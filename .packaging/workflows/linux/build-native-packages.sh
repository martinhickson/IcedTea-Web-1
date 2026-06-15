#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUTPUT_DIR="${ITW_NATIVE_OUTPUT_DIR:-$ROOT_DIR/icedtea-web-distribution/target/native-packages}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
PACKAGE_FORMATS="${ITW_PACKAGE_FORMATS:-deb rpm}"
IMAGE_PREFIX="${ITW_PACKAGE_IMAGE_PREFIX:-icedtea-web-native}"
read -r -a DOCKER_CMD <<< "$DOCKER_BIN"

if ! command -v "${DOCKER_CMD[0]}" >/dev/null 2>&1; then
  echo "Docker-compatible CLI not found: $DOCKER_BIN" >&2
  echo "Set DOCKER_BIN=podman if you want to use Podman." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

run_builder() {
  local format="$1"
  local image="${IMAGE_PREFIX}-${format}:local"
  local dockerfile="$ROOT_DIR/.packaging/workflows/linux/docker/${format}.Dockerfile"

  if [[ ! -f "$dockerfile" ]]; then
    echo "Unsupported native package format: $format" >&2
    exit 1
  fi

  local attempt=1
  local max_attempts=3
  while (( attempt <= max_attempts )); do
    if "${DOCKER_CMD[@]}" build \
      --file "$dockerfile" \
      --tag "$image" \
      "$ROOT_DIR/.packaging/workflows/linux/docker"; then
      break
    fi
    if (( attempt == max_attempts )); then
      echo "Docker image build failed after ${max_attempts} attempts: $image" >&2
      exit 1
    fi
    echo "Docker image build failed (attempt ${attempt}/${max_attempts}); retrying in 30s..." >&2
    sleep 30
    attempt=$((attempt + 1))
  done

  "${DOCKER_CMD[@]}" run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$ROOT_DIR:/workspace:rw" \
    --workdir /workspace \
    --env ITW_VERSION="${ITW_VERSION:-}" \
    --env ITW_DIST_DIR="${ITW_DIST_DIR:-}" \
    --env ITW_NATIVE_OUTPUT_DIR="${ITW_NATIVE_OUTPUT_DIR:-}" \
    --env ITW_PACKAGE_NAME="${ITW_PACKAGE_NAME:-}" \
    --env ITW_PACKAGE_MAINTAINER="${ITW_PACKAGE_MAINTAINER:-}" \
    --env ITW_PACKAGE_DESCRIPTION="${ITW_PACKAGE_DESCRIPTION:-}" \
    --env ITW_DEB_ARCH="${ITW_DEB_ARCH:-}" \
    --env ITW_RPM_ARCH="${ITW_RPM_ARCH:-}" \
    "$image" \
    "/workspace/.packaging/workflows/linux/container-build-package.sh" "$format"
}

for format in $PACKAGE_FORMATS; do
  run_builder "$format"
done
