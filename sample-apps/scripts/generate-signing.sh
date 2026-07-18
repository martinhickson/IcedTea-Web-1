#!/usr/bin/env bash
set -euo pipefail

SAMPLE="${1:?sample id required (swing-gui or console)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLE_ROOT="$ROOT/$SAMPLE"
SIGN_DIR="$SAMPLE_ROOT/signing"
KS="$SIGN_DIR/$SAMPLE.jks"
CRT="$SIGN_DIR/$SAMPLE.crt"
ALIAS="$SAMPLE"
STORE_PASS="${ITW_SAMPLE_STORE_PASS:-changeit}"
DNAME="CN=IcedTea-Web Sample ${SAMPLE} Signer, OU=Development, O=IcedTea-Web, C=US"

mkdir -p "$SIGN_DIR"
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if [[ -f "$KS" && -f "$CRT" ]]; then
  if "$keytool" -list -alias "$ALIAS" -keystore "$KS" -storepass "$STORE_PASS" >/dev/null 2>&1; then
    exit 0
  fi
  echo "Keystore alias '$ALIAS' missing; regenerating $KS"
  rm -f "$KS" "$CRT"
fi

"$keytool" -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 8250 \
  -dname "$DNAME" -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS"
"$keytool" -exportcert -rfc -alias "$ALIAS" -keystore "$KS" -storepass "$STORE_PASS" -file "$CRT"
echo "Generated signing keystore: $KS"
