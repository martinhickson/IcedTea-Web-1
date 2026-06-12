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
  local source="$4"
  local main_class="$5"
  local javac="$jdk_home/bin/javac"
  local jarsigner="$jdk_home/bin/jarsigner"
  if [[ ! -x "$javac" ]]; then
    echo "Skipping $sample_dir: $javac not found"
    return 0
  fi
  local classes="$TARGET/test-app-classes/$sample_dir"
  local out="$TARGET/test-jnlp-samples/$sample_dir"
  mkdir -p "$classes" "$out"
  "$javac" -d "$classes" "$source"
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

compile_sample "${ITW_JDK21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" java21-app \
  "ITW Java 21 bytecode app" \
  "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain"

compile_sample "${ITW_JDK25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}" java25-app \
  "ITW Java 25 bytecode app" \
  "$APPS/HeadlessHoldJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain"

compile_sample "${ITW_JDK17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" gui-app \
  "ITW JDK Assignments GUI Sample" \
  "$APPS/GuiSampleJnlpMain.java" \
  "net.sourceforge.icedteaweb.it.apps.GuiSampleJnlpMain"
