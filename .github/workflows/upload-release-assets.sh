#!/usr/bin/env bash
# Upload GitHub release assets in a stable, platform-prioritized order after all
# parallel distribution matrix jobs have finished. Checksum sidecars are uploaded
# last in the same order (GitHub also exposes native SHA-256 digests on releases).
set -euo pipefail

RELEASE_TAG="${1:?release tag required}"
ASSETS_DIR="${2:-release-assets}"
INCLUDE_LINUX_ZIP="${ITW_INCLUDE_LINUX_ZIP:-true}"
INCLUDE_WINDOWS_ZIP="${ITW_INCLUDE_WINDOWS_ZIP:-false}"

if [[ ! -d "$ASSETS_DIR" ]]; then
  echo "Assets directory not found: $ASSETS_DIR" >&2
  exit 1
fi

is_truthy() {
  case "${1,,}" in
    1 | true | yes | on) return 0 ;;
    *) return 1 ;;
  esac
}

ordered=()

add_glob() {
  local pattern="$1"
  local matches=()
  mapfile -t matches < <(find "$ASSETS_DIR" -type f -name "$pattern" | sort)
  if (("${#matches[@]}")); then
    ordered+=( "${matches[@]}" )
  fi
}

artifact_patterns=(
  '*-win-x64.msi'
)

if is_truthy "$INCLUDE_WINDOWS_ZIP"; then
  artifact_patterns+=('*-win-x64.zip')
fi

artifact_patterns+=(
  '*.rpm'
  '*.deb'
)

if is_truthy "$INCLUDE_LINUX_ZIP"; then
  artifact_patterns+=('*-linux-x64.zip')
fi

artifact_patterns+=(
  '*-osx-x64.zip'
  '*-osx-x64.dmg'
  '*-osx-arm64.zip'
  '*-osx-arm64.dmg'
)

checksum_patterns=(
  '*-win-x64.msi.sha256.txt'
)

if is_truthy "$INCLUDE_WINDOWS_ZIP"; then
  checksum_patterns+=('*-win-x64.zip.sha256.txt')
fi

checksum_patterns+=(
  '*.rpm.sha256.txt'
  '*.deb.sha256.txt'
)

if is_truthy "$INCLUDE_LINUX_ZIP"; then
  checksum_patterns+=('*-linux-x64.zip.sha256.txt')
fi

checksum_patterns+=(
  '*-osx-x64.zip.sha256.txt'
  '*-osx-x64.dmg.sha256.txt'
  '*-osx-arm64.zip.sha256.txt'
  '*-osx-arm64.dmg.sha256.txt'
)

echo "Include Linux ZIP:   $INCLUDE_LINUX_ZIP"
echo "Include Windows ZIP: $INCLUDE_WINDOWS_ZIP"

for pattern in "${artifact_patterns[@]}"; do
  add_glob "$pattern"
done

for pattern in "${checksum_patterns[@]}"; do
  add_glob "$pattern"
done

if (("${#ordered[@]}" == 0)); then
  echo "No release assets found under $ASSETS_DIR" >&2
  exit 1
fi

echo "Uploading ${#ordered[@]} release assets to ${RELEASE_TAG}:"
printf '  %s\n' "${ordered[@]}"

gh release upload "$RELEASE_TAG" "${ordered[@]}" --clobber
