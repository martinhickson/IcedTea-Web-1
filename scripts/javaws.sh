#!/bin/bash
# Maven-packaged javaws wrapper for the shaded uber JAR.
# Supports -J<jvm-arg> forwarding (same as native ITW launchers) and relaunch via icedtea-web.bin.location.
# ByteBuddy javaagent + bootclasspath mirror the native ITW launcher so JarFileCloseProtection works.

set -euo pipefail

# Marks this JVM tree as started from a native/shell wrapper (not bare java -cp).
export ITW_NATIVE_LAUNCHER=1

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
is_known_missing_package() {
  local module_package="$1"
  case "${major:-}" in
    11)
      case "$module_package" in
        java.base/sun.misc|java.desktop/javax.jnlp) return 0 ;;
      esac
      ;;
    17)
      case "$module_package" in
        java.base/com.sun.net.ssl.internal.ssl|java.base/sun.misc|java.desktop/sun.applet|java.desktop/javax.jnlp) return 0 ;;
      esac
      ;;
    21|25)
      case "$module_package" in
        java.base/com.sun.net.ssl.internal.ssl|java.base/sun.misc|java.base/jdk.internal.util.jar|java.desktop/sun.applet|java.desktop/javax.jnlp) return 0 ;;
      esac
      ;;
  esac
  if [ "${major:-}" = "25" ]; then
      case "$module_package" in
        java.base/sun.security.action) return 0 ;;
      esac
  fi
  return 1
}

add_module_access() {
  local option="$1"
  local module="$2"
  local package_name="$3"
  local module_package="${module}/${package_name}"
  if is_known_missing_package "$module_package"; then
    return
  fi
  MODULAR_ARGS+=("$option" "${module_package}=ALL-UNNAMED")
}

if [ "${major:-0}" -ge 9 ] 2>/dev/null; then
  USE_BOOTCLASSPATH="NO"
  add_module_access --add-exports java.base sun.net.www.protocol.jar
  add_module_access --add-opens java.base sun.net.www.protocol.jar
  add_module_access --add-exports java.base sun.security.action
  add_module_access --add-exports java.base sun.security.provider
  add_module_access --add-exports java.base sun.security.util
  add_module_access --add-exports java.base sun.security.validator
  add_module_access --add-exports java.base sun.security.x509
  add_module_access --add-exports java.base jdk.internal.util.jar
  add_module_access --add-opens java.base jdk.internal.util.jar
  add_module_access --add-exports java.base sun.net.www.protocol.http
  add_module_access --add-exports java.desktop sun.applet
  add_module_access --add-exports java.desktop sun.awt
  add_module_access --add-exports java.desktop sun.awt.image
  add_module_access --add-exports java.desktop sun.swing.table
  add_module_access --add-exports java.desktop sun.swing
  add_module_access --add-exports java.desktop sun.swing.plaf
  add_module_access --add-exports java.naming com.sun.jndi.toolkit.url
  add_module_access --add-opens java.base java.lang
  case "$(uname -s)" in
    Linux)
      add_module_access --add-exports java.desktop sun.awt.X11
      ;;
    MINGW*|MSYS*|CYGWIN*)
      add_module_access --add-exports java.desktop sun.awt.windows
      add_module_access --add-exports java.desktop com.sun.java.swing.plaf.windows
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
