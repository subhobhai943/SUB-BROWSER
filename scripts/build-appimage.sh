#!/usr/bin/env bash
# build-appimage.sh — AppImage builder for SUB-BROWSER (GPL-3.0)
# Uses appimagetool if present; otherwise emits a fully self-extracting
# .AppImage (POSIX sh header + squashed-free tar.gz payload), which FUSE users
# can run directly and everyone can --appimage-extract.
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
ARCH="${ARCH:-$(uname -m)}"
DIST_DIR="${DIST_DIR:-./dist}"
STAGING="${1:-${STAGING:-}}"
usage() {
  cat <<EOF
Usage: $(basename "$0") [STAGING_DIR]
  STAGING_DIR  compiled tree: bin/sub-browser, share/sub-browser/*, share/icons
Env: VERSION=$VERSION ARCH=$ARCH DIST_DIR=$DIST_DIR
Optionally provide APPRUN=script and ICON=png; defaults are generated.
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi
[ -n "$STAGING" ] || { usage >&2; exit 2; }
STAGING="$(readlink -f "$STAGING")"
[ -x "$STAGING/bin/sub-browser" ] || { echo "ERROR: $STAGING/bin/sub-browser missing" >&2; exit 1; }

ROOT="$(mktemp -d)"; trap 'rm -rf "$ROOT"' EXIT
APP="$ROOT/sub-browser.AppDir"
mkdir -p "$APP/usr/bin" "$APP/usr/share"
cp -a "$STAGING/bin/sub-browser" "$APP/usr/bin/"
cp -a "$STAGING/share/." "$APP/usr/share/" 2>/dev/null || true

cat > "$APP/sub-browser.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SUB-BROWSER
Exec=sub-browser %U
Icon=sub-browser
Categories=Network;WebBrowser;
Terminal=false
EOF
[ -f "$APP/.DirLink" ] || true
echo "$VERSION" > "$APP/usr/AppImageVersion"

ICON="${ICON:-}"
[ -z "$ICON" ] && [ -d "$STAGING/share/icons" ] && ICON="$(find "$STAGING/share/icons" -name '*.png' | head -1 || true)"
[ -n "$ICON" ] && cp "$ICON" "$APP/sub-browser.png" || printf '\x89PNG\r\n\x1a\n' > "$APP/sub-browser.png" # placeholder; set ICON= for real

# AppRun
if [ -n "${APPRUN:-}" ] && [ -f "$APPRUN" ]; then
  cp "$APPRUN" "$APP/AppRun"
else
  cat > "$APP/AppRun" <<'EOF'
#!/usr/bin/env bash
SELF="$(readlink -f "${BASH_SOURCE[0]:-$0}")"
HERE="$(dirname "$SELF")"
export LD_LIBRARY_PATH="$HERE/usr/lib:$HERE/usr/lib64:${LD_LIBRARY_PATH:-}"
exec "$HERE/usr/bin/sub-browser" --appimage "${@}"
EOF
fi
chmod +x "$APP/AppRun" "$APP/usr/bin/sub-browser"

mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/sub-browser-$VERSION-$ARCH.AppImage"

if command -v appimagetool >/dev/null 2>&1; then
  ARCH="$ARCH" appimagetool "$APP" "$OUT"
  echo "== built with appimagetool: $OUT"
else
  echo "appimagetool not found; producing self-extracting archive AppImage"
  PAYLOAD="$ROOT/payload.tar.gz"
  tar -czf "$PAYLOAD" -C "$APP" .
  {
    cat <<'HDR'
#!/bin/sh
# SUB-BROWSER AppImage (self-extracting)
set -e
DIR="${APPIMAGE_EXTRACT_DIR:-${TMPDIR:-/tmp}/sub-browser-appimage.$$}"
if [ "${1:-}" = "--appimage-extract" ]; then
  mkdir -p squashfs-root 2>/dev/null || true
  DIR="$(pwd)/squashfs-root"; tar -xzf "$0" -C "$DIR" 2>/dev/null || { skip=1; }
  [ "${skip:-}" = 1 ] && { tail -n +0 "$0" >/dev/null; }
fi
HDR
    # marker line; payload appended after it
    echo '#PAYLOAD-BELOW#'
    cat "$PAYLOAD"
  } > "$OUT"
  # pad header to exactly 4096 bytes; payload starts at fixed offset 4096
  python3 - "$OUT" "$PAYLOAD" <<'PY'
import sys, os
out, payload_path = sys.argv[1], sys.argv[2]
hdr = ("#!/bin/sh\n"
"# SUB-BROWSER AppImage (self-extracting tar.gz payload at byte 4096)\n"
"set -e\n"
'PROG="$0"\n'
'[ -L "$0" ] && PROG="$(readlink -f "$0")"\n'
"OFF=4096\n"
'if [ "${1:-}" = "--appimage-extract" ]; then\n'
'  DEST="${APPIMAGE_EXTRACT_DIR:-squashfs-root}"; mkdir -p "$DEST"\n'
'  tail -c +"$(($OFF+1))" "$PROG" | tar -xzf - -C "$DEST"\n'
'  echo "extracted to $DEST"; exit 0\n'
"fi\n"
'RUNDIR="${TMPDIR:-/tmp}/sub-browser.AppImage.$$"\n'
'mkdir -p "$RUNDIR"\n'
'tail -c +"$(($OFF+1))" "$PROG" | tar -xzf - -C "$RUNDIR"\n'
'chmod +x "$RUNDIR/AppRun" "$RUNDIR/usr/bin/sub-browser" 2>/dev/null || true\n'
'export APPIMAGE="$PROG"\n'
'"$RUNDIR/AppRun" "$@"\n'
"RC=$?\n"
'rm -rf "$RUNDIR"\n'
"exit $RC\n")
h = hdr.encode()
assert len(h) <= 4095, "header too big"
h = h + b"# " + b"_" * (4096 - len(h) - 3) + b"\n"
assert len(h) == 4096, len(h)
with open(out, "wb") as f:
    f.write(h)
    with open(payload_path, "rb") as p:
        while True:
            b = p.read(1 << 20)
            if not b: break
            f.write(b)
os.chmod(out, 0o755)
PY
  chmod +x "$OUT"
  echo "== built (self-extracting): $OUT"
fi

sha256sum "$OUT"
