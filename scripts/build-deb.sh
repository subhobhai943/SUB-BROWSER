#!/usr/bin/env bash
# build-deb.sh — Debian package builder for SUB-BROWSER (GPL-3.0)
# Input: a compiled Linux binary tree with layout:
#   <staging>/bin/sub-browser
#   <staging>/share/sub-browser/*
#   <staging>/share/icons/...
# Output: $DIST_DIR/sub-browser_<VERSION>_<ARCH>.deb
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
ARCH="${ARCH:-}"
DIST_DIR="${DIST_DIR:-./dist}"
PREFIX="${PREFIX:-/usr}"
STAGING="${1:-${STAGING:-}}"
MAINTAINER="${MAINTAINER:-SUB-BROWSER Maintainers <packaging@sub-browser.example>}"
LICENSE="${LICENSE:-GPL-3.0-or-later}"
# Engine is Gecko/servo (NOT webkit). Runtime deps are distro-sensitive; keep
# Depends editable via $DEB_DEPENDS. Empty is also valid (static/self-contained build).
DEPENDS="${DEB_DEPENDS:-}"
DESCRIPTION="${DESCRIPTION:-SUB-BROWSER — a Gecko/servo-based web browser.
 Self-contained Gecko engine browser build for Linux x86_64 and arm64.}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [STAGING_DIR]
  STAGING_DIR  compiled tree: bin/sub-browser, share/sub-browser/*, share/icons
Env: VERSION=$VERSION ARCH=$ARCH DIST_DIR=$DIST_DIR PREFIX=$PREFIX
     DEB_DEPENDS='$DEPENDS' MAINTAINER='$MAINTAINER'
Example: $(basename "$0") /path/to/out-linux
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi

[ -n "$STAGING" ] || { usage >&2; echo "ERROR: staging dir required (arg 1 or \$STAGING)" >&2; exit 2; }
STAGING="$(readlink -f "$STAGING")"
[ -x "$STAGING/bin/sub-browser" ] || { echo "ERROR: $STAGING/bin/sub-browser missing or not executable" >&2; exit 1; }

if [ -z "$ARCH" ]; then
  case "$(uname -m)" in
    x86_64) ARCH=amd64 ;; aarch64) ARCH=arm64 ;; armv7l) ARCH=armhf ;;
    ppc64le) ARCH=ppc64el ;; s390x) ARCH=s390x ;; i686|i386) ARCH=i386 ;;
    *) echo "ERROR: unknown arch; set ARCH" >&2; exit 1 ;;
  esac
fi

PKG="sub-browser_${VERSION}_${ARCH}"
ROOT="$(mktemp -d)"; trap 'rm -rf "$ROOT"' EXIT
DEB="$ROOT/$PKG"
mkdir -p "$DEB/DEBIAN" "$DEB/$PREFIX"

# --- data tree -------------------------------------------------------------
cp -a "$STAGING/." "$DEB$PREFIX/"
# strip binaries if strip exists (best effort)
command -v strip >/dev/null && strip --strip-unneeded "$DEB$PREFIX/bin/sub-browser" 2>/dev/null || true

# --- desktop entry + icon ---------------------------------------------------
mkdir -p "$DEB$PREFIX/share/applications"
cat > "$DEB$PREFIX/share/applications/sub-browser.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SUB-BROWSER
GenericName=Web Browser
Comment=Gecko/servo-based web browser
Exec=sub-browser %U
Icon=sub-browser
Terminal=false
Categories=Network;WebBrowser;
MimeType=x-scheme-handler/http;x-scheme-handler/https;text/html;
StartupWMClass=sub-browser
EOF
# icon: prefer staged hicolor icon, else placeholder png if provided
ICON_SRC=""
[ -d "$DEB$PREFIX/share/icons" ] && ICON_SRC="$(find "$DEB$PREFIX/share/icons" -name '*.png' | head -1 || true)"
ICON_DST="$DEB$PREFIX/share/icons/hicolor/256x256/apps/sub-browser.png"
if [ -n "$ICON_SRC" ] && [ "$(readlink -f "$ICON_SRC")" != "$(readlink -f "$ICON_DST" 2>/dev/null || echo x)" ]; then
  mkdir -p "$(dirname "$ICON_DST")"
  cp "$ICON_SRC" "$ICON_DST"
elif [ -n "${ICON_PNG:-}" ] && [ -f "$ICON_PNG" ]; then
  mkdir -p "$DEB$PREFIX/share/icons/hicolor/256x256/apps"
  cp "$ICON_PNG" "$DEB$PREFIX/share/icons/hicolor/256x256/apps/sub-browser.png"
fi
# make PREFIX-relative if user chose non-/usr prefix for desktop files under etc fallback
if [ "$PREFIX" != "/usr" ]; then
  mkdir -p "$DEB/etc/xdg/menus" 2>/dev/null || true
fi

# --- symlinks in /usr/bin if prefix isn't /usr ------------------------------
if [ "$PREFIX" != "/usr" ]; then
  mkdir -p "$DEB/usr/bin"
  ln -sf "$PREFIX/bin/sub-browser" "$DEB/usr/bin/sub-browser"
fi

# --- control ---------------------------------------------------------------
CTRL="$DEB/DEBIAN/control"
SIZE_KB="$(du -sk "$DEB" | cut -f1)"
{
  echo "Package: sub-browser"
  echo "Version: $VERSION"
  echo "Section: web"
  echo "Priority: optional"
  echo "Architecture: $ARCH"
  echo "Maintainer: $MAINTAINER"
  [ -n "$DEPENDS" ] && echo "Depends: $DEPENDS"
  echo "Homepage: https://github.com/subhobhai943/SUB-BROWSER"
  echo "License: $LICENSE"
  echo "Installed-Size: $SIZE_KB"
  echo "Description: SUB-BROWSER web browser"
  printf '%s\n' "$DESCRIPTION" | sed 's/^/ /'
} > "$CTRL"

cat > "$DEB/DEBIAN/conffiles" <<'EOF'
EOF

# --- build -----------------------------------------------------------------
mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/${PKG}.deb"
chmod 0755 "$DEB/DEBIAN"
if command -v dpkg-deb >/dev/null 2>&1; then
  dpkg-deb --root-owner-group --build "$DEB" "$OUT"
else
  # Manual deb = ar archive of debian-binary + control.tar.gz + data.tar.gz
  echo "dpkg-deb not found; building .deb manually with ar+tar"
  ( cd "$DEB/DEBIAN" && tar -czf "$ROOT/control.tar.gz" --format=gnu . )
  ( cd "$DEB" && find . -mindepth 1 -not -path './DEBIAN*' -print0 | tar -czf "$ROOT/data.tar.gz" --format=gnu --null -T - )
  printf '2.0\n' > "$ROOT/debian-binary"
  ( cd "$ROOT" && ar rc "$OUT" debian-binary control.tar.gz data.tar.gz )
fi

echo "== built: $OUT"
command -v dpkg-deb >/dev/null && dpkg-deb --info "$OUT" | head -20 || true
command -v dpkg-deb >/dev/null && dpkg-deb --contents "$OUT" | head -20 || true
sha256sum "$OUT"
