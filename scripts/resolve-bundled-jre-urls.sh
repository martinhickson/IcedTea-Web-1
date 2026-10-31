#!/bin/bash
# Write bundled JRE download URLs for one platform.
# vendor: temurin | bellsoft
# os: windows | linux | mac
# arch: x64 | aarch64 | osx-x64 | osx-arm64
# prefix: property prefix, e.g. itw.linux.temurin -> itw.linux.temurin11.url
# BellSoft URLs are the standard JRE (no JavaFX) builds that include the
# eMudhra trust-store fix. Apple Silicon falls back to x64 when aarch64
# is not published.
set -euo pipefail

vendor="${1:?vendor required}"
os="${2:?os required}"
arch_in="${3:?arch required}"
prefix="${4:?property prefix required}"
out="${5:?output properties file required}"

case "$arch_in" in
  osx-arm64|aarch64|arm64) arch="aarch64" ;;
  *) arch="x64" ;;
esac

case "$vendor" in
  temurin|bellsoft) ;;
  *) echo "Unknown JRE vendor: $vendor" >&2; exit 1 ;;
esac

http_ok() {
  local url="$1"
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 -A 'IcedTea-Web' "$url" || true)"
  case "$code" in
    200|301|302|303|307|308) return 0 ;;
    *) printf '%s' "${code:-000}"; return 1 ;;
  esac
}

temurin_url() {
  local version="$1"
  local use_arch="$2"
  printf 'https://api.adoptium.net/v3/binary/latest/%s/ga/%s/%s/jre/hotspot/normal/eclipse' \
    "$version" "$os" "$use_arch"
}

bellsoft_version() {
  case "$1" in
    11) printf '%s' '11.0.33.0.3+7' ;;
    17) printf '%s' '17.0.21.0.3+5' ;;
    21) printf '%s' '21.0.13.0.3+5' ;;
    25) printf '%s' '25.0.5.0.3+5' ;;
    *) echo "No BellSoft pin for $1" >&2; exit 1 ;;
  esac
}

bellsoft_os_arch() {
  local use_arch="$1"
  case "$os" in
    windows) printf '%s' "windows-amd64" ;;
    linux)
      if [ "$use_arch" = "aarch64" ]; then
        printf '%s' "linux-aarch64"
      else
        printf '%s' "linux-amd64"
      fi
      ;;
    mac)
      if [ "$use_arch" = "aarch64" ]; then
        printf '%s' "macos-aarch64"
      else
        printf '%s' "macos-amd64"
      fi
      ;;
    *) echo "Unknown os: $os" >&2; exit 1 ;;
  esac
}

bellsoft_ext() {
  if [ "$os" = "windows" ]; then
    printf '%s' "zip"
  else
    printf '%s' "tar.gz"
  fi
}

bellsoft_url() {
  local version="$1"
  local use_arch="$2"
  local ver osarch ext
  ver="$(bellsoft_version "$version")"
  osarch="$(bellsoft_os_arch "$use_arch")"
  ext="$(bellsoft_ext)"
  printf 'https://github.com/bell-sw/Liberica/releases/download/%s/bellsoft-jre%s-%s.%s' \
    "$ver" "$ver" "$osarch" "$ext"
}

resolve_one() {
  local version="$1"
  local use_arch="$arch"
  local url
  if [ "$vendor" = "bellsoft" ]; then
    url="$(bellsoft_url "$version" "$use_arch")"
    if [ "$arch" = "aarch64" ]; then
      local code
      if code="$(http_ok "$url")"; then
        echo "${os} ${arch} BellSoft ${version}: using ${use_arch}" >&2
      else
        use_arch="x64"
        url="$(bellsoft_url "$version" "$use_arch")"
        echo "${os} aarch64 BellSoft ${version}: HTTP ${code}, using x64" >&2
      fi
    else
      echo "${os} ${arch} BellSoft ${version}: ${url}" >&2
    fi
  else
    if [ "$os" = "mac" ] && [ "$arch" = "aarch64" ]; then
      url="$(temurin_url "$version" "aarch64")"
      local code
      if code="$(http_ok "$url")"; then
        echo "osx-arm64 Temurin ${version}: using mac/aarch64" >&2
      else
        use_arch="x64"
        url="$(temurin_url "$version" "x64")"
        echo "osx-arm64 Temurin ${version}: HTTP ${code}, using mac/x64" >&2
      fi
    else
      url="$(temurin_url "$version" "$use_arch")"
    fi
  fi
  printf '%s%s.url=%s\n' "$prefix" "$version" "$url"
}

{
  resolve_one 11
  resolve_one 17
  resolve_one 21
  resolve_one 25
} > "$out"
