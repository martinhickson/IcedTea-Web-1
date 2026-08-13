#!/usr/bin/env bash
# Fast probe runner: Groovy 5.0.8 + icedtea-web uber jar (no Maven Surefire).
# Usage:
#   scripts/run-itw-groovy.sh scripts/probes/pack-admission.groovy
#   scripts/run-itw-groovy.sh --compile-java scripts/probes/settle.groovy
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPILE_JAVA=0
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--compile-java" ]]; then
    COMPILE_JAVA=1
  else
    ARGS+=("$arg")
  fi
done

if [[ ${#ARGS[@]} -lt 1 ]]; then
  echo "Usage: $0 [--compile-java] <script.groovy> [script args...]" >&2
  exit 2
fi

SCRIPT="${ARGS[0]}"
SCRIPT_ARGS=("${ARGS[@]:1}")

if [[ ! -f "$SCRIPT" ]]; then
  # allow bare probe name
  if [[ -f "$ROOT_DIR/scripts/probes/$SCRIPT" ]]; then
    SCRIPT="$ROOT_DIR/scripts/probes/$SCRIPT"
  elif [[ -f "$ROOT_DIR/scripts/probes/${SCRIPT}.groovy" ]]; then
    SCRIPT="$ROOT_DIR/scripts/probes/${SCRIPT}.groovy"
  else
    echo "Script not found: $SCRIPT" >&2
    exit 1
  fi
fi

JAVA_HOME="${JAVA_HOME:-${JDK11_HOME:-}}"
if [[ -z "${JAVA_HOME}" ]]; then
  if [[ -d "/c/Program Files/Amazon Corretto/jdk17.0.15_6" ]]; then
    JAVA_HOME="/c/Program Files/Amazon Corretto/jdk17.0.15_6"
  fi
fi
if [[ -z "${JAVA_HOME}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "Set JAVA_HOME to JDK 11+ (Corretto 17 recommended)." >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

GROOVY_HOME="$("$ROOT_DIR/scripts/ensure-groovy-5.sh")"
export GROOVY_HOME
export PATH="$GROOVY_HOME/bin:$PATH"

if [[ "$COMPILE_JAVA" -eq 1 ]]; then
  echo "Compiling icedtea-web (generate-sources + compile)..."
  mvn -s "$ROOT_DIR/.powershell/workflows/maven-settings.xml" -pl icedtea-web -am \
    -DskipTests compile \
    "-Djdk11.home=$JAVA_HOME" -q
fi

UBER=$(ls -1 "$ROOT_DIR"/icedtea-web/target/icedtea-web-*-uber.jar 2>/dev/null | head -1 || true)
CLASSES="$ROOT_DIR/icedtea-web/target/classes"
if [[ -z "$UBER" && ! -d "$CLASSES" ]]; then
  echo "No icedtea-web classes/uber jar. Run with --compile-java or mvn package -pl icedtea-web -am once." >&2
  exit 1
fi

# Prefer classes (picks up last compile) ahead of uber jar.
to_cp_entry() {
  local p="$1"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$p"
      else
        # /c/foo -> C:\foo
        echo "$p" | sed -e 's|^/\([a-zA-Z]\)/|\1:\\|' -e 's|/|\\|g'
      fi
      ;;
    *)
      echo "$p"
      ;;
  esac
}

SEP=':'
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
esac

CP=""
if [[ -d "$CLASSES" ]]; then
  CP="$(to_cp_entry "$CLASSES")"
fi
if [[ -n "$UBER" ]]; then
  entry="$(to_cp_entry "$UBER")"
  CP="${CP:+$CP$SEP}$entry"
fi
if [[ -z "$CP" ]]; then
  CP="."
fi

echo "Groovy $($GROOVY_HOME/bin/groovy -version 2>&1 | head -1)"
echo "Classpath: $CP"
echo "Running $SCRIPT"
# headless avoids AWT shutdown hangs when ITW touches logging/UI classes
export JAVA_OPTS="${JAVA_OPTS:-} -Djava.awt.headless=true"
exec "$GROOVY_HOME/bin/groovy" -cp "$CP" "$SCRIPT" "${SCRIPT_ARGS[@]}"
