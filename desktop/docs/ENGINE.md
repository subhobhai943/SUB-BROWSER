# SUB-BROWSER Desktop v2 — Engine Architecture

Host proven on: GPU-less Debian 12, 2 vCPU, rust 1.99 — see the full feasibility
spike in `VERDICT.md` at `/opt/gecko-desktop/` (repo-external artifact).

## TL;DR

The Tauri 2 shell (toolbar chrome + tab manager) drives the **official Servo
v0.8.0 `servoshell` binary over a loopback WebDriver IPC session**. This is the
proven "Option 2" path: zero engine compile time, correct real-world rendering
on a GPU-less box via WebRender's SWGL software path. The in-process
`servo` crate embed ("Option 1") now lives behind the same `EngineBackend`
trait as the cargo feature `embed` — implemented and headless-verified
(`embed-smoke`); the chrome still defaults to IPC until backend selection
lands in `main.rs` (see "The embed backend" below).

```
┌────────────────────────── Tauri window "main" ──────────────────────────┐
│  toolbar webview (trusted, local UI)                                     │
│  ┌ tab strip ────────────────────────────────────────────────┐          │
│  ├ URL/nav row (back fwd reload home ★ shield close) ────────┤          │
│  │ engine view region                                        │          │
│  │   <img> live tile stream  (WebDriver screenshots ~10fps)  │          │
│  │   input: click/key/wheel → W3C actions / scrollBy         │          │
│  └───────────────────────────────────────────────────────────┘          │
└───────────────▲ sub-state/frame/adblock-count │ sub-cmd ▼───────────────┘
        Rust shell (main.rs) ── EngineBackend trait ── ServoIpcBackend
                                     │                    │ spawn
                              SQLite store (rusqlite)    ▼
                     <config>/sub-browser/store/<p>   servoshell -z
                     <config>/sub-browser/profile/<p> --webdriver=127.0.0.1:<rand>
                                                      --config-dir=<profile>
```

## Components

| File | Role |
|---|---|
| `crates/sub-browser/src/engine/mod.rs` | `EngineBackend` trait: create_view, navigate, back/forward/reload, url, title, screenshot, viewport, focus, close_view, inject_adblock, click, key, scroll, `blocked_event_stream`, describe. |
| `crates/sub-browser/src/engine/servo_ipc.rs` | **ServoIpcBackend**: supervises one `servoshell` child per profile (`--headless --webdriver=<random-port> (bare port; host:port form rejected by 0.8.0; server binds loopback) --config-dir=<config_dir>/sub-browser/profile/<p>`); random port grabbed via an ephemeral `TcpListener` then released; readiness polled via `GET /status`; child killed on Drop. Tabs = WebDriver window handles (`POST /window/new {type:"tab"}`); tab_id→handle map; `focus()` = `POST /window` switch. Includes the adblock content script (see below). |
| `crates/sub-browser/src/engine/wd.rs` | Hand-rolled tiny WebDriver client: one blocking `TcpStream` per request, `Connection: close`, Content-Length framing (+chunked fallback), W3C `value` unwrapping and error mapping. No reqwest/hyper/thirtyfour. |
| `crates/sub-browser/src/engine/embed.rs` | `embed` feature: **in-process `servo` crate backend (IMPLEMENTED)**. Dedicated embed thread owns `Servo` + `SoftwareRenderingContext` (surfman software adapter = SWGL, GPU-less); one `WebView` per tab; trait calls marshal over mpsc with reply channels; frames via `notify_new_frame_ready` → `WebView::paint` → `read_to_image` back buffer; input via `notify_input_event`; adblock via `evaluate_javascript`. Headless-verified by the `embed-smoke` bin. |
| `crates/sub-browser/src/store.rs` | rusqlite (bundled) bookmarks/history/settings at `<config_dir>/sub-browser/store/<profile>/sub-browser.db` — beside the engine profile so switching backends keeps all state. |
| `crates/sub-browser/src/main.rs` | Tauri shell: window + one trusted toolbar webview (full window: chrome strip + tile region), command bus (`sub-cmd`), poller thread (~10 fps screenshot frames; 2 Hz page-ready detect → adblock inject + history + tab meta), adblock badge events. |
| `crates/sub-browser/src/bin/smoke.rs` | `sub-browser-smoke` CLI harness (no GUI) used by CI. |
| `ui/index.html` | Chrome + tile region (`<img>` swap-decoded per frame to avoid flicker), input forwarding scaled to engine viewport. |

