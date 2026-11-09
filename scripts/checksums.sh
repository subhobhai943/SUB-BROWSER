#!/usr/bin/env bash
# checksums.sh — SHA256SUMS for a dist directory (SUB-BROWSER releases)
set -euo pipefail
usage() {
  cat <<EOF
Usage: $(basename "$0") [DIST_DIR]
  DIST_DIR  directory of release artifacts (default: \$DIST_DIR or ./dist)
Writes SHA256SUMS (relative paths, sha256sum -c compatible) inside DIST_DIR
and also SHA256SUMS.asc note if gpg present + GPG_KEY set.
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi
DIR="${1:-${DIST_DIR:-./dist}}"
[ -d "$DIR" ] || { echo "ERROR: no such dir: $DIR" >&2; exit 1; }
OUT="$DIR/SHA256SUMS"
( cd "$DIR" && find . -maxdepth 1 -type f \
    ! -name 'SHA256SUMS' ! -name '*.asc' -printf '%P\n' \
  | LC_ALL=C sort | xargs -r sha256sum > SHA256SUMS )
echo "== wrote: $OUT"
cat "$OUT"
if command -v gpg >/dev/null 2>&1 && [ -n "${GPG_KEY:-}" ]; then
  gpg --local-user "$GPG_KEY" --armor --detach-sign --output "$OUT.asc" "$OUT"
  echo "== signed: $OUT.asc"
fi
