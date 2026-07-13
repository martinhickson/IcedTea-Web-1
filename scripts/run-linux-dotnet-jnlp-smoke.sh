#!/usr/bin/env bash
#
# Run a local Linux .NET-launcher JNLP smoke test.
#
# This consumes the normal IcedTea-Web Linux distribution artifact from
# icedtea-web-distribution/target, extracts its javaws launcher, serves a JNLP
# from a temporary local HTTP server, and streams javaws output.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${ITW_VERSION:-2.0.1-SNAPSHOT}"
DIST_ZIP="${ITW_DOTNET_LINUX_ZIP:-$ROOT_DIR/icedtea-web-distribution/target/icedtea-web-$VERSION-linux-x64.zip}"
DIST_DIR="${ITW_DOTNET_DIST_DIR:-}"
JAVAWS_BIN="${ITW_JAVAWS_BIN:-}"
APP_JAR="${ITW_HEADLESS_APP_JAR:-$ROOT_DIR/icedtea-web-integration/target/icedtea-web-integration-$VERSION-headless-app.jar}"
BUILD_TEST_APP="${ITW_SMOKE_BUILD_TEST_APP:-true}"
PORT="${ITW_SMOKE_PORT:-}"
TIMEOUT_SECONDS="${ITW_SMOKE_TIMEOUT_SECONDS:-180}"
KEEP_WORKDIR="${ITW_SMOKE_KEEP_WORKDIR:-false}"

WORK_DIR=""
WEB_ROOT=""
SERVER_PID=""
JAVAWS_PID=""
TAIL_PID=""

cleanup() {
  if [ -n "${TAIL_PID:-}" ] && kill -0 "$TAIL_PID" 2>/dev/null; then
    kill "$TAIL_PID" 2>/dev/null || true
  fi
  if [ -n "${JAVAWS_PID:-}" ] && kill -0 "$JAVAWS_PID" 2>/dev/null; then
    kill "$JAVAWS_PID" 2>/dev/null || true
  fi
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  if [ "$KEEP_WORKDIR" != "true" ] && [ -n "${WORK_DIR:-}" ] && [ -d "$WORK_DIR" ]; then
    rm -rf "$WORK_DIR"
  elif [ -n "${WORK_DIR:-}" ]; then
    echo "Kept smoke-test work dir: $WORK_DIR"
  fi
}
trap cleanup EXIT

