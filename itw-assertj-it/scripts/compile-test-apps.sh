#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/target"
APPS="$ROOT/src/test/java/net/sourceforge/icedteaweb/it/apps"
RES="$ROOT/src/test/resources/jnlp-samples"
SIGN_DIR="$TARGET/test-signing"
KS="$SIGN_DIR/itw-test.jks"
ALIAS="itw-it-test"
STORE_PASS="${ITW_TEST_STORE_PASS:-changeit}"

mkdir -p "$TARGET/test-app-classes" "$TARGET/test-jnlp-samples"

chmod +x "$ROOT/scripts/generate-test-signing.sh"
"$ROOT/scripts/generate-test-signing.sh"

compile_sample() {
  local jdk_home="$1"
  local sample_dir="$2"
  local app_title="$3"
  shift 3
  local release=""
  if [[ "${1:-}" == --release ]]; then
    release="$2"
    shift 2
  fi
  local main_class="${@: -1}"
  local -a sources=("${@:1:$#-1}")
  local javac="$jdk_home/bin/javac"
  local jarsigner="$jdk_home/bin/jarsigner"
  if [[ ! -x "$javac" ]]; then
    echo "Skipping $sample_dir: $javac not found"
    return 0
  fi
  local classes="$TARGET/test-app-classes/$sample_dir"
  local out="$TARGET/test-jnlp-samples/$sample_dir"
  mkdir -p "$classes" "$out"
  if [[ -n "$release" ]]; then
    "$javac" --release "$release" -d "$classes" "${sources[@]}"
  else
    "$javac" -d "$classes" "${sources[@]}"
  fi
  local manifest="$classes/MANIFEST.MF"
  cat > "$manifest" <<EOF
Manifest-Version: 1.0
Main-Class: $main_class
Application-Name: $app_title
Permissions: all-permissions
Codebase: *
Application-Library-Allowable-Codebase: *

EOF
  jar cfm "$out/app.jar" "$manifest" -C "$classes" .
  if [[ -x "$jarsigner" ]]; then
    "$jarsigner" -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -digestalg SHA-256 -sigalg SHA256withRSA \
      "$out/app.jar" "$ALIAS"
  else
    jarsigner -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -digestalg SHA-256 -sigalg SHA256withRSA \
      "$out/app.jar" "$ALIAS"
  fi
  cp "$RES/$sample_dir/app.jnlp" "$out/app.jnlp"
  echo "Built and signed $out/app.jar with $jdk_home"
}

compile_sample "${ITW_JDK17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" java17-app \
  "ITW Java 17 bytecode app" \
  "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain"

compile_sample "${ITW_JDK21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" java18-app \
  "ITW Java 18 GUI Sample" \
  --release 18 \
  "$APPS/Java18GuiSampleJnlpMain.java" "$APPS/GuiSampleJnlpMain.java" "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.Java18GuiSampleJnlpMain"

JAVA18_PLUS_OUT="$TARGET/test-jnlp-samples/java18-plus-app"
mkdir -p "$JAVA18_PLUS_OUT"
if [[ -f "$TARGET/test-jnlp-samples/java18-app/app.jar" ]]; then
  cp "$TARGET/test-jnlp-samples/java18-app/app.jar" "$JAVA18_PLUS_OUT/app.jar"
  cp "$RES/java18-plus-app/app.jnlp" "$JAVA18_PLUS_OUT/app.jnlp"
  echo "Built $JAVA18_PLUS_OUT from java18-app jar"
fi

compile_sample "${ITW_JDK21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" java21-app \
  "ITW Java 21 GUI Sample" \
  "$APPS/Java21GuiSampleJnlpMain.java" "$APPS/GuiSampleJnlpMain.java" "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.Java21GuiSampleJnlpMain"

compile_sample "${ITW_JDK25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}" java25-app \
  "ITW Java 25 bytecode app" \
  "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain"

compile_sample "${ITW_JDK17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" gui-app \
  "ITW JDK Assignments GUI Sample" \
  "$APPS/GuiSampleJnlpMain.java" "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.GuiSampleJnlpMain"
