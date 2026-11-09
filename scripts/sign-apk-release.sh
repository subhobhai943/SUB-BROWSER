#!/usr/bin/env bash
# sign-apk-release.sh — production release signing for SUB-BROWSER APKs
# Creates (once) a real release keystore, zipaligns, signs with apksigner
# (v2/v3 schemes), verifies, and prints the signing-cert SHA-256.
# NEVER lose release.keystore: same key is mandatory for user upgrades.
set -euo pipefail

KEYSTORE="${SUB_KEYSTORE:-/opt/subpack/release.keystore}"
ALIAS="${SUB_KEY_ALIAS:-subbrowser}"
KEYPASS="${SUB_KEYSTORE_PASS:-}"     # REQUIRED env; same for store & key
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
VERSION="${VERSION:-2.0.0}"
usage() {
  cat <<EOF
Usage: $(basename "$0") [INPUT.apk | -o OUT.apk INPUT.apk]
  Signs INPUT.apk -> <name>-signed.apk (inplace only with --inplace)
Env:
  SUB_KEYSTORE_PASS   (required) keystore+key password
  SUB_KEYSTORE        keystore path (default /opt/subpack/release.keystore)
  SUB_KEY_ALIAS       key alias (default subbrowser)
  ANDROID_HOME        SDK root (default /opt/android-sdk)
Creates $KEYSTORE on first run: RSA 2048, 10000 days, CN=SUB-BROWSER Release.
EOF
}
IN=""; OUT=""; INPLACE=0
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0;;
    -o) OUT="$2"; shift 2;;
    --inplace) INPLACE=1; shift;;
    *) IN="$1"; shift;;
  esac
done
[ -n "$IN" ] || { usage >&2; exit 2; }
[ -f "$IN" ] || { echo "ERROR: no such APK: $IN" >&2; exit 1; }
[ -n "$KEYPASS" ] || { echo "ERROR: SUB_KEYSTORE_PASS env is required (refuse default password for release keys)" >&2; exit 2; }

# --- locate apksigner: newest build-tools ------------------------------------
APKSIGNER=""
if command -v apksigner >/dev/null 2>&1; then APKSIGNER=apksigner
elif [ -d "$ANDROID_SDK/build-tools" ]; then
  BT="$(ls -1 "$ANDROID_SDK/build-tools" | sort -V | tail -1)"
  APKSIGNER="$ANDROID_SDK/build-tools/$BT/apksigner"
  ZIPALIGN="$ANDROID_SDK/build-tools/$BT/zipalign"
fi
[ -x "$APKSIGNER" ] || { echo "ERROR: apksigner not found (checked \$PATH and $ANDROID_SDK/build-tools)" >&2; exit 127; }
echo "using: $APKSIGNER"

# --- keystore ----------------------------------------------------------------
if [ ! -f "$KEYSTORE" ]; then
  echo "generating release keystore: $KEYSTORE (alias=$ALIAS, RSA 2048, 10000 days)"
  umask 077
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -dname "CN=SUB-BROWSER Release, OU=Mobile, O=SUB-BROWSER Project, L=Unknown, ST=Unknown, C=US" \
    >/dev/null
  echo "keystore created: $KEYSTORE"
else
  echo "keystore exists: $KEYSTORE (reusing — required for upgrade compatibility)"
fi
keytool -list -keystore "$KEYSTORE" -storepass "$KEYPASS" -alias "$ALIAS" | sed 's/^/  /'

# --- output naming ------------------------------------------------------------
BASE="$(basename "$IN" .apk)"
if [ "$INPLACE" = 1 ]; then OUT="$IN"
else [ -n "$OUT" ] || OUT="$(dirname "$IN")/${BASE}-signed.apk"; fi

# --- zipalign then sign --------------------------------------------------------
if [ -n "${ZIPALIGN:-}" ] && [ -x "$ZIPALIGN" ] && [ "$OUT" != "$IN" ]; then
  "$ZIPALIGN" -f 4 "$IN" "$OUT"
  ALIGNED="$OUT"
else
  cp -f "$IN" "$OUT"; ALIGNED="$OUT"
  [ -x "${ZIPALIGN:-}" ] && "$ZIPALIGN" -f 4 "$OUT" "$OUT.tmp" && mv "$OUT.tmp" "$OUT" || echo "WARN: zipalign skipped"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 21 \
  "$ALIGNED"

# --- verify + fingerprint -------------------------------------------------------
echo "-- verify --"
"$APKSIGNER" verify --verbose --print-certs "$ALIGNED" | sed 's/^/  /'

CERT_SHA256="$("$APKSIGNER" verify --print-certs "$ALIGNED" | grep -im1 'SHA-256 digest' | awk '{print $NF}')"
echo "== signed: $ALIGNED"
echo "== signer cert SHA-256: $CERT_SHA256"
echo "== apk sha256: $(sha256sum "$ALIGNED" | cut -d' ' -f1)"
echo "Keep $KEYSTORE in offline secure backup. Play/App Store upload needs this same key."
