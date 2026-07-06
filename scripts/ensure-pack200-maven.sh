#!/usr/bin/env bash
# Install martinhickson/pack200 GitHub release into ~/.m2 when the resolved artifact
# is missing or lacks io/pack200/pack/intrinsic.properties (required at runtime on JDK 17+).
set -euo pipefail

VERSION="${PACK200_VERSION:-11.0.2}"
RESOURCE_PATH="io/pack200/pack/intrinsic.properties"
MIN_BYTES=100000

repo_root="${HOME}/.m2/repository/io/pack200/pack200/${VERSION}"
jar="${repo_root}/pack200-${VERSION}.jar"

pack200_jar_has_intrinsic() {
  local file="$1"
  jar tf "$file" | grep -Fx "$RESOURCE_PATH" >/dev/null
}

if [[ -f "$jar" ]] && pack200_jar_has_intrinsic "$jar"; then
  echo "pack200 ${VERSION} already in local Maven repository with ${RESOURCE_PATH}; skipping bootstrap."
  exit 0
fi

if [[ -f "$jar" ]]; then
  echo "pack200 ${VERSION} jar present but missing ${RESOURCE_PATH}; re-bootstrapping from GitHub release."
  rm -f "$jar"
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
temp_jar="${temp_dir}/pack200-${VERSION}.jar"

release_url="https://github.com/martinhickson/pack200/releases/download/pack200-${VERSION}/pack200-${VERSION}.jar"
echo "Downloading pack200 ${VERSION} from ${release_url}"
curl -fsSL "$release_url" -o "$temp_jar"

size="$(wc -c < "$temp_jar" | tr -d ' ')"
if [[ "$size" -lt "$MIN_BYTES" ]]; then
  echo "ERROR: downloaded pack200 jar too small (${size} bytes): ${release_url}" >&2
  exit 1
fi

if ! pack200_jar_has_intrinsic "$temp_jar"; then
  echo "ERROR: downloaded pack200 jar missing ${RESOURCE_PATH}: ${release_url}" >&2
  exit 1
fi

mkdir -p "$repo_root"
mvn -q install:install-file \
  "-Dfile=${temp_jar}" \
  -DgroupId=io.pack200 \
  -DartifactId=pack200 \
  "-Dversion=${VERSION}" \
  -Dpackaging=jar

if [[ ! -f "$jar" ]] || ! pack200_jar_has_intrinsic "$jar"; then
  echo "ERROR: pack200 ${VERSION} was not installed into ${repo_root} with ${RESOURCE_PATH}" >&2
  exit 1
fi

echo "pack200 ${VERSION} bootstrapped into ${repo_root}"
