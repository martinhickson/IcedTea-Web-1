#!/usr/bin/env bash
# Fail fast when Pack200's intrinsic.properties is missing from a jar.
# PropMap.<clinit> throws "intrinsic.properties cannot be loaded" at runtime without it.
set -euo pipefail

RESOURCE_PATH="io/pack200/pack/intrinsic.properties"

usage() {
  echo "usage: $0 <jar-file> [label]" >&2
  exit 2
}

if [ "$#" -lt 1 ]; then
  usage
fi

jar_file="$1"
label="${2:-$(basename "$jar_file")}"

if [ ! -f "$jar_file" ]; then
  echo "ERROR [$label]: jar not found: $jar_file" >&2
  exit 1
fi

if jar tf "$jar_file" | grep -Fx "$RESOURCE_PATH" >/dev/null; then
  echo "OK [$label]: $RESOURCE_PATH present in $(basename "$jar_file")"
  exit 0
fi

echo "ERROR [$label]: $RESOURCE_PATH missing from $jar_file" >&2
echo "Pack200 will fail at runtime with: intrinsic.properties cannot be loaded" >&2
exit 1
