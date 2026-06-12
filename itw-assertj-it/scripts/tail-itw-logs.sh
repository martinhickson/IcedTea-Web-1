#!/usr/bin/env bash
# Tail IcedTea-Web file logs for an isolated test home (or the current XDG_CONFIG_HOME).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ITW_ASSERTJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="${1:-${ITW_TEST_HOME:-$ITW_ASSERTJ_DIR/target/itw-test-home}}"

if [[ -n "${XDG_CONFIG_HOME:-}" && "$ITW_TEST_HOME" == "${ITW_ASSERTJ_DIR}/target/itw-test-home" ]]; then
  LOG_DIR="${XDG_CONFIG_HOME}/icedtea-web/log"
else
  LOG_DIR="$ITW_TEST_HOME/.config/icedtea-web/log"
fi

if [[ ! -d "$LOG_DIR" ]]; then
  echo "Log directory not found: $LOG_DIR" >&2
  echo "Enable file logging with deployment.log=true and deployment.log.file=true." >&2
  exit 1
fi

shopt -s nullglob
logs=("$LOG_DIR"/itw-*.log)
if [[ ${#logs[@]} -eq 0 ]]; then
  echo "No itw-*.log files in $LOG_DIR yet." >&2
  echo "Launch javaws with -verbose and deployment.log.file=true, then retry." >&2
  exit 1
fi

echo "Tailing ${#logs[@]} log file(s) under $LOG_DIR"
exec tail -n 50 -F "${logs[@]}"
