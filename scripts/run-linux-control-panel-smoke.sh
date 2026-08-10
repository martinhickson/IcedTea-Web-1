#!/usr/bin/env bash
#
# Smoke test the IcedTea-Web control panel from a built Linux distribution tree.
# Launches icedtea-web-settings under xvfb and fails if JVM panel construction throws.

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
    echo "Using icedtea-web-settings from ITW_SETTINGS_BIN: $SETTINGS_BIN"
    return
  fi

  if [ -n "$DIST_DIR" ]; then
    SETTINGS_BIN="$DIST_DIR/bin/icedtea-web-settings"
    if [ ! -x "$SETTINGS_BIN" ]; then
      echo "ITW_DOTNET_DIST_DIR is set but bin/icedtea-web-settings is not executable: $SETTINGS_BIN" >&2
      exit 1
    fi
    echo "Using icedtea-web-settings from ITW_DOTNET_DIST_DIR: $SETTINGS_BIN"
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
  SETTINGS_BIN="$DIST_DIR/bin/icedtea-web-settings"
  if [ -f "$SETTINGS_BIN" ]; then
    chmod +x "$SETTINGS_BIN"
  fi
  if [ -d "$DIST_DIR/runtime/corretto" ]; then
    chmod -R u+rwX "$DIST_DIR/runtime/corretto"
    chmod -R ugo+rx "$DIST_DIR/runtime/corretto"/*/bin 2>/dev/null || true
  fi
  if [ ! -x "$SETTINGS_BIN" ]; then
    echo "Extracted artifact does not contain executable bin/icedtea-web-settings: $SETTINGS_BIN" >&2
    exit 1
  fi
  echo "Using icedtea-web-settings from distribution artifact: $SETTINGS_BIN"
}

write_blocking_launcher_config() {
  local config_root="$1"
  mkdir -p "$config_root/icedtea-web"
  cat >"$config_root/icedtea-web/deployment.properties" <<'EOF'
deployment.keepJavawsProcess=true
deployment.keepjavaPrelaunchProcess=true
EOF
}

assert_no_control_panel_crash() {
  local log_file="$1"
  if grep -q "Exception in thread" "$log_file"; then
    echo "Control panel smoke test failed; launcher log:" >&2
    cat "$log_file" >&2
    exit 1
  fi
  if grep -q "ArrayIndexOutOfBoundsException" "$log_file"; then
    echo "Control panel smoke test failed; launcher log:" >&2
    cat "$log_file" >&2
    exit 1
  fi
}

main() {
  if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "xvfb-run is required for the control panel smoke test" >&2
    exit 1
  fi

  prepare_distribution

  local log_file config_root
  log_file="$(mktemp "${TMPDIR:-/tmp}/itw-control-panel-smoke.XXXXXX.log")"
  config_root="$(mktemp -d "${TMPDIR:-/tmp}/itw-control-panel-config.XXXXXX")"
  write_blocking_launcher_config "$config_root"

  echo "Launching control panel smoke test for ${WAIT_SECONDS}s"
  echo "XDG_CONFIG_HOME: $config_root"
  XDG_CONFIG_HOME="$config_root" xvfb-run -a "$SETTINGS_BIN" >"$log_file" 2>&1 &
  SETTINGS_PID=$!

  local deadline=$((SECONDS + WAIT_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    assert_no_control_panel_crash "$log_file"
    if ! kill -0 "$SETTINGS_PID" 2>/dev/null; then
      assert_no_control_panel_crash "$log_file"
      echo "Control panel exited before smoke window elapsed"
      cat "$log_file"
      exit 1
    fi
    sleep 0.5
  done

  assert_no_control_panel_crash "$log_file"
  echo "Control panel smoke test passed"
}

main "$@"
