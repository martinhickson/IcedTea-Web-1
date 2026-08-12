#!/usr/bin/env bash
# Smoke-test a packaged IcedTea-Web distribution (linux / macos / windows).
# Verifies end-to-end on the REAL packaged artifact (zip tree, deb/MSI install, etc.):
#   1. .NET launcher resolves the bundled Temurin 21 as the download JVM
#   2. JNLP + jar download over HTTP (Apache client)
#   3. App launches on the bundled JVM (success marker)
#   4. KnownJvmStore seeds bundled JREs in order 21 -> 17 -> 11 -> 25
#
# Portable across Git Bash (Windows) and macOS/Linux runners:
#   - no GNU timeout/coreutils (background sleep guard instead)
#   - marker is first polled from the ITW javantx logs (Windows captures child
#     output there); on Linux/macOS a fallback re-runs the child command parsed
#     from the handoff record.
#
# Usage: smoke-packaged-javaws.sh <distribution-root> [http-port] [expected-download-jvm-major]
# Exit 0 on full success, non-zero otherwise.
set -euo pipefail

DIST="${1:?distribution root required}"
PORT="${2:-8123}"
EXPECTED_MAJOR="${3:-21}"

IS_WINDOWS=0
case "$(uname -s 2>/dev/null || echo MSYS)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;;
esac
if [ "$IS_WINDOWS" = 1 ]; then
  DIST="${DIST//\\//}"   # Git Bash needs forward slashes for path tests/exec
fi

if [ ! -x "$DIST/bin/javaws" ] && [ ! -x "$DIST/bin/javaws.exe" ]; then
  echo "FATAL: no executable launcher at $DIST/bin/javaws(.exe)" >&2
  exit 1
fi
LAUNCHER="$DIST/bin/javaws$([ "$IS_WINDOWS" = 1 ] && echo .exe || true)"

PY=""   # python is banned in this environment; the sample server is Groovy
GROOVY="${SMOKE_GROOVY:-groovy}"
groovy_ok=0
if command -v "$GROOVY" >/dev/null 2>&1; then
  groovy_ok=1
elif command -v "${GROOVY}.bat" >/dev/null 2>&1; then
  groovy_ok=1
elif [ "$IS_WINDOWS" = 1 ] && cmd /c "groovy --version" >/dev/null 2>&1; then
  groovy_ok=1
