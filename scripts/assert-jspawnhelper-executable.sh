#!/usr/bin/env bash
# Fail if any bundled Temurin jspawnhelper is missing or not executable.
# Accepts a distribution root (…/runtime/temurin-*) or a package payload
# that contains opt/icedtea-web/runtime.
set -euo pipefail

ROOT="${1:?usage: assert-jspawnhelper-executable.sh <dist-or-payload-root>}"

if [[ ! -d "$ROOT" ]]; then
  echo "ERROR: not a directory: $ROOT" >&2
  exit 1
fi

search_root=""
if [[ -d "$ROOT/runtime" ]]; then
  search_root="$ROOT/runtime"
elif [[ -d "$ROOT/opt/icedtea-web/runtime" ]]; then
  search_root="$ROOT/opt/icedtea-web/runtime"
else
  echo "ERROR: no runtime/ tree under $ROOT" >&2
  exit 1
fi

shopt -s nullglob
temurin_dirs=("$search_root"/temurin-*)
if (( ${#temurin_dirs[@]} == 0 )); then
  echo "ERROR: no temurin-* runtimes under $search_root" >&2
  exit 1
fi

file_mode() {
  local path="$1"
  if stat -c '%a' "$path" >/dev/null 2>&1; then
    stat -c '%a' "$path"
  else
    stat -f '%OLp' "$path"
  fi
}

failed=0
found=0
for runtime_dir in "${temurin_dirs[@]}"; do
  helpers=()
  while IFS= read -r -d '' helper; do
    helpers+=("$helper")
  done < <(find "$runtime_dir" -type f -name jspawnhelper -print0 | sort -z)

  if (( ${#helpers[@]} == 0 )); then
    echo "ERROR: no jspawnhelper under $runtime_dir" >&2
    failed=1
    continue
  fi

  for helper in "${helpers[@]}"; do
    found=$((found + 1))
    mode="$(file_mode "$helper")"
    if [[ ! -x "$helper" ]]; then
      echo "ERROR: $helper is not executable (mode $mode)" >&2
      failed=1
    else
      echo "OK: $helper mode $mode"
    fi
  done
done

if (( found == 0 )); then
  echo "ERROR: no jspawnhelper found under $search_root" >&2
  exit 1
fi

exit "$failed"
