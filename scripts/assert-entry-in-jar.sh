#!/usr/bin/env bash
# Fail if a required entry path is missing from a jar (e.g. shaded uber contents).
set -euo pipefail

JAR_FILE="${1:?jar path required}"
ENTRY_PATH="${2:?entry path required}"
LABEL="${3:-$(basename "$JAR_FILE")}"

if [[ ! -f "$JAR_FILE" ]]; then
  echo "ERROR [$LABEL]: jar not found: $JAR_FILE" >&2
  exit 1
fi

JAR_EXE=jar
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  JAR_EXE="$JAVA_HOME/bin/jar"
fi

if "$JAR_EXE" tf "$JAR_FILE" | grep -qxF "$ENTRY_PATH"; then
  echo "OK [$LABEL]: $ENTRY_PATH present in $(basename "$JAR_FILE")"
  exit 0
fi

echo "ERROR [$LABEL]: $ENTRY_PATH missing from $JAR_FILE" >&2
exit 1