fi
if [ "$groovy_ok" != 1 ]; then
  echo "FATAL: groovy not found (SMOKE_GROOVY=${GROOVY})" >&2
  exit 1
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVE_SCRIPT="$SCRIPT_DIR/smoke-http-server.groovy"
[ -f "$SERVE_SCRIPT" ] || { echo "FATAL: $SERVE_SCRIPT missing" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'kill "${SRV:-}" 2>/dev/null || true' EXIT

# Run a command in the background and hard-kill it after `cap` seconds.
run_capped() {
  local cap="$1"
  shift
  ( "$@" ) > "$WORK/last-run.out" 2>&1 &
  local pid=$!
  ( sleep "$cap"; kill -9 "$pid" 2>/dev/null ) &
  local guard=$!
  wait "$pid" || true
  kill "$guard" 2>/dev/null || true
}

fail() { echo "SMOKE-FAIL: $*" >&2; exit 1; }

# --- build a tiny sandboxed smoke app (no permissions requested -> unsigned is fine) ---
mkdir -p "$WORK/out"
cat > "$WORK/SmokeMain.java" <<'EOF'
public class SmokeMain {
  public static void main(String[] a) {
    System.out.println("ITW_SMOKE_SUCCESS app-launched-on-bundled-jvm");
  }
}
EOF
javac -d "$WORK" "$WORK/SmokeMain.java"
printf 'Manifest-Version: 1.0\nMain-Class: SmokeMain\nApplication-Name: ITW Smoke\n\n' > "$WORK/out/MANIFEST.MF"
jar cfm "$WORK/app.jar" "$WORK/out/MANIFEST.MF" -C "$WORK" SmokeMain.class
cat > "$WORK/app.jnlp" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="." href="app.jnlp">
  <information><title>ITW Smoke</title><vendor>IcedTea-Web</vendor></information>
  <resources><j2se version="1.8+"/><jar href="app.jar" main="true"/></resources>
  <application-desc main-class="SmokeMain"/>
</jnlp>
EOF

# --- serve the sample (Groovy server; python is banned) ---
if [ "$IS_WINDOWS" = 1 ]; then
  (cd "$WORK" && cmd /c "groovy \"$SERVE_SCRIPT\" $PORT \"$WORK\"" >/dev/null 2>&1) &
else
  (cd "$WORK" && "$GROOVY" "$SERVE_SCRIPT" "$PORT" "$WORK" >/dev/null 2>&1) &
fi
SRV=$!
# wait for the server to accept (curl is present on all hosted runners)
ready=0
for _ in $(seq 1 20); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/app.jnlp" 2>/dev/null; then
    ready=1
    break
  fi
  sleep 1
done
[ "$ready" = 1 ] || fail "Groovy sample server did not come up on port $PORT"

# --- isolated user config (macOS: ~/Library/Application Support/icedtea-web; linux/windows: XDG) ---
export HOME="$WORK/home"
export XDG_CONFIG_HOME="$WORK/home/.config"
export XDG_CACHE_HOME="$WORK/home/.cache"
export XDG_DATA_HOME="$WORK/home/.local/share"
mkdir -p "$HOME"

# --- 1. launcher run -> handoff record ---
URL="http://127.0.0.1:$PORT/app.jnlp"
run_capped 30 "$LAUNCHER" -headless -Xtrustall -J-Djava.awt.headless=true "$URL"

LOG_BASE="$(find "$HOME" -type d -name log -path "*icedtea-web*" 2>/dev/null | head -1)"
MAIN_LOG="$(find "$LOG_BASE" -name "*.log" ! -name "*prelaunch*" ! -name "*relaunch*" 2>/dev/null | head -1)"
[ -n "$MAIN_LOG" ] || fail "no launcher handoff log found under $HOME"

CHILD_EXE="$(grep -a "Child executable:" "$MAIN_LOG" | head -1 | sed 's/.*Child executable: *//')"
CHILD_VER="$(grep -a "Child JVM version:" "$MAIN_LOG" | head -1 | sed 's/.*Child JVM version: *//')"
echo "== download JVM = $CHILD_VER at $CHILD_EXE"
CHILD_EXE_UNIX="${CHILD_EXE//\\//}"
case "$CHILD_EXE_UNIX" in
  */runtime/temurin-21/*) ;;
  *) fail "download JVM not under runtime/temurin-21: $CHILD_EXE" ;;
esac
case "$CHILD_VER" in
  "$EXPECTED_MAJOR"*) ;;
  *) fail "download JVM version $CHILD_VER does not start with $EXPECTED_MAJOR" ;;
esac

# --- 2. app launch evidence ---
marker_found=0
# primary: poll the ITW javantx logs (Windows captures child output there; Linux/macOS sometimes too)
for _ in $(seq 1 30); do
  if grep -ra "ITW_SMOKE_SUCCESS" "$LOG_BASE" 2>/dev/null | grep -qv "SMOKE-FAIL"; then
    marker_found=1
    break
  fi
  sleep 1
done

if [ "$marker_found" = 0 ] && [ "$IS_WINDOWS" != 1 ]; then
  # fallback: re-run the child command directly (handoff Command is not reliably
  # captured in logs on Linux/macOS, and is unquoted so unusable on Windows)
  CMD="$(grep -A1 "^Command:" "$MAIN_LOG" | tail -1 | sed 's/^ *//')"
  if [ -n "$CMD" ]; then
    run_capped 45 bash -c "$CMD"
    if grep -aq "ITW_SMOKE_SUCCESS" "$WORK/last-run.out"; then
      marker_found=1
    else
      echo "child output:"; tail -15 "$WORK/last-run.out"
    fi
  fi
fi

[ "$marker_found" = 1 ] || fail "app did not print ITW_SMOKE_SUCCESS (handoff=$CHILD_VER)"
echo "== app launched on bundled JVM (ITW_SMOKE_SUCCESS found)"

# --- 3. cache + seeded JVM order ---
CACHED="$(find "$HOME" -name "app.jar" -path "*icedtea-web/cache*" 2>/dev/null | head -1)"
[ -n "$CACHED" ] || fail "app.jar not found in cache"
echo "== cached: $CACHED"

PROPS="$(find "$HOME" -name deployment.properties 2>/dev/null | head -1)"
[ -n "$PROPS" ] || fail "no deployment.properties found"
ORDER="$(grep "deployment.jdk." "$PROPS" | sed 's/.*temurin-/temurin-/')"
echo "== seeded JVM order:"
echo "$ORDER"
seq_order="$(printf '%s\n' "$ORDER" | sed 's/^temurin-\([0-9]*\).*/\1/' | tr '\n' ' ')"
[ "$seq_order" = "21 17 11 25 " ] || fail "bundled seed order was '$seq_order', expected 21 17 11 25"

echo "SMOKE-SUCCESS: packaged distribution $DIST"
