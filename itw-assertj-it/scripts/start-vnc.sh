#!/bin/sh
set -e
DISPLAY="${1:-:99}"
NUM="${DISPLAY#:}"
LOCK="/tmp/.X${NUM}-lock"
SOCKET="/tmp/.X11-unix/X${NUM}"
XVNC="${XVNC:-/usr/bin/Xvnc}"

if [ ! -x "$XVNC" ]; then
  echo "TigerVNC Xvnc not found at $XVNC (install tigervnc-standalone-server)" >&2
  exit 1
fi

mkdir -p target
if [ -f "$LOCK" ]; then
  rm -f "$LOCK"
fi
if [ -e "$SOCKET" ]; then
  rm -f "$SOCKET"
fi

"$XVNC" "$DISPLAY" \
  -geometry 1920x1080 \
  -depth 24 \
  -SecurityTypes None \
  -localhost=1 \
  -noreset \
  >/dev/null 2>&1 &
echo $! > target/vnc.pid

if command -v xdpyinfo >/dev/null 2>&1; then
  i=0
  while [ "$i" -lt 50 ]; do
    if xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
      exit 0
    fi
    i=$((i + 1))
    sleep 0.1
  done
  echo "TigerVNC display $DISPLAY did not become ready" >&2
  exit 1
fi

sleep 1
