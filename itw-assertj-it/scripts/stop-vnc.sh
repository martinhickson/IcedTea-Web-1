#!/bin/sh
DISPLAY="${1:-:99}"
NUM="${DISPLAY#:}"
LOCK="/tmp/.X${NUM}-lock"
SOCKET="/tmp/.X11-unix/X${NUM}"

if [ -f target/vnc.pid ]; then
  kill "$(cat target/vnc.pid)" 2>/dev/null || true
  rm -f target/vnc.pid
fi

pkill -f "Xvnc ${DISPLAY}" 2>/dev/null || true
rm -f "$LOCK" "$SOCKET"
