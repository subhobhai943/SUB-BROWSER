# SUB-BROWSER 🐉

A fast, minimal, dragon-themed web browser. **GPL-3.0** licensed.

> **Apps:** Desktop — Windows / Linux / macOS · Android — 7.0+
> Every page you visit announces you as `SUBBrowser/1.0`.

## Features
- 🗂 **Multi-tab** — Ctrl+T / Ctrl+W, tab chips with one-tap close
- 🔍 **Smart address bar** — type a URL to go direct, anything else becomes a Google search
- 🐉 **Custom new-tab page** — SUB dragon branding + search, dark gradient
- 🌑 **Dark dragon theme** — `#050505` base, `#29ABE2` dragon-blue accents
- 🔒 **Sandboxed tab content**, context-isolated shell, persisted profile
- 📲 Android: opens external http/https links, installable as default browser candidate

## Engines

| Platform | Engine | Notes |
|---|---|---|
| Desktop | Chromium (Electron 45) | Full modern web platform |
| Android | Bundled engine — zero system dependency | Ships its own engine inside the APK |

The engine is an implementation detail — the app is simply **SUB-BROWSER**, everywhere.

## Build from source

### Desktop (Windows / Linux / macOS)
```bash
cd desktop
npm install        # downloads the engine (~110 MB, first time)
npm start          # run (needs a display)
npm run smoke      # headless self-test (xvfb)
npm run dist:linux # AppImage
```

### Android (no Gradle needed)
```bash
cd android
ANDROID_HOME=/path/to/android-sdk bash build.sh
# → build/SUB-BROWSER-1.0.apk
```
Toolchain: Android SDK build-tools 34, JDK 17, aapt2 + javac + d8 + zipalign + apksigner.
A debug keystore is expected at `$ANDROID_HOME/debug.jks` (see build.sh).

## Repo layout
```
desktop/    Electron browser (main.js, renderer/, assets/)
android/    Android browser app + build.sh (aapt2/d8 pipeline)
LICENSE     GNU GPL v3
```

## License
Copyright (C) 2026 Subhadip Sarkar

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

### Rust edition (desktop-rust/)
Native Rust browser built on Tauri 2 (WRY/WebKitGTK engine, ~7.5 MB binary):
```bash
cd desktop-rust/src-tauri
cargo build --release    # binary: target/release/sub-browser
```
Requires webkit2gtk-4.1 + gtk3 dev packages (Linux).
