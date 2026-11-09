# SUB-BROWSER Desktop v2 — Rust shell + Servo engine

Tauri 2 shell (Rust) whose content rendering is done by the **Servo 0.8.0
engine** (`servoshell` binary) driven over a loopback WebDriver IPC session.
Adblocking (539-domain blocklist + DOM filter pass), bookmarks, history and
settings persist per profile.

```
desktop/
  Cargo.toml              # workspace root
  crates/sub-browser/     # the app crate (Tauri shell + engine backends)
    src/main.rs           # shell: window, toolbar webview, command bus, frame pump
    src/lib.rs            # engine + store library layer
    src/engine/           # EngineBackend trait, servo_ipc backend, wd client, embed stub
    src/store.rs          # SQLite bookmarks/history/settings (rusqlite)
    src/bin/smoke.rs      # headless end-to-end smoke harness
    adblock_domains.txt   # embedded blocklist (include_str!)
  ui/                     # toolbar chrome + engine tile region (plain HTML)
  docs/ENGINE.md          # architecture, proven WebDriver surface, embed-switch guide
```

## Build

```sh
cargo build --release
```

## Run (needs an X display; e.g. `Xvfb :99 -screen 0 1280x800x24 &`)

```sh
DISPLAY=:99 ./crates/sub-browser/target/release/sub-browser https://example.com/
```

The engine binary is resolved from `$SUB_SERVOSHELL`, then `servoshell` beside
the app (or in `./engine/`), then the packaged dev path. Official Servo
releases: <https://github.com/servo/servo/releases> (v0.8.0
`servo-x86_64-linux-gnu.tar.gz`, checksum `.sha256` published beside it).

## Headless smoke test (used by CI)

```sh
./crates/sub-browser/target/release/sub-browser-smoke https://example.com/
# asserts: engine spawn + session, navigation, title, PNG screenshot >10KB,
# adblock injection without JS error, back/forward/reload. Prints SMOKE PASS.
cargo test    # unit tests (store, base64, url utils)
```

## Engine architecture & the embed switch

See [`docs/ENGINE.md`](docs/ENGINE.md). v1 uses the proven servoshell-as-engine
WebDriver transport (VERDICT Option 2); the in-process `servo` crate backend
(Option 1) is stubbed behind the `embed` cargo feature behind the same
`EngineBackend` trait and shares the profile/store layout, so switching keeps
all user state.

## v1 known limitations

- Page frames are WebDriver screenshots at ~10 fps (tile stream), not true
  surface embedding — input works via W3C pointer/key actions and scrollBy,
  but latency is demo-grade. Fixed by the `embed` backend.
- Ad blocking is post-load DOM filtering (elements removed after render;
  requests themselves still fetch). Fixed by the embed backend's network
  delegate or a local proxy.
- One WebDriver session per engine process (all tabs share one servoshell —
  matches Servo's single-session server).
- New tabs start on the search engine; the local newtab page cannot be shown
  inside the engine tile (engine cannot browse tauri-origin URLs).