## Proven WebDriver surface (servoshell 0.8.0, verified on this host)

| Command | Status |
|---|---|
| `POST /session` / `DELETE /session/{id}` | ✅ one session per process; recreate if dropped |
| `GET /status` (`ready` flag) | ✅ used for spawn-readiness poll |
| `POST /session/{id}/url`, `GET …/url`, `GET …/title` | ✅ apply to **focused** context |
| `POST …/back`, `…/forward`, `…/refresh` | ✅ |
| `POST …/window/new {type:"tab"}` → handle, `POST …/window {handle}` switch, `DELETE …/window/{handle}`, `GET …/window/handles` | ✅ handles look like `PainterId: 1, TopLevelBrowsingContext(0,N)` (percent-encode in paths — `wd::pct`) |
| `POST …/window/rect {}` | ✅ returns viewport (1024×740 default headless) |
| `GET …/screenshot` | ✅ base64 PNG (example.com ≈ 160 KB) |
| `POST …/execute/sync` | ✅ used for adblock + scrollBy; **requires** `Content-Type: application/json` |
| `POST …/actions` W3C pointer/key | ✅ pointer move/down/up accepted (null result); `POST …/keys` is NOT supported (405) — use actions |
| element `find`/`click` | find works; we prefer JS + pointer actions |

Gotchas found by probing (do not regress):
- `--webdriver=127.0.0.1:PORT` is rejected (`"is not expected in this context"`); pass a bare `PORT`. The server binds loopback itself — still loopback-only, verified (`/status` unreachable on external iface by bind address).
- Any JSON POST without `Content-Type: application/json` → `Invalid Content-Type`.
- `POST /window/new` is the *only* window-creation route (`POST /window` = switch).

## Ad blocking (v1: post-load DOM filter via injected content script)

Request-level interception needs a proxy or engine hooks — out of scope for the
WebDriver path. Instead, on every page-settle the shell `execute/sync`s a
content script that:
1. scans every `img, iframe, script, embed, source, frame, [background]` `src`
   /`data-src` and removes elements whose host suffix-matches the embedded
   **539-domain blocklist** (`crates/sub-browser/adblock_domains.txt`,
   `include_str!`, dot-boundary suffix match, `www.` strip);
2. removes `a[href]` to blocked hosts with ad-ish class/id;
3. collapses visible fixed/absolute containers matching `ad|ads|advert|banner|sponsor|doubleclick|pop` class/id patterns;
4. returns `[[host, kind], …]` removals → counted and badged in the toolbar
   (`adblock-count` event, throttled ~400 ms), toggle persisted in settings.

The engine-side stream API (`blocked_event_stream`) carries the same events for
any backend, so the embed backend can emit real request-blocked events later.

**Known v1 limitation:** post-load means a blocked ad image may briefly occupy
layout before removal and blocked network requests still fetch (no byte
savings). This applies to BOTH backends: servo 0.8.0's embedder API has no
request-level block hook (`evaluate_javascript` in embed is the same DOM
filter, minus WebDriver latency). A local proxy or an upstream API would be
needed for request-level blocking.

## The `embed` backend (Option 1) — status: IMPLEMENTED + verified

`cargo build --release --features embed` compiles the full servo 0.8.0 crate
graph in-process. The earlier spike E0599 was an API-invention problem: the
real 0.8.0 surface is `ServoBuilder::default().event_loop_waker(b).build()`,
`Servo::spin_event_loop()` (pump-driven, no `handle_wakeup`),
`WebView::{load, reload, go_back(n), go_forward(n), paint, resize,
notify_input_event, evaluate_javascript, page_title, url, load_status}` —
see the crate's `examples/winit_minimal.rs` for the canonical loop.

