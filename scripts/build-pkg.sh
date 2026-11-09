#!/usr/bin/env bash
# build-pkg.sh — macOS .pkg preparation for SUB-BROWSER (GPL-3.0)
# On a macOS runner this drives pkgbuild/productbuild. On Linux it validates
# the payload layout and emits the component plist + exact commands.
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
DIST_DIR="${DIST_DIR:-./dist}"
IDENTIFIER="${PKG_IDENTIFIER:-dev.subbrowser.SUB-BROWSER}"
PAYLOAD="${1:-${PAYLOAD:-}}"
usage() {
  cat <<EOF
Usage: $(basename "$0") [PAYLOAD_DIR]
  PAYLOAD_DIR  macOS payload root, expected layout:
      payload/Applications/SUB-BROWSER.app/Contents/{MacOS,Resources,...}
Env: VERSION=$VERSION DIST_DIR=$DIST_DIR PKG_IDENTIFIER=$IDENTIFIER
On macOS: builds $IDENTIFIER-$VERSION.pkg via pkgbuild (+productbuild distribution).
On Linux: validates layout only, writes the component plist.
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi

[ -n "$PAYLOAD" ] || { usage >&2; echo "ERROR: payload dir required" >&2; exit 2; }
[ -d "$PAYLOAD" ] || { echo "ERROR: no such dir: $PAYLOAD" >&2; exit 1; }
mkdir -p "$DIST_DIR"

# --- validate payload -------------------------------------------------------
FAIL=0
check() { if [ -e "$PAYLOAD/$1" ]; then echo "ok: $1"; else echo "MISSING: $1" >&2; FAIL=1; fi; }
check "Applications/SUB-BROWSER.app"
check "Applications/SUB-BROWSER.app/Contents/MacOS"
# app binary named same as bundle
APPBIN="$PAYLOAD/Applications/SUB-BROWSER.app/Contents/MacOS"
ls "$APPBIN" >/dev/null 2>&1 || FAIL=1
[ -f "$PAYLOAD/Applications/SUB-BROWSER.app/Contents/Info.plist" ] && echo "ok: Info.plist" || { echo "WARN: Info.plist missing" >&2; }
# mach-o magic check for the main binary (434d6800 little-endian / cafebabe / cdfacf)
if [ -f "$APPBIN/sub-browser" ] || [ -f "$APPBIN/SUB-BROWSER" ]; then
  BIN="$(ls "$APPBIN" | head -1)"
  MAGIC="$(od -An -tx1 -N4 "$APPBIN/$BIN" | tr -d ' \n')"
  case "$MAGIC" in
    cffaedfe|cefaedfe|cafebabe|feedfacf) echo "ok: Mach-O binary ($BIN, magic $MAGIC)";;
    *) echo "WARN: $BIN is not Mach-O (magic $MAGIC) — wrong-arch payload?" >&2;;
  esac
fi
[ "$FAIL" -eq 0 ] || { echo "ERROR: payload layout incomplete (see MISSING above)" >&2; exit 1; }
echo "== payload validated: $PAYLOAD"

# --- component plist (used by productbuild distribution) --------------------
cat > "$DIST_DIR/components.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<array>
  <dict>
    <key>pkg-path</key>   <string>/Applications</string>
    <key>pkg-id</key>     <string>$IDENTIFIER</string>
    <key>pkg-version</key><string>$VERSION</string>
  </dict>
</array>
</plist>
EOF

if command -v pkgbuild >/dev/null 2>&1 && [ "$(uname -s)" = "Darwin" ]; then
  OUT="$DIST_DIR/SUB-BROWSER-$VERSION.pkg"
  pkgbuild --root "$PAYLOAD" \
           --identifier "$IDENTIFIER" \
           --version "$VERSION" \
           --install-location / \
           --sign "${PRODUCTSIGN_IDENTITY:-}" \
           "$OUT" 2>/dev/null || \
  pkgbuild --root "$PAYLOAD" --identifier "$IDENTIFIER" --version "$VERSION" \
           --install-location / "$OUT"
  echo "== built: $OUT"
  pkgutil --check-signature "$OUT" 2>/dev/null || echo "(unsigned; run productsign for Developer ID)"
  sha256sum "$OUT"
else
  cat <<EOF

On the macOS runner run:
  pkgbuild --root "$PAYLOAD" \\
           --identifier "$IDENTIFIER" \\
           --version "$VERSION" \\
           --install-location / \\
           SUB-BROWSER-$VERSION.pkg

  # notarizable installer (recommended):
  productbuild --component "SUB-BROWSER-$VERSION.pkg" /Applications \\
               --distribution distribution.plist SUB-BROWSER-$VERSION-installer.pkg
  productsign --sign "Developer ID Installer: <TEAM>" in.pkg out.pkg
  xcrun notarytool submit out.pkg --keychain-profile AC_PASSWORD --wait
  xcrun stapler staple out.pkg

Linux box: validated payload + wrote $DIST_DIR/components.plist only (pkgbuild
is macOS-only). CI agent: run this step on the macos runner.
EOF
fi
