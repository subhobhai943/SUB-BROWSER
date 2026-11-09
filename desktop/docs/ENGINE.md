# SUB-BROWSER Desktop v2 — Engine Architecture

Host proven on: GPU-less Debian 12, 2 vCPU, rust 1.99 — see the full feasibility
spike in `VERDICT.md` at `/opt/gecko-desktop/` (repo-external artifact).

## TL;DR

The Tauri 2 shell (toolbar chrome + tab manager) drives the **official Servo
v0.8.0 `servoshell` binary over a loopback WebDriver IPC session**. This is the
proven "Option 2" path: zero engine compile time, correct real-world rendering
on a GPU-less box via WebRender's SWGL software path. The in-process
`servo` crate embed ("Option 1") lives behind the same `EngineBackend` trait as
the cargo feature `embed` and replaces the tile stream the moment its compile
is green on CI.

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
| `crates/sub-browser/src/engine/embed.rs` | `embed` feature stub for the in-process `servo` crate. Deliberately NOT wired to the dependency yet. |
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
savings). Request-level blocking arrives with Option 1 (`servo` crate's
`NetworkDelegate`/`AllowOrDeny`), or a local MITM proxy (not chosen).

## Switching to the `embed` backend (Option 1) — procedure

The servo crate (0.8.x on crates.io, monthly releases, MPL-2.0) exposes
`ServoBuilder`/`WebViewBuilder`/`WebViewDelegate` + `SoftwareRenderingContext`
for true in-process embedding on this same GPU-less class of machine.

1. CI preconditions (already installed on this box): rust 1.99.x, clang,
   autoconf, cmake, libglib2.0-dev, fontconfig/freetype dev, gtk3, gstreamer
   dev (or drop `media-gstreamer`).
2. `crates/sub-browser/Cargo.toml`: add
   `servo = { version = "0.8", default-features = false, features = ["js_jit"], optional = true }`
   and make the `embed` feature pull it (`embed = ["dep:servo"]`).
   First cold build: 60–150 min on 2 vCPU (mozjs C++); cache with sccache.
3. Implement `EmbedBackend` in `engine/embed.rs` against the trait:
   one `Servo` instance per profile, one `WebView` per tab, frames blitted
   from `SoftwareRenderingContext` into the tile region **zero-copy** (replaces
   screenshot polling), input via `WebView::notify_event`, adblock via the
   embedder's network delegate (request-level — strictly better than v1).
4. `main.rs`: select backend from settings (`engine=embed|ipc`); profile and
   store dirs are already shared, so users switch engines without losing
   cookies/history/bookmarks.
5. No UI changes: the toolbar already consumes frames + `EngineBackend`-shaped
   commands only.

## Build & run

```sh
cargo build --release                 # workspace root; first build ~1-3 min (tauri only)
./crates/sub-browser/target/release/sub-browser-smoke https://example.com/   # headless E2E check
DISPLAY=:99 ./crates/sub-browser/target/release/sub-browser https://example.com/
# engine binary override: SUB_SERVOSHELL=/path/to/servoshell
# shipped layout: put `servoshell` (+ its resources/) beside the binary or in ./engine/
```

`cargo test` covers: base64 round-trip, SQLite store, URL/host matching, WD
percent-encoding. The GUI E2E smoke (`smoke`) asserts: engine spawn, session,
navigation, stable title, PNG screenshot > 10 KB, viewport rect, adblock
injection without JS error, back/forward/reload, tab close.
