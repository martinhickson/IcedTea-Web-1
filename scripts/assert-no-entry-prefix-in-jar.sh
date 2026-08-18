#!/usr/bin/env bash
# Fail if any jar entry starts with the given prefix (e.g. unshaded org/slf4j/).
set -euo pipefail

JAR_FILE="${1:?jar path required}"
ENTRY_PREFIX="${2:?entry prefix required}"
LABEL="${3:-$(basename "$JAR_FILE")}"

if [[ ! -f "$JAR_FILE" ]]; then
  echo "ERROR [$LABEL]: jar not found: $JAR_FILE" >&2
  exit 1
fi

JAR_EXE=jar
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  JAR_EXE="$JAVA_HOME/bin/jar"
fi

hits="$("$JAR_EXE" tf "$JAR_FILE" | grep -E "^${ENTRY_PREFIX}" || true)"
if [[ -z "$hits" ]]; then
  echo "OK [$LABEL]: no ${ENTRY_PREFIX}* in $(basename "$JAR_FILE")"
  exit 0
fi

echo "ERROR [$LABEL]: unexpected ${ENTRY_PREFIX}* in $JAR_FILE" >&2
echo "$hits" | head -20 >&2
exit 1
