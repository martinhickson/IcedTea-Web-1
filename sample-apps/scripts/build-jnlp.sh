#!/usr/bin/env bash
set -euo pipefail

SAMPLE="${1:?sample id required (swing-gui or console)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLE_ROOT="$ROOT/$SAMPLE"
JAVA_SRC="$SAMPLE_ROOT/java/src"
OUT_DIR="$ROOT/public/jnlp/$SAMPLE"
MANIFEST="$SAMPLE_ROOT/java/META-INF/MANIFEST.MF"
KS="$SAMPLE_ROOT/signing/$SAMPLE.jks"
ALIAS="$SAMPLE"
STORE_PASS="${ITW_SAMPLE_STORE_PASS:-changeit}"
PORT="${ITW_SAMPLE_PORT:-4200}"
CODEBASE="http://127.0.0.1:${PORT}/jnlp/${SAMPLE}/"

resolve_jdk17() {
  if [[ -n "${JDK17_HOME:-}" && -x "${JDK17_HOME}/bin/javac" ]]; then
    echo "$JDK17_HOME"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "17'; then
      echo "$JAVA_HOME"
      return
    fi
  fi
  for candidate in /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17*; do
    if [[ -x "${candidate}/bin/javac" ]]; then
      echo "$candidate"
      return
    fi
  done
  echo "JDK 17 not found. Set JDK17_HOME." >&2
  exit 1
}

chmod +x "$ROOT/scripts/generate-signing.sh"
"$ROOT/scripts/generate-signing.sh" "$SAMPLE"

JDK_HOME="$(resolve_jdk17)"
BUILD_DIR="$SAMPLE_ROOT/java/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$OUT_DIR"

echo "Compiling $SAMPLE with $JDK_HOME"
find "$JAVA_SRC" -name '*.java' -print0 | xargs -0 "${JDK_HOME}/bin/javac" --release 17 -encoding UTF-8 -d "$BUILD_DIR"
"${JDK_HOME}/bin/jar" --create --file "$OUT_DIR/app.jar" --manifest "$MANIFEST" -C "$BUILD_DIR" .

echo "Signing ${OUT_DIR}/app.jar"
"${JDK_HOME}/bin/jarsigner" -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
  -digestalg SHA-256 -sigalg SHA256withRSA \
  "${OUT_DIR}/app.jar" "$ALIAS"

export ITW_SAMPLE="$SAMPLE"
node "$ROOT/scripts/write-jnlp.mjs" "$SAMPLE"

echo "Built and signed ${OUT_DIR}/app.jar"
echo "Launch URL: ${CODEBASE}app.jnlp"
