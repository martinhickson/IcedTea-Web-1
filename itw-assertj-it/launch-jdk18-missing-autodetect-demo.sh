#!/usr/bin/env bash
# Launch java18-app via javaws with no configured JDK 18 so the install/autodetect
# prompt appears (JDK 18 is rarely installed). Requires a graphical display.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="$SCRIPT_DIR/target/itw-test-home-jdk18-demo"
SAMPLE_NAME="java18-app"
REQUIRED_JDK_MAJOR=18

REBUILD=0
KEEP_HOME=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Launch the ${SAMPLE_NAME} GUI sample with javaws using an isolated config under:
  $ITW_TEST_HOME

No deployment.jdk.* entries and deployment.autodetectJDKs=false. javaws starts on
JDK 11 without -headless. The JNLP requires exact Java ${REQUIRED_JDK_MAJOR}, so higher
installed JDKs (17, 21, …) do not satisfy it. If JDK ${REQUIRED_JDK_MAJOR} is not
installed you should see:

  "Install JDK ${REQUIRED_JDK_MAJOR}, then press Autodetect."

If JDK ${REQUIRED_JDK_MAJOR} is present on this machine, you'll get the Apply dialog
instead (use a host without JDK ${REQUIRED_JDK_MAJOR} to exercise the missing-JDK path).

Options:
  --rebuild       Build and install icedtea-web before launching
  --keep-home     Do not reset $ITW_TEST_HOME before launch
  -h, --help      Show this help

Requires a graphical display (\$DISPLAY set).
Logs: scripts/tail-itw-logs.sh $ITW_TEST_HOME
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
    "${ITW_JDK18_HOME:-}" \
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

JDK18_HOME="$(find_jdk_home_with_major "$REQUIRED_JDK_MAJOR" || true)"

if [[ "$KEEP_HOME" -eq 0 ]]; then
  rm -rf "$ITW_TEST_HOME"
fi
mkdir -p "$ITW_TEST_HOME/.config/icedtea-web" "$ITW_TEST_HOME/.cache/icedtea-web"

PROPS="$ITW_TEST_HOME/.config/icedtea-web/deployment.properties"
if [[ "$KEEP_HOME" -eq 0 || ! -f "$PROPS" ]]; then
  cat > "$PROPS" <<EOF
# itw-assertj-it jdk${REQUIRED_JDK_MAJOR} missing autodetect demo
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
  echo "Ensure JDK 21+ is available to build java18-app (compile-test-apps uses --release 18)." >&2
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
echo "  JNLP requires: exact Java ${REQUIRED_JDK_MAJOR} (not ${REQUIRED_JDK_MAJOR}+)"
if [[ -n "$JDK18_HOME" ]]; then
  echo "  system JDK ${REQUIRED_JDK_MAJOR}: $JDK18_HOME"
  echo "  note: JDK ${REQUIRED_JDK_MAJOR} is installed — expect Apply dialog, not install prompt"
else
  echo "  system JDK ${REQUIRED_JDK_MAJOR}: not found — expect install + Autodetect prompt"
  if find_jdk_home_with_major 21 >/dev/null 2>&1 || find_jdk_home_with_major 17 >/dev/null 2>&1; then
    echo "  note: higher JDKs are present but ignored for exact Java ${REQUIRED_JDK_MAJOR}"
  fi
fi
echo "  javaws=$JAVAWS_BIN"
echo "  jnlp=$JNLP_URL"
echo "  logs=$XDG_CONFIG_HOME/icedtea-web/log/  (tail: scripts/tail-itw-logs.sh $ITW_TEST_HOME)"
echo

exec "$JAVAWS_BIN" -verbose -Xtrustall "$JNLP_URL"