Design (all confirmed against crates.io servo-0.8.0 source):

* **Thread discipline**: servo objects are `Rc`/`RefCell`, single-threaded →
  one dedicated `sub-embed` thread builds `Servo` over
  `SoftwareRenderingContext` (1280×800 default, `SUB_EMBED_W/H` override) and
  pumps `spin_event_loop` at 8 ms + on `EventLoopWaker` wake (wired to the
  command channel). The trait surface stays `Send + Sync` via request/reply
  mpsc.
* **Native surface**: `WebViewDelegate::notify_new_frame_ready` flags dirty;
  the pump paints the active tab into the software context (`paint` +
  `present`). `screenshot()` waits for `LoadStatus::Complete` + a fresh
  frame, paints, and reads the back buffer directly with
  `RenderingContext::read_to_image` — real pixels, no WebDriver base64
  round-trip. PNG encoding is a once-per-call compatibility shim for the
  trait; a chrome-integrated build can blit the `RgbaImage` zero-copy.
* **Input**: `notify_input_event` (mouse down/up, wheel pixels — note Servo
  inverts delta sign vs. page scroll, keyboard via keyboard-types).
* **Adblock under embed**: 0.8.0 DOES expose JS eval
  (`WebView::evaluate_javascript` → `JSValue::String`), so the SAME 539-domain
  DOM-filter script runs over one in-process channel. Honest caveat: it is
  still the post-load DOM filter (same semantics as IPC — ads fetch before
  removal). The `NetworkDelegate` request-level hook never shipped as public
  API in 0.8.0, so embed does NOT upgrade blocking strength; it removes
  WebDriver latency per injection.
* **Tabs**: no public `WebView::deinit` in 0.8.0; `close_view` drops the
  handle and Servo retires the pipeline internally.
* **TLS**: `rustls::crypto::aws_lc_rs::default_provider().install_default()`
  at spawn (same bootstrap as the official example; servo-net needs it).

### Speed hack (compile-cache reuse)

