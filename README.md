# SUB-BROWSER 🐉

A fast, minimal, dragon-themed web browser. **GPL-3.0** licensed.

> **Editions:** Desktop — Windows / Linux (deb · rpm · Arch) / macOS · Android 7.0+
> Every page you visit announces you as `SUBBrowser/1.0`.

## Features
- 🗂 **Multi-tab** — Ctrl+T / Ctrl+W, tab chips with one-tap close
- 🔍 **Smart address bar** — type a URL to go direct, anything else becomes a Google search
- 🐉 **Custom new-tab page** — SUB dragon branding + search, dark gradient
- 🌑 **Dark dragon theme** — `#050505` base, `#29ABE2` dragon-blue accents
- 🛡 **Content blocking** built in (ad-block domain list at the engine layer)
- 📲 Android: opens external http/https links, installable as default browser candidate

## Engine

Both editions run on the **Gecko** engine — the same web platform on desktop and mobile.

> **Branding rule:** the engine is an implementation detail and is *never* named in
> the app UI. The app is simply **SUB-BROWSER**, everywhere.

## Editions

| Edition | Tech | Distribution |
|---|---|---|
| Desktop | Native Rust app, Gecko engine | Multi-OS installers: `.deb`, `.rpm`, `.pkg.tar.zst`, Windows installer, macOS `.dmg` |
| Android | GeckoView, engine bundled in the APK | Production-signed APK — zero system-browser dependency |

## Build from source

### Desktop (Rust)
```bash
cd desktop/src-tauri
cargo build --release    # binary: target/release/sub-browser
```

### Android (no Gradle needed)
```bash
cd android
ANDROID_HOME=/path/to/android-sdk bash build.sh
# → build/SUB-BROWSER-<version>.apk
```
Toolchain: Android SDK build-tools 34, JDK 17, aapt2 + javac + d8 + zipalign + apksigner.
The GeckoView engine is downloaded and bundled into the APK at build time.

## Repo layout
```
desktop/    Rust desktop browser (src-tauri/, ui/)
android/    Android browser app + build.sh (aapt2/d8 pipeline, GeckoView bundled)
.github/    Release workflow (multi-OS packaging + Android)
LICENSE     GNU GPL v3
```

## License
Copyright (C) 2026 Subhadip Sarkar

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).
