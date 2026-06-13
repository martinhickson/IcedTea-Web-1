#!/usr/bin/env bash
# Launch java21-app via javaws with an empty JVM list (JDK 11 starter only) so the
# guided JDK 21 autodetect dialog appears. Requires a graphical display.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="$SCRIPT_DIR/target/itw-test-home"
SAMPLE_NAME="java21-app"

REBUILD=0
KEEP_HOME=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Launch the ${SAMPLE_NAME} GUI sample with javaws using an isolated config under:
  $ITW_TEST_HOME

Matches JdkAutodetectJavawsLaunchIT setup: no deployment.jdk.* entries and
deployment.autodetectJDKs=false. javaws runs on JDK 11 without -headless so the
JDK autodetect / Apply dialog is shown when the JNLP requires Java 21+.

If JDK 21 is installed on this machine (e.g. under /usr/lib/jvm), Apply should
offer to add it and relaunch. If not, use Autodetect after installing JDK 21.

Options:
  --rebuild       Build and install icedtea-web before launching
  --keep-home     Do not reset target/itw-test-home before launch
  -h, --help      Show this help

Requires a graphical display (\$DISPLAY set).
Logs: scripts/tail-itw-logs.sh
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=1 ;;
    --keep-home) KEEP_HOME=1 ;;
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

jdk_major() {
  local java_bin="$1/bin/java"
  if [[ ! -x "$java_bin" ]]; then
    echo 0
    return
  fi
  local version
  version="$("$java_bin" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  if [[ "$version" == 1.* ]]; then
    echo "${version#1.}" | cut -d. -f1
  else
    echo "${version%%.*}"
  fi
}

find_jdk_home_with_major() {
  local want_major="$1"
  local candidate home major
  for candidate in \
    "${ITW_JDK11_HOME:-}" \
    "${ITW_JDK17_HOME:-}" \
    "${ITW_JDK21_HOME:-}" \
    "${ITW_JDK25_HOME:-}" \
    /usr/lib/jvm/java-11-amazon-corretto \
    /usr/lib/jvm/java-11-openjdk-amd64 \
    /usr/lib/jvm/*; do
    [[ -n "$candidate" && -d "$candidate" ]] || continue
    home="$(readlink -f "$candidate")"
    major="$(jdk_major "$home")"
    if [[ "$major" -eq "$want_major" ]]; then
      echo "$home"
      return 0
    fi
  done
  return 1
}

file_url() {
  local path
  path="$(readlink -f "$1")"
  echo "file://${path}"
}

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

JDK11_HOME="$(find_jdk_home_with_major 11 || true)"
if [[ -z "$JDK11_HOME" ]]; then
  echo "No JDK 11 found. Install JDK 11 or set ITW_JDK11_HOME." >&2
  exit 1
fi

JDK21_HOME="$(find_jdk_home_with_major 21 || true)"

if [[ "$KEEP_HOME" -eq 0 ]]; then
  rm -rf "$ITW_TEST_HOME"
fi
mkdir -p "$ITW_TEST_HOME/.config/icedtea-web" "$ITW_TEST_HOME/.cache/icedtea-web"

PROPS="$ITW_TEST_HOME/.config/icedtea-web/deployment.properties"
if [[ "$KEEP_HOME" -eq 0 || ! -f "$PROPS" ]]; then
  cat > "$PROPS" <<EOF
# itw-assertj-it jdk21 autodetect demo
deployment.autodetectJDKs=false
deployment.log=true
deployment.log.file=true
EOF
fi

echo "Building test JNLP sample apps ..."
chmod +x "$SCRIPT_DIR/scripts/compile-test-apps.sh"
"$SCRIPT_DIR/scripts/compile-test-apps.sh"

export ITW_VERSION UBER_JAR
chmod +x "$SCRIPT_DIR/scripts/prepare-javaws-launcher.sh" \
  "$SCRIPT_DIR/scripts/install-test-trust-cert.sh"
JAVAWS_BIN="$("$SCRIPT_DIR/scripts/prepare-javaws-launcher.sh")"

"$SCRIPT_DIR/scripts/install-test-trust-cert.sh" "$ITW_TEST_HOME"

JNLP="$SCRIPT_DIR/target/test-jnlp-samples/${SAMPLE_NAME}/app.jnlp"
if [[ ! -f "$JNLP" ]]; then
  echo "Missing sample JNLP: $JNLP" >&2
  exit 1
fi
JNLP_URL="$(file_url "$JNLP")"

export XDG_CONFIG_HOME="$ITW_TEST_HOME/.config"
export XDG_CACHE_HOME="$ITW_TEST_HOME/.cache"
export HOME="$ITW_TEST_HOME"
export JAVA_HOME="$JDK11_HOME"
export PATH="$SCRIPT_DIR/target/bin:$PATH"

echo "Launching ${SAMPLE_NAME} (close dialogs or the app to exit)."
echo "  DISPLAY=$DISPLAY"
echo "  JAVA_HOME=$JAVA_HOME ($(jdk_major "$JAVA_HOME"))"
echo "  user.home=$ITW_TEST_HOME"
echo "  deployment.properties=$PROPS"
echo "  configured JDKs: none (autodetectJDKs=false)"
if [[ -n "$JDK21_HOME" ]]; then
  echo "  system JDK 21: $JDK21_HOME (not configured — Apply should detect it)"
else
  echo "  system JDK 21: not found — expect install prompt; use Autodetect after installing"
fi
echo "  javaws=$JAVAWS_BIN"
echo "  jnlp=$JNLP_URL"
echo "  logs=$XDG_CONFIG_HOME/icedtea-web/log/  (tail: scripts/tail-itw-logs.sh)"
echo

exec "$JAVAWS_BIN" -verbose -Xtrustall "$JNLP_URL"
