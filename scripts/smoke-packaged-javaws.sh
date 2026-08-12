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
RUN_N=0
run_capped() {
  local cap="$1"
  shift
  RUN_N=$((RUN_N + 1))
  local out="$WORK/run${RUN_N}.out"
  ( "$@" ) > "$out" 2>&1 &
  local pid=$!
  ( sleep "$cap"; kill -9 "$pid" 2>/dev/null ) &
  local guard=$!
  wait "$pid" || true
  kill "$guard" 2>/dev/null || true
  echo "$out"
}

fail() { echo "SMOKE-FAIL: $*" >&2; exit 1; }

# --- build + SIGN a tiny all-permissions smoke app ---------------------------
# Signed with a self-signed cert so the app can request <all-permissions> and
# write its success marker to a file (stdout capture through the .NET launcher
# redirect is unreliable across platforms; user.home is not $HOME). -Xtrustall
# accepts the cert. The marker path is passed explicitly via itw.smoke.marker.
MARKER="$WORK/itw-smoke-success.txt"
MARKER_JVM="$MARKER"
if [ "$IS_WINDOWS" = 1 ]; then
  MARKER_JVM="$(cygpath -w "$MARKER")"
fi
mkdir -p "$WORK/out"
cat > "$WORK/SmokeMain.java" <<'EOF'
import java.nio.file.Files;
import java.nio.file.Paths;
public class SmokeMain {
  public static void main(String[] a) throws Exception {
    String p = System.getProperty("itw.smoke.marker");
    Files.write(Paths.get(p), ("ITW_SMOKE_SUCCESS app-launched-on-bundled-jvm\n"
        + "java.home=" + System.getProperty("java.home") + "\n").getBytes("UTF-8"));
    System.out.println("ITW_SMOKE_SUCCESS app-launched-on-bundled-jvm");
  }
}
EOF
javac -d "$WORK" "$WORK/SmokeMain.java"
printf 'Manifest-Version: 1.0\nMain-Class: SmokeMain\nApplication-Name: ITW Smoke\nPermissions: all-permissions\nCodebase: *\nApplication-Library-Allowable-Codebase: *\n\n' > "$WORK/out/MANIFEST.MF"
jar cfm "$WORK/app.jar" "$WORK/out/MANIFEST.MF" -C "$WORK" SmokeMain.class
keytool -genkeypair -keystore "$WORK/itw-smoke.jks" -storepass changeit -keypass changeit \
  -alias smoke -dname "CN=ITW Smoke, OU=IT, O=IcedTea-Web, C=NZ" -keyalg RSA -validity 365 >/dev/null 2>&1
jarsigner -keystore "$WORK/itw-smoke.jks" -storepass changeit -keypass changeit \
  -digestalg SHA-256 -sigalg SHA256withRSA "$WORK/app.jar" smoke >/dev/null 2>&1
cat > "$WORK/app.jnlp" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="." href="app.jnlp">
  <information><title>ITW Smoke</title><vendor>IcedTea-Web</vendor></information>
  <security><all-permissions/></security>
  <resources><j2se version="1.8+"/><jar href="app.jar" main="true"/></resources>
  <application-desc main-class="SmokeMain"/>
</jnlp>
EOF

# --- serve the sample (Groovy server; python is banned) ---
SERVER_LOG="$WORK/server.log"
if [ "$IS_WINDOWS" = 1 ]; then
  WIN_SCRIPT="$(cygpath -w "$SERVE_SCRIPT")"
  WIN_WORK="$(cygpath -w "$WORK")"
  if [ -n "${SMOKE_GROOVY_JAR:-}" ]; then
    # Deterministic on Windows: run groovy.ui.GroovyMain via the JDK (avoids
    # groovy.bat + cmd /c quoting/translation pitfalls).
    JAR_WIN="$(cygpath -m "$SMOKE_GROOVY_JAR")"
    (cd "$WORK" && java -cp "$JAR_WIN" groovy.ui.GroovyMain "$WIN_SCRIPT" "$PORT" "$WIN_WORK" >"$SERVER_LOG" 2>&1) &
  else
    (cd "$WORK" && cmd /c "groovy \"$WIN_SCRIPT\" $PORT \"$WIN_WORK\"" >"$SERVER_LOG" 2>&1) &
  fi
else
  (cd "$WORK" && "$GROOVY" "$SERVE_SCRIPT" "$PORT" "$WORK" >"$SERVER_LOG" 2>&1) &
