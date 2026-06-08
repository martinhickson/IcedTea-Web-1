#!/usr/bin/env bash
set -euo pipefail

FINAL_NAME="${1:?final name required}"
UBER_CLASSIFIER="${2:?uber classifier required}"
MAIN_CLASS="${3:?main class required}"

OUT="target/bin/javaws"
UBER_JAR="target/${FINAL_NAME}-${UBER_CLASSIFIER}.jar"
BYTEBUDDY_AGENT_JAR="target/bin/byte-buddy-agent.jar"

mkdir -p target/bin
sed \
  -e "s|@ITW_UBER_JAR@|${UBER_JAR}|g" \
  -e "s|@BYTEBUDDY_AGENT_JAR@|${BYTEBUDDY_AGENT_JAR}|g" \
  -e "s|@MAIN_CLASS@|${MAIN_CLASS}|g" \
  ../scripts/javaws.sh > "$OUT"
chmod ugo+rx "$OUT"
