#!/usr/bin/env bash
# Mandatory release gates: JDK selection / autodetect persistence must survive Apply and relaunch.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MAVEN_SETTINGS="${MAVEN_SETTINGS:-$ROOT/.powershell/workflows/maven-settings.xml}"
JDK11_HOME="${JDK11_HOME:-${JAVA_HOME_11_X64:-${JAVA_HOME:-}}}"
JDK17_HOME="${JDK17_HOME:-${JAVA_HOME_17_X64:-}}"
JDK21_HOME="${JDK21_HOME:-${JAVA_HOME_21_X64:-}}"
JDK25_HOME="${JDK25_HOME:-${JAVA_HOME_25_X64:-}}"

if [ -z "$JDK11_HOME" ] || [ ! -x "$JDK11_HOME/bin/java" ]; then
  echo "JDK11_HOME must point at a JDK 11+ home with bin/java" >&2
  exit 1
fi

export JAVA_HOME="$JDK11_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

ITW_VERSION="$(
  mvn -s "$MAVEN_SETTINGS" -q -DforceStdout -Dexpression=project.version help:evaluate 2>/dev/null \
    | tail -n 1
)"
if [ -z "$ITW_VERSION" ] || [ "$ITW_VERSION" = "null object or invalid expression" ]; then
  ITW_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -n 1)"
fi
echo "Using icedtea-web.version=$ITW_VERSION"

echo "==> Unit gates: Apply / launch-autodetect persistence"
mvn -s "$MAVEN_SETTINGS" -pl icedtea-web -am \
  -Dexec.skip=true \
  -Djdk11.home="$JDK11_HOME" \
  -Dbash.executable=bash \
  -Dtest=DeploymentConfigurationJdkApplyPersistTest,KnownJvmStoreEnsureHomeTest,JvmSelectorTest,MultiJ2seVmArgsTest \
  -DfailIfNoTests=false \
  test

echo "==> Build uber JAR for AssertJ persistence ITs"
mvn -s "$MAVEN_SETTINGS" -pl icedtea-web -am \
  -DskipTests -Dmaven.test.skip=true -Dexec.skip=true \
  -Djdk11.home="$JDK11_HOME" \
  -Dbash.executable=bash \
  install

IT_JDK17="${JDK17_HOME:-$JDK11_HOME}"
IT_JDK21="${JDK21_HOME:-$JDK11_HOME}"
IT_JDK25="${JDK25_HOME:-$IT_JDK21}"

echo "==> AssertJ gates: Control Panel JDK select/autodetect persist after Apply"
cd itw-assertj-it
mvn -s "$MAVEN_SETTINGS" verify -Pvnc \
  -Dicedtea-web.version="$ITW_VERSION" \
  -Ditw.jdk17.home="$IT_JDK17" \
  -Ditw.jdk21.home="$IT_JDK21" \
  -Ditw.jdk25.home="$IT_JDK25" \
  -Dit.test='ControlPanelJvmAutodetectIT,ControlPanelJvmSelectionIT,DeploymentAutodetectJdksOnLoadIT'

echo "Critical JDK persistence gates passed."