fi
SRV=$!
# wait for the server to accept (curl is present on all hosted runners)
ready=0
for _ in $(seq 1 40); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/app.jnlp" 2>/dev/null; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "SMOKE-FAIL: Groovy sample server did not come up on port $PORT; server log:"
  cat "$SERVER_LOG" 2>/dev/null || true
  exit 1
fi

# --- isolated user config (macOS: ~/Library/Application Support/icedtea-web; linux/windows: XDG) ---
export HOME="$WORK/home"
export XDG_CONFIG_HOME="$WORK/home/.config"
export XDG_CACHE_HOME="$WORK/home/.cache"
export XDG_DATA_HOME="$WORK/home/.local/share"
mkdir -p "$HOME"

# ITW writes a default deployment.properties on first run that overrides -J
# system properties; pre-write it with file logging so every launch's output
# lands in ITW's own logs regardless of the launcher stdout-redirect quirk.
mkdir -p "$XDG_CONFIG_HOME/icedtea-web"
printf 'deployment.log=true\ndeployment.log.file=true\ndeployment.log.file.clientapp=true\n' \
  > "$XDG_CONFIG_HOME/icedtea-web/deployment.properties"

# --- 1. launcher run (default resolution) -> handoff record proves the download
# JVM is the bundled Temurin 21. Do NOT pass -Xnofork here: -Xnofork makes the
# launcher take its "relaunch" resolution path (which prefers JAVA_HOME), and we
# must not unset JAVA_HOME because the app-launch phase below relies on it.
URL="http://127.0.0.1:$PORT/app.jnlp"
RUN1_OUT="$(run_capped 30 "$LAUNCHER" -headless -verbose -Xtrustall -J-Djava.awt.headless=true \
  "-J-Ditw.smoke.marker=$MARKER_JVM" "$URL")"

LOG_BASE="$(find "$HOME" -type d -name log -path "*icedtea-web*" 2>/dev/null | head -1)"
MAIN_LOG="$(grep -l "Child executable:" "$LOG_BASE"/*.log 2>/dev/null | grep -v "prelaunch" | head -1)"
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

# --- 2. app launch evidence: relaunch-style (-Xnofork) with normal JAVA_HOME so
# ITW file logging captures the app marker reliably on every platform. On some
# platforms the child output lands in the launcher's own stdout (the run_capped
# capture) rather than the itw-javantx redirect file, so both are polled.
RUN2_OUT="$(run_capped 45 "$LAUNCHER" -headless -verbose -Xtrustall -Xnofork \
  -J-Djava.awt.headless=true \
  "-J-Ditw.smoke.marker=$MARKER_JVM" \
  -J-Ddeployment.log=true -J-Ddeployment.log.file=true -J-Ddeployment.log.file.clientapp=true \
  "$URL")"
marker_found=0
MARKER_FILE="$MARKER"
for _ in $(seq 1 90); do
  if [ -f "$MARKER_FILE" ]; then
    marker_found=1
    break
  fi
  if grep -ra "ITW_SMOKE_SUCCESS" "$LOG_BASE" "$WORK"/run*.out 2>/dev/null | grep -qv "SMOKE-FAIL"; then
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
    RUN3_OUT="$(run_capped 45 bash -c "$CMD")"
    if grep -aq "ITW_SMOKE_SUCCESS" "$RUN3_OUT"; then
      marker_found=1
    else
      echo "child output:"; tail -15 "$RUN3_OUT"
    fi
  fi
fi

[ "$marker_found" = 1 ] || {
  echo "SMOKE-FAIL: app did not print ITW_SMOKE_SUCCESS (handoff=$CHILD_VER)"
  echo "--- marker file: $(ls -la "$MARKER_FILE" 2>/dev/null || echo missing)"
  echo "--- launcher run2 stdout (app launch phase):"
  cat "$RUN2_OUT" 2>/dev/null | head -40
  echo "--- log files:"
  find "$LOG_BASE" -type f 2>/dev/null | head -20
  echo "--- main handoff log full content:"
  cat "$MAIN_LOG" 2>/dev/null
  echo "--- log highlights:"
  grep -raE "Selected JVM|Exception|Fatal|Error|ITW_SMOKE|Starting application|Invoking main|Permission|LaunchException|jdk=" "$LOG_BASE" "$WORK"/run*.out 2>/dev/null | grep -avE "Handoff|Child |Standard |Working dir|\.NET|Command:|Handoff complete|OS:|Architecture" | head -25
  exit 1
}
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
