#!/usr/bin/env bash
# Ensure io.github.martinhickson:pack200 is in ~/.m2 with intrinsic.properties.
# Preferred source: Maven Central. Fallback: GitHub release jar.
set -euo pipefail

VERSION="${PACK200_VERSION:-11.0.4}"
GROUP_PATH="io/github/martinhickson/pack200"
RESOURCE_PATH="io/pack200/pack/intrinsic.properties"
MIN_BYTES=100000

repo_root="${HOME}/.m2/repository/${GROUP_PATH}/${VERSION}"
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
  echo "pack200 ${VERSION} jar present but missing ${RESOURCE_PATH}; re-bootstrapping."
  rm -f "$jar"
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
temp_jar="${temp_dir}/pack200-${VERSION}.jar"

fetch_from_central() {
  local url="https://repo1.maven.org/maven2/${GROUP_PATH}/${VERSION}/pack200-${VERSION}.jar"
  echo "Downloading pack200 ${VERSION} from Maven Central"
  curl -fsSL "$url" -o "$temp_jar" || return 1
  if ! pack200_jar_has_intrinsic "$temp_jar"; then
    echo "Maven Central pack200 ${VERSION} is missing ${RESOURCE_PATH}; falling back to release"
    return 1
  fi
  return 0
}

fetch_from_release() {
  local release_url="https://github.com/martinhickson/pack200/releases/download/pack200-${VERSION}/pack200-${VERSION}.jar"
  echo "Downloading pack200 ${VERSION} from ${release_url}"
  curl -fsSL "$release_url" -o "$temp_jar"
  if ! pack200_jar_has_intrinsic "$temp_jar"; then
    echo "ERROR: downloaded pack200 jar missing ${RESOURCE_PATH}: ${release_url}" >&2
    exit 1
  fi
}

if ! fetch_from_central && ! fetch_from_release; then
  echo "ERROR: unable to bootstrap pack200 ${VERSION}" >&2
  exit 1
fi

size="$(wc -c < "$temp_jar" | tr -d ' ')"
if [[ "$size" -lt "$MIN_BYTES" ]]; then
  echo "ERROR: downloaded pack200 jar too small (${size} bytes)" >&2
  exit 1
fi

mkdir -p "$repo_root"
mvn -q install:install-file \
  "-Dfile=${temp_jar}" \
  -DgroupId=io.github.martinhickson \
  -DartifactId=pack200 \
  "-Dversion=${VERSION}" \
  -Dpackaging=jar

if [[ ! -f "$jar" ]] || ! pack200_jar_has_intrinsic "$jar"; then
  echo "ERROR: pack200 ${VERSION} was not installed into ${repo_root} with ${RESOURCE_PATH}" >&2
  exit 1
fi

echo "pack200 ${VERSION} bootstrapped into ${repo_root}"
