#!/usr/bin/env bash
# build-rpm.sh — RPM package builder for SUB-BROWSER (GPL-3.0)
# Primary path: rpmbuild -bb with a generated spec (works on Debian box for
# cross-building AND on RHEL/Fedora family). Fallback: fpm if installed.
# Input staging tree: bin/sub-browser, share/sub-browser/*, share/icons
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
RELEASE="${RPM_RELEASE:-1}"
ARCH="${ARCH:-}"
DIST_DIR="${DIST_DIR:-./dist}"
STAGING="${1:-${STAGING:-}}"
SUMMARY="${SUMMARY:-SUB-BROWSER — Gecko/servo-based web browser}"
# Engine is Gecko/servo, NOT webkit2gtk. Keep runtime Requires editable.
REQUIRES="${RPM_REQUIRES:-}"   # e.g. "glibc >= 2.28" or "" for self-contained

usage() {
  cat <<EOF
Usage: $(basename "$0") [STAGING_DIR]
  STAGING_DIR  compiled tree: bin/sub-browser, share/sub-browser/*, share/icons
Env: VERSION=$VERSION RPM_RELEASE=$RELEASE ARCH=$ARCH DIST_DIR=$DIST_DIR
     RPM_REQUIRES='$REQUIRES'
Example: $(basename "$0") /path/to/out-linux
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi

[ -n "$STAGING" ] || { usage >&2; exit 2; }
STAGING="$(readlink -f "$STAGING")"
[ -x "$STAGING/bin/sub-browser" ] || { echo "ERROR: $STAGING/bin/sub-browser missing/not executable" >&2; exit 1; }

if [ -z "$ARCH" ]; then
  case "$(uname -m)" in
    x86_64) ARCH=x86_64 ;; aarch64) ARCH=aarch64 ;; armv7l) ARCH=armv7hl ;;
    *) echo "ERROR: unknown arch; set ARCH (rpm naming: x86_64/aarch64/armv7hl)" >&2; exit 1 ;;
  esac
fi

ROOT="$(mktemp -d)"; trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT"/{BUILD,RPMS,SPECS,SOURCES,SRPMS,BUILDROOT}
APPTREE="$ROOT/sub-browser-$VERSION"
mkdir -p "$APPTREE"
cp -a "$STAGING/." "$APPTREE/"
( cd "$ROOT" && tar -czf SOURCES/sub-browser-$VERSION.tar.gz sub-browser-$VERSION )

cat > "$ROOT/SPECS/sub-browser.spec" <<EOF
Name:           sub-browser
Version:        $VERSION
Release:        $RELEASE%{?dist}
Summary:        $SUMMARY
License:        GPL-3.0-only
URL:            https://github.com/subhobhai943/SUB-BROWSER
Source0:        sub-browser-%{version}.tar.gz
#BuildArch: noarch  # (omit for arch-specific binary packages)
$([ -n "$REQUIRES" ] && echo "Requires: $REQUIRES" || echo "#Requires: (self-contained Gecko build; edit RPM_REQUIRES to add runtime deps)")
AutoReqProv:    no

%description
SUB-BROWSER is a self-contained web browser built on the Gecko/servo engine.
This package ships the full runtime; it does not depend on webkit2gtk.

%prep
%setup -q -n sub-browser-%{version}

%install
rm -rf %{buildroot}
mkdir -p %{buildroot}/usr/bin %{buildroot}/usr/share
cp -a bin/. %{buildroot}/usr/bin/
cp -a share/. %{buildroot}/usr/share/
mkdir -p %{buildroot}/usr/share/applications
mkdir -p %{buildroot}/usr/share/icons
cat > %{buildroot}/usr/share/applications/sub-browser.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=SUB-BROWSER
GenericName=Web Browser
Exec=sub-browser %U
Icon=sub-browser
Terminal=false
Categories=Network;WebBrowser;
MimeType=x-scheme-handler/http;x-scheme-handler/https;text/html;
DESK

%files
%defattr(-,root,root,-)
/usr/bin/sub-browser
/usr/bin/servoshell
/usr/bin/sub-browser-smoke
/usr/share/sub-browser
/usr/share/applications/sub-browser.desktop
/usr/share/icons

%changelog
* $(date '+%a %b %d %Y') SUB-BROWSER Maintainers <packaging@sub-browser.example> - $VERSION-$RELEASE
- Release $VERSION
EOF

mkdir -p "$DIST_DIR"
if command -v rpmbuild >/dev/null 2>&1; then
  rpmbuild -bb --nodeps --target "$ARCH" \
    --define "_topdir $ROOT" \
    --define "_binary_payload w9.gzdio" \
    "$ROOT/SPECS/sub-browser.spec" >&2
  BUILT="$(find "$ROOT/RPMS" -name "sub-browser-*.rpm" | head -1)"
  [ -n "$BUILT" ] || { echo "ERROR: rpmbuild produced no rpm" >&2; exit 1; }
  cp "$BUILT" "$DIST_DIR/"
  OUT="$DIST_DIR/$(basename "$BUILT")"
elif command -v fpm >/dev/null 2>&1; then
  fpm -s dir -t rpm -n sub-browser -v "$VERSION" -a "$ARCH" \
      --license GPL-3.0 --url https://github.com/subhobhai943/SUB-BROWSER \
      -p "$DIST_DIR/sub-browser-$VERSION-1.$ARCH.rpm" \
      -C "$STAGING" bin=usr/bin share=usr/share
  OUT="$DIST_DIR/sub-browser-$VERSION-1.$ARCH.rpm"
else
  cat >&2 <<'EOF'
ERROR: neither rpmbuild nor fpm found.
Install one of:
  RHEL/Fedora:   sudo dnf install rpm-build        (or: yum install rpm-build)
  Debian/Ubuntu: sudo apt-get install rpm
  anywhere:      sudo gem install fpm
EOF
  exit 127
fi

echo "== built: $OUT"
command -v rpm >/dev/null && rpm -qpi "$OUT" 2>/dev/null | head -15 || true
command -v rpm >/dev/null && rpm -qlp "$OUT" 2>/dev/null | head -15 || true
sha256sum "$OUT"
