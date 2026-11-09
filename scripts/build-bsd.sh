#!/usr/bin/env bash
# build-bsd.sh — FreeBSD pkg package for SUB-BROWSER (GPL-3.0)
# Output: sub-browser-$VERSION.txz = tar.xz of the staging tree (paths relative
# to /) + a ._ / +MANIFEST member, matching what pkg(8) consumes from a repo.
# If pkg is installed with repo support you can also `pkg repo`; this script
# produces the package file itself (that's all `pkg add file.txz` needs).
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
ARCH="${ARCH:-}"
DIST_DIR="${DIST_DIR:-./dist}"
STAGING="${1:-${STAGING:-}}"
MAINTAINER="${MAINTAINER:-packaging@sub-browser.example}"
usage() {
  cat <<EOF
Usage: $(basename "$0") [STAGING_DIR]
  STAGING_DIR  compiled tree: bin/sub-browser, share/sub-browser/*, share/icons
Env: VERSION=$VERSION ARCH=$ARCH DIST_DIR=$DIST_DIR
Arch naming for FreeBSD: amd64 | i386 | aarch64 (set ARCH when cross-naming)
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi
[ -n "$STAGING" ] || { usage >&2; exit 2; }
STAGING="$(readlink -f "$STAGING")"
[ -x "$STAGING/bin/sub-browser" ] || { echo "ERROR: $STAGING/bin/sub-browser missing" >&2; exit 1; }
: "${ARCH:=$(uname -m)}"
case "$ARCH" in x86_64) ARCH=amd64 ;; esac   # FreeBSD naming

ROOT="$(mktemp -d)"; trap 'rm -rf "$ROOT"' EXIT
TREE="$ROOT/tree"
mkdir -p "$TREE/usr/local/bin" "$TREE/usr/local/share"
cp -a "$STAGING/bin/sub-browser" "$TREE/usr/local/bin/"
cp -a "$STAGING/share/." "$TREE/usr/local/share/" 2>/dev/null || true
mkdir -p "$TREE/usr/local/share/applications"
cat > "$TREE/usr/local/share/applications/sub-browser.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=SUB-BROWSER
GenericName=Web Browser
Exec=sub-browser %U
Icon=sub-browser
Terminal=false
Categories=Network;WebBrowser;
DESK

# flat dists-style layout used by pkg add: bin/ and share/ at archive root
FLAT="$ROOT/flat"
mkdir -p "$FLAT"
cp -a "$TREE/usr/local/." "$FLAT/"

FLATSIZE="$(du -sk "$FLAT" | cut -f1)"
# +MANIFEST: pkg(8) metadata (JSON)
cat > "$FLAT/+MANIFEST" <<EOF
{
  "arch": "$ARCH",
  "desc": "SUB-BROWSER - Gecko/servo-based web browser (self-contained)",
  "origin": "www/sub-browser",
  "maintainer": "$MAINTAINER",
  "www": "https://github.com/subhobhai943/SUB-BROWSER",
  "comment": "Gecko/servo-based web browser",
  "name": "sub-browser",
  "version": "$VERSION",
  "prefix": "/usr/local",
  "licenselogic": "single",
  "licenses": ["GPL3"],
  "flatsize": $(( FLATSIZE * 1024 )),
  "manifestdir": "+",
  "deps": {}
}
EOF

mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/sub-browser-$VERSION-$ARCH.txz"
OUT_ABS="$(realpath -m "$OUT")"
# pkg expects +MANIFEST at archive root alongside files
( cd "$FLAT" && tar -cJf "$OUT_ABS" +MANIFEST bin share )

echo "== built: $OUT"
echo "-- members --"; tar -tJf "$OUT" | head -15 || true
echo "-- manifest --"; tar -xJf "$OUT" +MANIFEST -O 2>/dev/null || tar -xJf "$OUT" ./+MANIFEST -O || true
echo "Install on FreeBSD:  sudo pkg add ./sub-browser-$VERSION-$ARCH.txz"
sha256sum "$OUT"
