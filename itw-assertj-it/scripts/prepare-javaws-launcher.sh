#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ITW_ASSERTJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ITW_ASSERTJ_DIR/.." && pwd)"
BIN_DIR="$ITW_ASSERTJ_DIR/target/bin"
ITW_VERSION="${ITW_VERSION:-$(sed -n 's:.*<icedtea-web.version>\(.*\)</icedtea-web.version>.*:\1:p' "$ITW_ASSERTJ_DIR/pom.xml")}"
UBER_JAR="${UBER_JAR:-$HOME/.m2/repository/net/sourceforge/icedtea-web/icedtea-web/${ITW_VERSION}/icedtea-web-${ITW_VERSION}-uber.jar}"

mkdir -p "$BIN_DIR"
cp "$REPO_ROOT/icedtea-web/target/bin/byte-buddy-agent.jar" "$BIN_DIR/byte-buddy-agent.jar"
sed \
  -e "s|@ITW_UBER_JAR@|$UBER_JAR|g" \
  -e "s|@BYTEBUDDY_AGENT_JAR@|$BIN_DIR/byte-buddy-agent.jar|g" \
  -e "s|@MAIN_CLASS@|net.sourceforge.jnlp.runtime.JavawsUberLauncher|g" \
  "$REPO_ROOT/scripts/javaws.sh" > "$BIN_DIR/javaws"
chmod ugo+rx "$BIN_DIR/javaws"
echo "$BIN_DIR/javaws"
