#!/usr/bin/env bash
#
# Smoke test the IcedTea-Web control panel from a built Linux distribution tree.
# Launches itweb-settings under xvfb and fails if JVM panel construction throws.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${ITW_VERSION:-2.0.1-SNAPSHOT}"
DIST_ZIP="${ITW_DOTNET_LINUX_ZIP:-$ROOT_DIR/icedtea-web-distribution/target/icedtea-web-$VERSION-linux-x64.zip}"
DIST_DIR="${ITW_DOTNET_DIST_DIR:-}"
SETTINGS_BIN="${ITW_SETTINGS_BIN:-}"
WAIT_SECONDS="${ITW_CONTROL_PANEL_SMOKE_WAIT_SECONDS:-8}"

WORK_DIR=""
SETTINGS_PID=""

cleanup() {
  if [ -n "${SETTINGS_PID:-}" ] && kill -0 "$SETTINGS_PID" 2>/dev/null; then
    kill "$SETTINGS_PID" 2>/dev/null || true
    wait "$SETTINGS_PID" 2>/dev/null || true
  fi
  if [ -n "${WORK_DIR:-}" ] && [ -d "$WORK_DIR" ]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

prepare_distribution() {
  if [ -n "$SETTINGS_BIN" ]; then
    if [ ! -x "$SETTINGS_BIN" ]; then
      echo "ITW_SETTINGS_BIN is set but not executable: $SETTINGS_BIN" >&2
      exit 1
    fi
    echo "Using itweb-settings from ITW_SETTINGS_BIN: $SETTINGS_BIN"
    return
  fi

  if [ -n "$DIST_DIR" ]; then
    SETTINGS_BIN="$DIST_DIR/bin/itweb-settings"
    if [ ! -x "$SETTINGS_BIN" ]; then
      echo "ITW_DOTNET_DIST_DIR is set but bin/itweb-settings is not executable: $SETTINGS_BIN" >&2
      exit 1
    fi
    echo "Using itweb-settings from ITW_DOTNET_DIST_DIR: $SETTINGS_BIN"
    return
  fi

  if [ ! -f "$DIST_ZIP" ]; then
    cat >&2 <<EOF
Linux .NET distribution artifact not found:
  $DIST_ZIP

Build it first, or point this script at an existing artifact with
ITW_DOTNET_LINUX_ZIP, ITW_DOTNET_DIST_DIR, or ITW_SETTINGS_BIN.
EOF
    exit 1
  fi

  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/itw-control-panel-smoke.XXXXXX")"
  local unpack_dir="$WORK_DIR/dotnet-dist"
  mkdir -p "$unpack_dir"
  echo "Extracting Linux distribution artifact: $DIST_ZIP"
  unzip -q "$DIST_ZIP" -d "$unpack_dir"
  DIST_DIR="$(find "$unpack_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  SETTINGS_BIN="$DIST_DIR/bin/itweb-settings"
  if [ -f "$SETTINGS_BIN" ]; then
    chmod +x "$SETTINGS_BIN"
  fi
  if [ -d "$DIST_DIR/runtime/corretto" ]; then
    chmod -R u+rwX "$DIST_DIR/runtime/corretto"
    chmod -R ugo+rx "$DIST_DIR/runtime/corretto"/*/bin 2>/dev/null || true
  fi
  if [ ! -x "$SETTINGS_BIN" ]; then
    echo "Extracted artifact does not contain executable bin/itweb-settings: $SETTINGS_BIN" >&2
    exit 1
  fi
  echo "Using itweb-settings from distribution artifact: $SETTINGS_BIN"
}

collect_launch_log_files() {
  local logs_dir="$1"
  shift
  local -a files=("$@")
  if [ ! -d "$logs_dir" ]; then
    printf '%s\n' "${files[@]}"
    return
  fi

  local launch_log
  launch_log="$(find "$logs_dir" -maxdepth 1 -name '*.launch.log' ! -name '*-prelaunch.launch.log' -type f 2>/dev/null | sort | tail -n 1)"
  if [ -n "$launch_log" ] && [ -f "$launch_log" ]; then
    local stdout_path stderr_path child_pid
    stdout_path="$(sed -n 's/^Standard Output stream written to: //p' "$launch_log" | tail -n 1)"
    stderr_path="$(sed -n 's/^Standard Error stream written to: //p' "$launch_log" | tail -n 1)"
    child_pid="$(sed -n 's/^Launched JDK process ID: //p' "$launch_log" | tail -n 1)"
    if [ -n "$stdout_path" ] && [ -f "$stdout_path" ]; then
      files+=("$stdout_path")
    fi
    if [ -n "$stderr_path" ] && [ -f "$stderr_path" ]; then
      files+=("$stderr_path")
    fi
    files+=("$launch_log")
    if [ -n "$child_pid" ]; then
      printf '%s\n' "${files[@]}"
      printf 'CHILD_PID=%s\n' "$child_pid"
      return
    fi
  fi

  printf '%s\n' "${files[@]}"
}

assert_no_control_panel_crash() {
  local log_file
  for log_file in "$@"; do
    [ -f "$log_file" ] || continue
    if grep -q "Exception in thread" "$log_file"; then
      echo "Control panel smoke test failed; log: $log_file" >&2
      cat "$log_file" >&2
      exit 1
    fi
    if grep -q "ArrayIndexOutOfBoundsException" "$log_file"; then
      echo "Control panel smoke test failed; log: $log_file" >&2
      cat "$log_file" >&2
      exit 1
    fi
  done
}

main() {
  if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "xvfb-run is required for the control panel smoke test" >&2
    exit 1
  fi

  prepare_distribution

  local log_file xdg_data launcher_logs_dir
  log_file="$(mktemp "${TMPDIR:-/tmp}/itw-control-panel-smoke.XXXXXX.log")"
  xdg_data="$(mktemp -d "${TMPDIR:-/tmp}/itw-control-panel-xdg.XXXXXX")"
  launcher_logs_dir="$xdg_data/IcedTea-Web/logs"

  echo "Launching control panel smoke test for ${WAIT_SECONDS}s"
  echo "XDG_DATA_HOME: $xdg_data"
  XDG_DATA_HOME="$xdg_data" xvfb-run -a "$SETTINGS_BIN" >"$log_file" 2>&1 &
  SETTINGS_PID=$!

  local child_pid=""
  local deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if ! kill -0 "$SETTINGS_PID" 2>/dev/null; then
      break
    fi
    sleep 0.25
  done

  local collected child_line
  collected="$(collect_launch_log_files "$launcher_logs_dir" "$log_file")"
  child_line="$(printf '%s\n' "$collected" | sed -n 's/^CHILD_PID=//p' | tail -n 1)"
  mapfile -t watch_logs < <(printf '%s\n' "$collected" | grep -v '^CHILD_PID=')
  if [ -n "$child_line" ]; then
    child_pid="$child_line"
  fi

  if [ -z "$child_pid" ]; then
    echo "Control panel smoke test failed; no JDK child PID in launch log" >&2
    printf '%s\n' "${watch_logs[@]}" >&2
    for log_file in "${watch_logs[@]}"; do
      [ -f "$log_file" ] && cat "$log_file" >&2
    done
    exit 1
  fi

  echo "Monitoring detached JDK control panel PID: $child_pid"
  deadline=$((SECONDS + WAIT_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    mapfile -t watch_logs < <(collect_launch_log_files "$launcher_logs_dir" "$log_file" | grep -v '^CHILD_PID=')
    assert_no_control_panel_crash "${watch_logs[@]}"
    if ! kill -0 "$child_pid" 2>/dev/null; then
      assert_no_control_panel_crash "${watch_logs[@]}"
      echo "Control panel JDK child exited before smoke window elapsed"
      for log_file in "${watch_logs[@]}"; do
        [ -f "$log_file" ] && cat "$log_file"
      done
      exit 1
    fi
    sleep 0.5
  done

  mapfile -t watch_logs < <(collect_launch_log_files "$launcher_logs_dir" "$log_file" | grep -v '^CHILD_PID=')
  assert_no_control_panel_crash "${watch_logs[@]}"
  echo "Control panel smoke test passed"
}

main "$@"
