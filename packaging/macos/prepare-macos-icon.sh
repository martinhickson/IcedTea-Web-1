#!/usr/bin/env bash
# Build icedtea-web.icns from packaging/icons PNGs (requires macOS iconutil).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ICON_SRC_DIR="${ITW_ICON_SRC_DIR:-$ROOT_DIR/packaging/icons}"
OUTPUT_ICNS="${ITW_ICON_ICNS_OUTPUT:-$ICON_SRC_DIR/icedtea-web.icns}"
ICONSET_DIR="${ITW_ICON_ICONSET_DIR:-$ICON_SRC_DIR/icedtea-web.iconset}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "prepare-macos-icon.sh: skipping icns build (not macOS)." >&2
  exit 0
fi

if ! command -v iconutil >/dev/null 2>&1; then
  echo "iconutil not found; cannot build icedtea-web.icns" >&2
  exit 1
fi

for size in 16 32 48 64 128 256; do
  if [[ ! -f "$ICON_SRC_DIR/icedtea-web-${size}.png" ]]; then
    echo "Missing icon source: $ICON_SRC_DIR/icedtea-web-${size}.png" >&2
    exit 1
  fi
done

rm -rf "$ICONSET_DIR"
mkdir -p "$ICONSET_DIR"

cp "$ICON_SRC_DIR/icedtea-web-16.png" "$ICONSET_DIR/icon_16x16.png"
cp "$ICON_SRC_DIR/icedtea-web-32.png" "$ICONSET_DIR/icon_16x16@2x.png"
cp "$ICON_SRC_DIR/icedtea-web-32.png" "$ICONSET_DIR/icon_32x32.png"
cp "$ICON_SRC_DIR/icedtea-web-64.png" "$ICONSET_DIR/icon_32x32@2x.png"
cp "$ICON_SRC_DIR/icedtea-web-128.png" "$ICONSET_DIR/icon_128x128.png"
cp "$ICON_SRC_DIR/icedtea-web-256.png" "$ICONSET_DIR/icon_128x128@2x.png"
cp "$ICON_SRC_DIR/icedtea-web-256.png" "$ICONSET_DIR/icon_256x256.png"
cp "$ICON_SRC_DIR/icedtea-web-256.png" "$ICONSET_DIR/icon_256x256@2x.png"
cp "$ICON_SRC_DIR/icedtea-web-256.png" "$ICONSET_DIR/icon_512x512.png"
cp "$ICON_SRC_DIR/icedtea-web-256.png" "$ICONSET_DIR/icon_512x512@2x.png"

iconutil -c icns "$ICONSET_DIR" -o "$OUTPUT_ICNS"
echo "Built macOS icon: $OUTPUT_ICNS"
