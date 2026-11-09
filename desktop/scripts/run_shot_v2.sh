#!/bin/bash
# Launch SUB-BROWSER v2 under Xvfb :99 and screenshot it.
set -x
BIN=/opt/sub-browser-desktop/target/release/sub-browser
SHOTDIR=/root/.openclaw/workspaces/byte/v2_shots
LOG=/tmp/sub_browser_v2.log
URL="${1:-https://example.com}"
mkdir -p "$SHOTDIR"

pgrep -x Xvfb >/dev/null || (Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &)
sleep 1

export DISPLAY=:99
export WEBKIT_DISABLE_COMPOSITING_MODE=1
export WEBKIT_DISABLE_DMABUF_RENDERER=1
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export NO_AT_BRIDGE=1
export GDK_BACKEND=x11

rm -f "$LOG"
"$BIN" "$URL" >"$LOG" 2>&1 &
APP=$!
echo "APP_PID=$APP"

for t in 12 20 30; do
  sleep ${t}x 2>/dev/null || sleep 8
  import -window root "$SHOTDIR/shot_${t}.png" 2>>"$LOG"
done
if kill -0 $APP 2>/dev/null; then echo "ALIVE_OK pid=$APP"; else echo "PROCESS DIED"; cat "$LOG"; fi
identify "$SHOTDIR"/shot_*.png
echo "KEEP_PID=$APP"