prepare_dotnet_distribution() {
  if [ -n "$JAVAWS_BIN" ]; then
    if [ ! -x "$JAVAWS_BIN" ]; then
      echo "ITW_JAVAWS_BIN is set but not executable: $JAVAWS_BIN" >&2
      exit 1
    fi
    echo "Using javaws from ITW_JAVAWS_BIN: $JAVAWS_BIN"
    return
  fi

  if [ -n "$DIST_DIR" ]; then
    JAVAWS_BIN="$DIST_DIR/bin/javaws"
    if [ ! -x "$JAVAWS_BIN" ]; then
      echo "ITW_DOTNET_DIST_DIR is set but bin/javaws is not executable: $JAVAWS_BIN" >&2
      exit 1
    fi
    echo "Using javaws from ITW_DOTNET_DIST_DIR: $JAVAWS_BIN"
    return
  fi

  if [ ! -f "$DIST_ZIP" ]; then
    cat >&2 <<EOF
Linux .NET distribution artifact not found:
  $DIST_ZIP

Build it first with:
  export PATH="\$PWD/target/dotnet-sdk:\$PATH" # if using a locally installed SDK
  mvn -P maven-distribution -pl icedtea-web-distribution -am package \\
    -Dmaven.test.skip=true -DskipTests \\
    -Ditw.dotnet.runtime.identifier=linux-x64 \\
    -Ditw.dotnet.selfContained=true

Or point this script at an existing artifact with ITW_DOTNET_LINUX_ZIP,
ITW_DOTNET_DIST_DIR, or ITW_JAVAWS_BIN.
EOF
    exit 1
  fi

  local unpack_dir="$WORK_DIR/dotnet-dist"
  mkdir -p "$unpack_dir"
  echo "Extracting normal Linux .NET distribution artifact: $DIST_ZIP"
  unzip -q "$DIST_ZIP" -d "$unpack_dir"
  DIST_DIR="$(find "$unpack_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  JAVAWS_BIN="$DIST_DIR/bin/javaws"
  if [ -f "$JAVAWS_BIN" ]; then
    chmod +x "$JAVAWS_BIN"
  fi
  if [ -d "$DIST_DIR/runtime/corretto" ]; then
    chmod -R u+rwX "$DIST_DIR/runtime/corretto"
    chmod -R ugo+rx "$DIST_DIR/runtime/corretto"/*/bin 2>/dev/null || true
  fi
  if [ ! -x "$JAVAWS_BIN" ]; then
    echo "Extracted artifact does not contain executable bin/javaws: $JAVAWS_BIN" >&2
    exit 1
  fi
  echo "Using javaws from normal Linux .NET distribution artifact: $JAVAWS_BIN"
}

ensure_headless_app() {
  if [ -f "$APP_JAR" ]; then
    return
  fi
  if [ "$BUILD_TEST_APP" != "true" ]; then
    echo "Headless app jar not found: $APP_JAR" >&2
    exit 1
  fi
  echo "Building signed headless JNLP app from icedtea-web-integration"
  (cd "$ROOT_DIR" && mvn -pl icedtea-web-integration -am process-test-classes -DskipTests)
}

wait_for_server() {
  local url="$1"
  local deadline=$((SECONDS + 30))
  until curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Timed out waiting for local HTTP server at $url" >&2
      return 1
    fi
    sleep 0.5
  done
}

choose_port() {
  if [ -n "$PORT" ]; then
    return
  fi
  PORT="$(python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
PY
)"
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
  launch_log="$(find "$logs_dir" -maxdepth 1 -name 'itw-javantx-*.log' ! -name '*-prelaunch.log' ! -name '*.err.log' -type f 2>/dev/null | sort | tail -n 1)"
  if [ -n "$launch_log" ] && [ -f "$launch_log" ]; then
    local stderr_path="${launch_log%.log}.err.log"
    files+=("$launch_log")
    if [ -f "$stderr_path" ]; then
      files+=("$stderr_path")
    fi
  fi

  printf '%s\n' "${files[@]}"
}

log_contains_success() {
  local log_file="$1"
  [ -f "$log_file" ] && grep -q "ITW_INTEGRATION_SUCCESS" "$log_file" 2>/dev/null
}

wait_for_launch() {
  local marker="$1"
  local launcher_logs_dir="$2"
  shift 2
  local -a log_files=("$@")
  local deadline=$((SECONDS + TIMEOUT_SECONDS))

  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ -s "$marker" ]; then
      return 0
    fi
    mapfile -t log_files < <(collect_launch_log_files "$launcher_logs_dir" "${log_files[@]}")
    for log_file in "${log_files[@]}"; do
      if log_contains_success "$log_file"; then
        return 0
      fi
    done
    sleep 0.5
  done

  return 1
}

main() {
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/itw-dotnet-jnlp.XXXXXX")"
  prepare_dotnet_distribution
  ensure_headless_app

  if [ ! -f "$APP_JAR" ]; then
    echo "Headless app jar not found: $APP_JAR" >&2
    exit 1
  fi

  WEB_ROOT="$WORK_DIR/web"
  mkdir -p "$WEB_ROOT"
  cp "$APP_JAR" "$WEB_ROOT/headless-app.jar"

  choose_port
  local marker="$WORK_DIR/success.marker"
  local javaws_log="$WORK_DIR/javaws.log"
  local server_log="$WORK_DIR/server.log"
  local codebase="http://127.0.0.1:$PORT/"
  local jnlp_url="${codebase}headless-test.jnlp"

  cat > "$WEB_ROOT/headless-test.jnlp" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="$codebase" href="headless-test.jnlp">
  <information>
    <title>ITW .NET launcher smoke test</title>
    <vendor>IcedTea-Web</vendor>
  </information>
  <security><all-permissions/></security>
  <resources>
    <property name="itw.test.success.marker" value="$marker"/>
    <j2se version="1.8+"/>
    <jar href="headless-app.jar" main="true"/>
  </resources>
  <application-desc main-class="net.sourceforge.jnlp.integration.HeadlessJnlpMain"/>
</jnlp>
EOF

  echo "Serving JNLP from $WEB_ROOT"
  (cd "$WEB_ROOT" && python3 -m http.server "$PORT" --bind 127.0.0.1 > "$server_log" 2>&1) &
  SERVER_PID=$!
  wait_for_server "$jnlp_url"

  local xdg_config="$WORK_DIR/xdg-config"
  mkdir -p "$xdg_config/icedtea-web/log"

  echo "Launching with .NET javaws: $JAVAWS_BIN"
  echo "JNLP URL: $jnlp_url"
  echo "Marker: $marker"
  echo "javaws log: $javaws_log"
  echo "XDG_CONFIG_HOME: $xdg_config"
  XDG_CONFIG_HOME="$xdg_config" "$JAVAWS_BIN" -headless -verbose -Xtrustall --auto-accept-https-certificate=true -Xnofork "$jnlp_url" > "$javaws_log" 2>&1 &
  JAVAWS_PID=$!

  tail -f "$javaws_log" &
  TAIL_PID=$!

  local launcher_logs_dir="$xdg_config/icedtea-web/log"

  if wait_for_launch "$marker" "$launcher_logs_dir" "$javaws_log"; then
    echo
    echo "JNLP launch succeeded."
    if [ -s "$marker" ]; then
      echo "Marker contents:"
      cat "$marker"
      echo
    fi
  else
    echo
    echo "JNLP launch did not report success within ${TIMEOUT_SECONDS}s." >&2
    echo "Full javaws log: $javaws_log" >&2
    echo "Server log: $server_log" >&2
    exit 1
  fi
}

main "$@"