`/opt/gecko-desktop/spike-embed/target` holds a complete release build of the
servo 0.8.0 graph (incl. mozjs C++). Building the shell with
`CARGO_TARGET_DIR=/opt/gecko-desktop/spike-embed/target` + the SAME servo
feature set (`default-features=false, ["bundled","js_jit","clipboard"]`) and a
stock release profile (workspace `strip`/`codegen-units` removed so
every dep's fingerprint matches) reuses those artifacts; only the shell
crates + a few feature-unified deps rebuild. NEVER `cargo clean` that dir.

### Headless verification (no display needed)

```sh
CARGO_TARGET_DIR=/opt/gecko-desktop/spike-embed/target \
  cargo build --release --features embed --bin embed-smoke
/opt/gecko-desktop/spike-embed/target/release/embed-smoke https://example.com/
# writes /tmp/embed-smoke.png; asserts title, >10 KB native-surface shot,
# adblock JS eval, traversal, input, close. See docs/ENGINE.md results table.
```

Results on this box (2 vCPU, SWGL):

| Metric (this box, 2 vCPU, SWGL, 1280×800) | embed | IPC ( WebDriver) |
|---|---|---|
| Engine boot | **84 ms** | seconds (spawn servoshell + poll `/status`) |
| example.com → stable title | 600 ms | comparable (network-bound) |
| Screenshot (native surface read + one PNG encode) | **8 ms**, 152,670 B (visually verified full layout) | ~200 ms+ WebDriver base64 round-trip |
| Sustained capture (servo.org, PNG-encoded every frame) | **200 captures/s** | ~10 fps poll cap |
| Frames composited during smoke | 2023, stable, no crashes | n/a (engine-side) |
| Adblock injection | ✅ via `evaluate_javascript` (post-load DOM filter, same semantics as IPC) | ✅ via `execute/sync` |
| Back/forward/reload/focus/click/key/scroll/close | ✅ all respond | ✅ (WebDriver) |

No display server is required (surfman software adapter). `libEGL
warning: pci id … driver (null)` on stderr is harmless — surfman falls back
to the software adapter exactly as designed.

### Default-feature decision (2026-11-09)

Embed **renders, is dramatically faster, and passed the full smoke** — but it
stays NON-default for two honest reasons:

1. `main.rs` is still typed against `ServoIpcBackend` (its `engine` slot is
   `Arc<ServoIpcBackend>`, not a trait object), so the shipped app cannot
   select embed yet. Making `embed` a default cargo feature would add a
   1–2 h servo/mozjs cold build for CI/users while the runtime behaviour
   stays IPC — a pure compile-time penalty with zero runtime effect.
2. Chrome integration (below) is the next change; flip `default = ["embed"]`
   in the same commit that switches `spawn_engine_and_pump`, ideally behind
   a settings key with IPC fallback (embed init can fail where IPC cannot,
   e.g. exotic surfman environments).

Recommendation: once backend selection lands, make embed the default on
Linux — the numbers above (84 ms boot, 8 ms zero-copy captures vs ~200 ms
WebDriver polling) justify it outright.

## Switching to the `embed` backend — remaining chrome work

The servo crate (0.8.x on crates.io, monthly releases, MPL-2.0) exposes
`ServoBuilder`/`WebViewBuilder`/`WebViewDelegate` + `SoftwareRenderingContext`
for true in-process embedding on this same GPU-less class of machine.

1. Done: dependency + feature wiring, `EmbedBackend` implementation, and
   headless render proof (sections above). The old plan's "adblock via
   NetworkDelegate request-level" step is VOID — 0.8.0 has no such public
   hook; embed reuses the DOM filter over `evaluate_javascript`.
2. `main.rs`: select backend from settings (`engine=embed|ipc`); profile and
   store dirs are already shared, so users switch engines without losing
   cookies/history/bookmarks. `EmbedBackend` already satisfies every trait
   method the shell uses, so this is a few-line change in
   `spawn_engine_and_pump`.

## Build & run

```sh
cargo build --release                 # workspace root; first build ~1-3 min (tauri only)
./crates/sub-browser/target/release/sub-browser-smoke https://example.com/   # headless E2E check
# embed backend (in-process servo; see "Speed hack" for cache reuse):
CARGO_TARGET_DIR=/opt/gecko-desktop/spike-embed/target cargo build --release --features embed --bin embed-smoke
/opt/gecko-desktop/spike-embed/target/release/embed-smoke https://example.com/  # headless embed E2E
DISPLAY=:99 ./crates/sub-browser/target/release/sub-browser https://example.com/
# engine binary override: SUB_SERVOSHELL=/path/to/servoshell
# shipped layout: put `servoshell` (+ its resources/) beside the binary or in ./engine/
```

`cargo test` covers: base64 round-trip, SQLite store, URL/host matching, WD
percent-encoding. The GUI E2E smoke (`smoke`) asserts: engine spawn, session,
navigation, stable title, PNG screenshot > 10 KB, viewport rect, adblock
injection without JS error, back/forward/reload, tab close.

## embed status (2026-11-09, v2.0.0)
- `--features embed` compiles clean (glue fix: `set_adblock_enabled` moved onto the
  `EngineBackend` trait with a default no-op; IPC backend overrides it).
- `embed-smoke` PASS headless on GPU-less box: in-process servo 0.8.0, software
  surface 1280x800 zero-copy; example.com loaded 621ms, title fetched, native
  screenshot 152KB, adblock injection OK, **103.78 captures/s** (vs ~10fps via IPC).
- Selection: settings store `engine`="embed"|"ipc" (default ipc). Linux release
  packages keep the IPC engine sidecar; embed becomes default once chrome
  integration (input map + multi-tab) lands in v2.1.
- Build note (2-core boxes): reuse the servo-crate dep cache via
  `CARGO_TARGET_DIR=<spike target>` + ≥8 GB swap (link-time OOM, servo#21792).
