#!/usr/bin/env bash
# Ensure Apache Groovy 5.0.8 is available under tools/groovy-5.0.8 (gitignored).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GROOVY_VER="${ITW_GROOVY_VERSION:-5.0.8}"
DEST="$ROOT_DIR/tools/groovy-${GROOVY_VER}"

if [[ -x "$DEST/bin/groovy" ]]; then
  echo "$DEST"
  exit 0
fi

mkdir -p "$ROOT_DIR/tools"
ZIP="${TMPDIR:-/tmp}/apache-groovy-binary-${GROOVY_VER}.zip"
URL="https://downloads.apache.org/groovy/${GROOVY_VER}/distribution/apache-groovy-binary-${GROOVY_VER}.zip"
ALT="https://archive.apache.org/dist/groovy/${GROOVY_VER}/distribution/apache-groovy-binary-${GROOVY_VER}.zip"

echo "Downloading Apache Groovy ${GROOVY_VER}..."
curl -fsSL -o "$ZIP" "$URL" || curl -fsSL -o "$ZIP" "$ALT"

EXTRACT="$ROOT_DIR/tools/.groovy-${GROOVY_VER}-extract"
rm -rf "$EXTRACT" "$DEST"
mkdir -p "$EXTRACT"
unzip -q "$ZIP" -d "$EXTRACT"
mv "$EXTRACT/groovy-${GROOVY_VER}" "$DEST"
rm -rf "$EXTRACT"
chmod +x "$DEST/bin/"* 2>/dev/null || true

echo "$DEST"
