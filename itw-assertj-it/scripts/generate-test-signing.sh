#!/usr/bin/env bash
# Create a self-signed code-signing keypair for itw-assertj-it sample JNLP apps.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIGN_DIR="$ROOT/target/test-signing"
KS="$SIGN_DIR/itw-test.jks"
CRT="$SIGN_DIR/itw-test.crt"
ALIAS="itw-it-test"
STORE_PASS="${ITW_TEST_STORE_PASS:-changeit}"

mkdir -p "$SIGN_DIR"

if [[ -f "$KS" && -f "$CRT" ]]; then
  exit 0
fi

SIGNER_DNAME="CN=IcedTea-Web IT Test Signer, OU=Development, O=IcedTea-Web, C=US"

if keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 8250 \
    -dname "$SIGNER_DNAME" \
    -ext "KeyUsage=critical,digitalSignature" \
    -ext "ExtendedKeyUsage=codeSigning" \
    -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS" 2>/dev/null; then
  :
else
  keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 8250 \
    -dname "$SIGNER_DNAME" \
    -keystore "$KS" -storepass "$STORE_PASS" -keypass "$STORE_PASS"
fi

keytool -exportcert -rfc -alias "$ALIAS" -keystore "$KS" -storepass "$STORE_PASS" -file "$CRT"
echo "Generated test signing keystore: $KS"
