#!/bin/bash
# Maven-packaged javaws wrapper for the shaded uber JAR.
# Supports -J<jvm-arg> forwarding (same as native ITW launchers) and relaunch via icedtea-web.bin.location.
# ByteBuddy javaagent + bootclasspath mirror the native ITW launcher so JarFileCloseProtection works.

set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do
  SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)"
  SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$SCRIPT_DIR/$SCRIPT_SOURCE"
done
readonly SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)"
readonly BINARY_LOCATION="$SCRIPT_DIR/$(basename "$SCRIPT_SOURCE")"
readonly ITW_UBER_JAR="@ITW_UBER_JAR@"
readonly BYTEBUDDY_AGENT_JAR="@BYTEBUDDY_AGENT_JAR@"
readonly MAIN_CLASS="@MAIN_CLASS@"

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA="${JAVA_HOME}/bin/java"
else
  JAVA="${JAVA:-java}"
fi

JAVA_ARGS=()
ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    -J*)
      JAVA_ARGS+=("${1#-J}")
      ;;
    *)
      ARGS+=("$1")
      ;;
  esac
  shift
done

# ByteBuddy javaagent JAR must stay separate from the uber JAR
if [ -f "$BYTEBUDDY_AGENT_JAR" ]; then
  JAVA_ARGS+=("-javaagent:${BYTEBUDDY_AGENT_JAR}")
fi

MODULAR_ARGS=()
USE_BOOTCLASSPATH="YES"
fullversion="$("$JAVA" -version 2>&1 || true)"
version="$(echo "$fullversion" | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
major="${version%%.*}"
if [ "$major" = "1" ]; then
  major="$(echo "$version" | cut -d. -f2)"
fi
if [ "${major:-0}" -ge 9 ] 2>/dev/null; then
  USE_BOOTCLASSPATH="NO"
  MODULAR_ARGS=(
    --add-exports java.base/sun.net.www.protocol.jar=ALL-UNNAMED
    --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED
    --add-exports java.base/sun.security.action=ALL-UNNAMED
    --add-exports java.base/sun.security.provider=ALL-UNNAMED
    --add-exports java.base/sun.security.util=ALL-UNNAMED
    --add-exports java.base/sun.security.validator=ALL-UNNAMED
    --add-exports java.base/sun.security.x509=ALL-UNNAMED
    --add-exports java.base/jdk.internal.util.jar=ALL-UNNAMED
    --add-opens
    java.base/jdk.internal.util.jar=ALL-UNNAMED
    --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED
    --add-exports java.desktop/sun.applet=ALL-UNNAMED
    --add-exports java.desktop/sun.awt=ALL-UNNAMED
    --add-exports java.desktop/sun.awt.image=ALL-UNNAMED
    --add-exports java.desktop/sun.swing.table=ALL-UNNAMED
    --add-exports java.desktop/sun.swing=ALL-UNNAMED
    --add-exports java.desktop/sun.swing.plaf=ALL-UNNAMED
    --add-exports java.naming/com.sun.jndi.toolkit.url=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
  )
  case "$(uname -s)" in
    Linux)
      MODULAR_ARGS+=(
        --add-exports java.desktop/sun.awt.X11=ALL-UNNAMED
      )
      ;;
    MINGW*|MSYS*|CYGWIN*)
      MODULAR_ARGS+=(
        --add-exports java.desktop/sun.awt.windows=ALL-UNNAMED
        --add-exports java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED
      )
      ;;
  esac
fi

if [ "$USE_BOOTCLASSPATH" = "YES" ]; then
  # JDK 8: advice classes must be on bootclasspath when ZipFile is patched
  JAVA_ARGS+=("-Xbootclasspath/a:${ITW_UBER_JAR}")
fi

exec "$JAVA" -Xms8m "${MODULAR_ARGS[@]}" "${JAVA_ARGS[@]}" \
  -Dicedtea-web.bin.name=javaws \
  -Dicedtea-web.bin.location="$BINARY_LOCATION" \
  -cp "$ITW_UBER_JAR" \
  "$MAIN_CLASS" "${ARGS[@]}"
