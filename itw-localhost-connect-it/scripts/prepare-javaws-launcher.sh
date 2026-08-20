#!/usr/bin/env bash
# Filter the repo javaws.sh wrapper for Failsafe ProcessBuilder launches.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="${1:-$ROOT/target/bin}"
UBER_JAR="${2:?uber jar path required}"
MAIN_CLASS="${3:-net.sourceforge.jnlp.runtime.JavawsUberLauncher}"
BB_JAR="${BIN_DIR}/byte-buddy-agent.jar"
REACTOR_UBER="$ROOT/../icedtea-web/target/icedtea-web-2.0.1-SNAPSHOT-uber.jar"
if [ -f "$REACTOR_UBER" ]; then
  UBER_JAR="$REACTOR_UBER"
  echo "Using reactor uber: $UBER_JAR"
fi

mkdir -p "$BIN_DIR"
if [ ! -f "$BB_JAR" ]; then
  echo "byte-buddy-agent.jar missing: $BB_JAR" >&2
  exit 1
fi
if [ ! -f "$UBER_JAR" ]; then
  echo "uber jar missing: $UBER_JAR" >&2
  exit 1
fi

sed -e "s|@ITW_UBER_JAR@|${UBER_JAR}|g" \
    -e "s|@BYTEBUDDY_AGENT_JAR@|${BB_JAR}|g" \
    -e "s|@MAIN_CLASS@|${MAIN_CLASS}|g" \
    "$ROOT/../scripts/javaws.sh" > "$BIN_DIR/javaws"
chmod +x "$BIN_DIR/javaws"
echo "Prepared $BIN_DIR/javaws"
