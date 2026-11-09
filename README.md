# SUB-BROWSER 🐉

A fast, minimal, dragon-themed web browser. **GPL-3.0** licensed.

Two builds from one identity (`SUBBrowser/1.0` in every User-Agent):

| | Engine | Stack | Where |
|---|---|---|---|
| **Desktop** | Chromium (Electron 45) | JS/HTML/CSS | Windows / Linux / macOS |
| **Android** | System WebView (Chromium) | Java | Android 7.0+ |

## Features
- Multi-tab (Ctrl+T / Ctrl+W, tab chips with close)
- Smart address bar — URL direct, anything else → Google
- Custom new-tab page with SUB dragon branding + search
- Dark theme (`#050505` + dragon blue `#29ABE2`)
- Sandboxed tab content, contextIsolated shell, persisted profile

## Desktop
```bash
cd desktop
npm install
npm start          # requires a display
npm run smoke      # headless verification (needs xvfb)
npm run dist:linux # AppImage
```

## Android
```bash
cd android
ANDROID_HOME=/path/to/sdk bash build.sh   # no Gradle: aapt2 + javac + d8 + apksigner
# → build/SUB-BROWSER-1.0.apk (~70KB; engine comes from the OS WebView)
```

## License
Copyright (C) 2026 Subhadip Sarkar

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).
