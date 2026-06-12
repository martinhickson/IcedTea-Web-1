#!/usr/bin/env bash
# Import the itw-assertj-it test signer into IcedTea-Web user trusted CA/cert stores.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ITW_ASSERTJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="${1:?usage: install-test-trust-cert.sh ITW_TEST_HOME}"

CRT="$ITW_ASSERTJ_DIR/target/test-signing/itw-test.crt"
ALIAS="itw-it-test-signer"
STORE_PASS="${ITW_TRUST_STORE_PASS:-changeit}"
SECURITY_DIR="$ITW_TEST_HOME/.config/icedtea-web/security"
TRUSTED_CA="$SECURITY_DIR/trusted.cacerts"
TRUSTED_CERTS="$SECURITY_DIR/trusted.certs"

if [[ ! -f "$CRT" ]]; then
  echo "Missing test certificate $CRT (run compile-test-apps.sh first)" >&2
  exit 1
fi

mkdir -p "$SECURITY_DIR"

import_into_store() {
  local store="$1"
  if [[ ! -f "$store" ]]; then
    keytool -genkeypair -alias bootstrap -dname "CN=bootstrap" -validity 1 \
      -keystore "$store" -storepass "$STORE_PASS" -keypass "$STORE_PASS" >/dev/null 2>&1 || true
    if [[ -f "$store" ]]; then
      keytool -delete -alias bootstrap -keystore "$store" -storepass "$STORE_PASS" >/dev/null 2>&1 || true
    fi
  fi
  if keytool -list -alias "$ALIAS" -keystore "$store" -storepass "$STORE_PASS" >/dev/null 2>&1; then
    keytool -delete -alias "$ALIAS" -keystore "$store" -storepass "$STORE_PASS" >/dev/null 2>&1 || true
  fi
  keytool -importcert -noprompt -alias "$ALIAS" -file "$CRT" \
    -keystore "$store" -storepass "$STORE_PASS"
}

import_into_store "$TRUSTED_CA"
import_into_store "$TRUSTED_CERTS"
echo "Installed test signer into $TRUSTED_CA and $TRUSTED_CERTS"
