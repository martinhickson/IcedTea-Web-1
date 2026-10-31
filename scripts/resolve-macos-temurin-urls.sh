#!/bin/bash
# Write Adoptium Temurin JRE URLs for a macOS distribution.
# osx-x64 always uses mac/x64. osx-arm64 uses mac/aarch64 when that
# latest binary exists, and mac/x64 otherwise.
set -euo pipefail

rid="${1:?runtime identifier required}"
out="${2:?output properties file required}"

resolve() {
  local version="$1"
  local arch="x64"
  if [ "$rid" = "osx-arm64" ]; then
    local url="https://api.adoptium.net/v3/binary/latest/${version}/ga/mac/aarch64/jre/hotspot/normal/eclipse"
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 -A 'IcedTea-Web' "$url" || true)"
    case "$code" in
      200|301|302|303|307|308) arch="aarch64" ;;
      *) arch="x64" ;;
    esac
    echo "osx-arm64 Temurin ${version}: HTTP ${code:-000}, using mac/${arch}" >&2
  fi
  printf 'itw.mac.temurin%s.url=https://api.adoptium.net/v3/binary/latest/%s/ga/mac/%s/jre/hotspot/normal/eclipse\n' \
    "$version" "$version" "$arch"
}

{
  resolve 11
  resolve 17
  resolve 21
  resolve 25
} > "$out"
