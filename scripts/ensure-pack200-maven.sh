#!/usr/bin/env bash
# Ensure a WORKING io.pack200:pack200 artifact is in ~/.m2 (the GitHub Packages
# 11.0.2 jar is known-broken: it lacks io/pack200/pack/intrinsic.properties, so
# UnpackerImpl throws at runtime and pack.gz downloads never work).
#
# Preferred source: GitHub Packages (maven.pkg.github.com/martinhickson/pack200)
# via the configured Maven settings (MAVEN_SETTINGS). Fallback: the GitHub
# release jar (martinhickson/pack200 releases), which is the jar that works in
# production. Either way the artifact is verified to contain the intrinsic
# resource before it is installed.
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
  echo "pack200 ${VERSION} jar present but missing ${RESOURCE_PATH}; re-bootstrapping."
  rm -f "$jar"
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
temp_jar="${temp_dir}/pack200-${VERSION}.jar"

fetch_from_github_packages() {
  local settings="${MAVEN_SETTINGS:-}"
  if [[ -z "$settings" || ! -f "$settings" ]]; then
    echo "no MAVEN_SETTINGS — skipping GitHub Packages source"
    return 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn not available — skipping GitHub Packages source"
    return 1
  fi
  echo "Fetching pack200 ${VERSION} from GitHub Packages (maven.pkg.github.com)"
  mvn -s "$settings" -q dependency:get \
    -Dartifact="io.pack200:pack200:${VERSION}" \
    -Dtransitive=false || return 1
  local repo_jar="${HOME}/.m2/repository/io/pack200/pack200/${VERSION}/pack200-${VERSION}.jar"
  [[ -f "$repo_jar" ]] || return 1
  cp -f "$repo_jar" "$temp_jar"
  if ! pack200_jar_has_intrinsic "$temp_jar"; then
    echo "GitHub Packages pack200 ${VERSION} is broken (missing ${RESOURCE_PATH}); falling back to release"
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

if ! fetch_from_github_packages && ! fetch_from_release; then
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
  -DgroupId=io.pack200 \
  -DartifactId=pack200 \
  "-Dversion=${VERSION}" \
  -Dpackaging=jar

if [[ ! -f "$jar" ]] || ! pack200_jar_has_intrinsic "$jar"; then
  echo "ERROR: pack200 ${VERSION} was not installed into ${repo_root} with ${RESOURCE_PATH}" >&2
  exit 1
fi

echo "pack200 ${VERSION} bootstrapped into ${repo_root}"
