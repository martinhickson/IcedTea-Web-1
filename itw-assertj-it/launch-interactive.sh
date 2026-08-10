#!/usr/bin/env bash
# Launch the IcedTea-Web control panel with the same isolated test home as
# itw-assertj-it Failsafe runs. Keeps running until you close the window.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="$SCRIPT_DIR/target/itw-test-home"

REBUILD=0
KEEP_HOME=0
USE_SYSTEM_LAF=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Open icedtea-web-settings interactively using an isolated config under:
  $ITW_TEST_HOME

Seeds JDK assignments with file:// URLs for runnable itw-assertj-it sample apps
(java17-app, java21-app, java25-app, gui-app) unless --keep-home is used.

Options:
  --rebuild       Build and install icedtea-web before launching
  --keep-home     Do not reset target/itw-test-home before launch
  --system-laf    Use the system Swing look-and-feel (default: Metal, like ITs)
  -h, --help      Show this help

Requires a graphical display (\$DISPLAY set).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=1 ;;
    --keep-home) KEEP_HOME=1 ;;
    --system-laf) USE_SYSTEM_LAF=1 ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ -z "${DISPLAY:-}" ]]; then
  echo "DISPLAY is not set. Use a desktop session or set DISPLAY (e.g. :0)." >&2
  exit 1
fi

ITW_VERSION="$(sed -n 's:.*<icedtea-web.version>\(.*\)</icedtea-web.version>.*:\1:p' "$SCRIPT_DIR/pom.xml")"
if [[ -z "$ITW_VERSION" ]]; then
  echo "Could not read icedtea-web.version from pom.xml" >&2
  exit 1
fi

UBER_JAR="$HOME/.m2/repository/net/sourceforge/icedtea-web/icedtea-web/${ITW_VERSION}/icedtea-web-${ITW_VERSION}-uber.jar"

if [[ "$REBUILD" -eq 1 || ! -f "$UBER_JAR" ]]; then
  echo "Building icedtea-web ${ITW_VERSION} ..."
  (cd "$REPO_ROOT" && mvn -q install -pl icedtea-web -am -DskipTests)
fi

if [[ ! -f "$UBER_JAR" ]]; then
  echo "Uber JAR not found: $UBER_JAR" >&2
  exit 1
fi

if [[ "$KEEP_HOME" -eq 0 ]]; then
  rm -rf "$ITW_TEST_HOME"
fi
mkdir -p "$ITW_TEST_HOME/.config/icedtea-web" "$ITW_TEST_HOME/.cache/icedtea-web"

echo "Building test JNLP sample apps ..."
chmod +x "$SCRIPT_DIR/scripts/compile-test-apps.sh"
"$SCRIPT_DIR/scripts/compile-test-apps.sh"

export ITW_VERSION UBER_JAR
chmod +x "$SCRIPT_DIR/scripts/prepare-javaws-launcher.sh" \
  "$SCRIPT_DIR/scripts/seed-interactive-home.sh" \
  "$SCRIPT_DIR/scripts/install-test-trust-cert.sh"
JAVAWS_BIN="$("$SCRIPT_DIR/scripts/prepare-javaws-launcher.sh")"

"$SCRIPT_DIR/scripts/install-test-trust-cert.sh" "$ITW_TEST_HOME"
if [[ "$KEEP_HOME" -eq 0 ]]; then
  "$SCRIPT_DIR/scripts/seed-interactive-home.sh" "$ITW_TEST_HOME"
fi

JAVA_OPTS=(
  -Duser.home="$ITW_TEST_HOME"
  -Djava.awt.headless=false
  -Dicedtea-web.bin.location="$JAVAWS_BIN"
)
LAF_OPTS=()
if [[ "$USE_SYSTEM_LAF" -eq 0 ]]; then
  LAF_OPTS=(-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel)
fi

export XDG_CONFIG_HOME="$ITW_TEST_HOME/.config"
export XDG_CACHE_HOME="$ITW_TEST_HOME/.cache"
export HOME="$ITW_TEST_HOME"
export PATH="$SCRIPT_DIR/target/bin:$PATH"

echo "Launching control panel (close the window to exit)."
echo "  DISPLAY=$DISPLAY"
echo "  user.home=$ITW_TEST_HOME"
echo "  XDG_CONFIG_HOME=$XDG_CONFIG_HOME"
echo "  deployment.properties=$XDG_CONFIG_HOME/icedtea-web/deployment.properties"
echo "  javaws=$JAVAWS_BIN"
echo "  uber JAR=$UBER_JAR"
echo "  logs=$XDG_CONFIG_HOME/icedtea-web/log/  (tail: scripts/tail-itw-logs.sh)"
echo

exec java "${JAVA_OPTS[@]}" "${LAF_OPTS[@]}" -cp "$UBER_JAR" net.sourceforge.jnlp.controlpanel.ControlPanel
