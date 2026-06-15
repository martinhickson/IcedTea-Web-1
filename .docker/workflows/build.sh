#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_BIN="${DOCKER_BIN:-docker}"

declare -A ENV_MAP=()

load_env_map() {
  local env_file="$1"
  ENV_MAP=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *"="* ]] || continue
    local key="${line%%=*}"
    key="${key// /}"
    local value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    ENV_MAP["$key"]="$value"
  done < "$env_file"
}

get_git_clone_url() {
  if [[ -n "${ENV_MAP[GIT_REMOTE_URL]:-}" ]]; then
    printf '%s' "${ENV_MAP[GIT_REMOTE_URL]}"
    return
  fi

  local repository="${ENV_MAP[GITHUB_REPOSITORY]:-martinhickson/IcedTea-Web-1}"
  printf 'https://github.com/%s.git' "$repository"
}

clone_source_checkout() {
  local remote_url git_ref clone_dir clone_url token parent_dir

  remote_url="$(get_git_clone_url)"
  git_ref="${ENV_MAP[GIT_REF]:-1.8}"
  clone_dir="${ENV_MAP[GIT_CLONE_DIR]:-$SCRIPT_DIR/.source}"

  clone_url="$remote_url"
  if [[ -n "${ENV_MAP[GIT_TOKEN]:-}" && "$clone_url" == https://* ]]; then
    token="${ENV_MAP[GIT_TOKEN]}"
    clone_url="${clone_url/https:\/\//https://x-access-token:${token}@}"
  fi

  if [[ -d "$clone_dir" ]]; then
    rm -rf "$clone_dir"
  fi
  parent_dir="$(dirname "$clone_dir")"
  if [[ -n "$parent_dir" ]]; then
    mkdir -p "$parent_dir"
  fi

  echo "Cloning fresh source from $remote_url (ref: $git_ref)"
  echo "Clone directory: $clone_dir"

  git clone --depth 1 --single-branch --branch "$git_ref" "$clone_url" "$clone_dir"

  printf '%s\n' "$(cd "$clone_dir" && pwd)"
}

export_optional_build_vars() {
  if [[ ${#ENV_MAP[@]} -eq 0 ]]; then
    return
  fi
  if [[ -n "${ENV_MAP[ITW_DRY_RUN]:-}" ]]; then
    export ITW_DRY_RUN="${ENV_MAP[ITW_DRY_RUN]}"
  fi
  if [[ -n "${ENV_MAP[ITW_VERSION]:-}" ]]; then
    export ITW_VERSION="${ENV_MAP[ITW_VERSION]}"
  fi
  if [[ -n "${ENV_MAP[ITW_DOTNET_SELF_CONTAINED]:-}" ]]; then
    export ITW_DOTNET_SELF_CONTAINED="${ENV_MAP[ITW_DOTNET_SELF_CONTAINED]}"
  fi
  if [[ -n "${ENV_MAP[ITW_DOTNET_RUNTIME_IDENTIFIER]:-}" ]]; then
    export ITW_DOTNET_RUNTIME_IDENTIFIER="${ENV_MAP[ITW_DOTNET_RUNTIME_IDENTIFIER]}"
  fi
  if [[ -n "${ENV_MAP[ITW_CORRETTO_URL]:-}" ]]; then
    export ITW_CORRETTO_URL="${ENV_MAP[ITW_CORRETTO_URL]}"
  fi
}

prepare_m2_repository() {
  local m2_repository="${1:-${ITW_M2_REPOSITORY:-${HOME}/.m2/repository}}"
  mkdir -p "$m2_repository"
  export ITW_M2_REPOSITORY="$m2_repository"
}

set_workspace_root() {
  local root="$1"
  export ITW_WORKSPACE_ROOT="$root"
  export ITW_REPO_ROOT="$root"
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Required command not found: $name" >&2
    exit 1
  fi
}

is_dry_run() {
  case "${ITW_DRY_RUN:-false}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

run_dry_run_build_check() {
  local root version

  root="${ITW_WORKSPACE_ROOT:?ITW_WORKSPACE_ROOT is not set}"
  if [[ ! -d "$root" ]]; then
    echo "Workspace root not found: $root" >&2
    exit 1
  fi

  cd "$root"

  if [[ -z "${ITW_VERSION:-}" ]]; then
    ITW_VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)"
  fi
  version="$ITW_VERSION"
  if [[ -z "$version" ]]; then
    echo "Could not determine project version from pom.xml" >&2
    exit 1
  fi

  echo "=== Dry run mode ==="
  echo "Workspace:       $root"
  echo "Project version: $version"
  echo "Dry run: not building in dry run mode."
  echo "=== Dry run completed successfully ==="
}

export_jenkins_container_identity() {
  export JENKINS_UID="${JENKINS_UID:-$(id -u)}"
  export JENKINS_GID="${JENKINS_GID:-$(id -g)}"
}

run_distribution_compose() {
  local workflow_dir="$1"
  local compose_file="$workflow_dir/build.compose"

  if [[ -z "${ITW_WORKSPACE_ROOT:-}" ]]; then
    echo "ITW_WORKSPACE_ROOT is not set." >&2
    exit 1
  fi
  if [[ ! -d "$ITW_WORKSPACE_ROOT" ]]; then
    echo "Workspace root not found: $ITW_WORKSPACE_ROOT" >&2
    exit 1
  fi
  if [[ ! -f "$ITW_WORKSPACE_ROOT/pom.xml" ]]; then
    echo "Workspace missing pom.xml: $ITW_WORKSPACE_ROOT" >&2
    exit 1
  fi

  if is_dry_run; then
    run_dry_run_build_check
    return 0
  fi

  if [[ ! -f "$compose_file" ]]; then
    echo "Build compose file not found: $compose_file" >&2
    exit 1
  fi

  require_command "$DOCKER_BIN"
  export_jenkins_container_identity

  echo "Distribution build via Docker compose: $compose_file"
  echo "Workspace root:     $ITW_WORKSPACE_ROOT"
  echo "Maven repository:   ${ITW_M2_REPOSITORY:-/home/jenkins/.m2/repository}"
  echo "Container identity: jenkins uid=${JENKINS_UID} gid=${JENKINS_GID}"

  "$DOCKER_BIN" compose \
    --file "$compose_file" \
    --project-directory "$workflow_dir" \
    up --build --abort-on-container-exit --remove-orphans
}

run_container_distribution_build() {
  local root version self_contained rid corretto_url

  root="${ITW_WORKSPACE_ROOT:?ITW_WORKSPACE_ROOT is not set}"
  if [[ ! -d "$root" ]]; then
    echo "Workspace root not found: $root" >&2
    exit 1
  fi

  cd "$root"

  if is_dry_run; then
    run_dry_run_build_check
    return 0
  fi

  if [[ -z "${ITW_VERSION:-}" ]]; then
    ITW_VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)"
  fi
  version="$ITW_VERSION"
  if [[ -z "$version" ]]; then
    echo "Could not determine project version from pom.xml" >&2
    exit 1
  fi

  self_contained="${ITW_DOTNET_SELF_CONTAINED:-true}"
  rid="${ITW_DOTNET_RUNTIME_IDENTIFIER:-linux-x64}"

  case "$rid" in
    linux-x64)
      corretto_url="${ITW_CORRETTO_URL:-https://corretto.aws/downloads/latest/amazon-corretto-11-x64-linux-jdk.tar.gz}"
      ;;
    win-x64)
      corretto_url="${ITW_CORRETTO_URL:-https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip}"
      ;;
    osx-x64)
      corretto_url="${ITW_CORRETTO_URL:-https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz}"
      ;;
    osx-arm64)
      corretto_url="${ITW_CORRETTO_URL:-https://corretto.aws/downloads/latest/amazon-corretto-11-aarch64-macos-jdk.tar.gz}"
      ;;
    *)
      echo "Unsupported runtime identifier: $rid" >&2
      exit 1
      ;;
  esac

  echo "=== IcedTea-Web distribution build ==="
  echo "Workspace:       $root"
  echo "Version:         $version"
  echo "JDK 11:          ${JAVA_HOME:?JAVA_HOME is not set}"
  echo "Runtime ID:      $rid"
  echo "Self-contained:  $self_contained"
  echo "Maven repo:      /home/jenkins/.m2/repository"

  local mvn_settings=()
  if [[ -n "${ITW_MAVEN_SETTINGS:-}" ]]; then
    mvn_settings=(-s "$ITW_MAVEN_SETTINGS")
  fi

  mvn "${mvn_settings[@]}" -P maven-distribution \
    -pl icedtea-web-distribution \
    -am \
    install \
    -Dmaven.test.skip=true \
    -DskipTests \
    -Djdk11.home="$JAVA_HOME" \
    -Ditw.dotnet.selfContained="$self_contained" \
    -Ditw.dotnet.runtime.identifier="$rid" \
    -Ditw.corretto.url="$corretto_url"

  echo "=== Distribution build completed successfully ==="
}

run_host_controller() {
  local env_file="${1:-$SCRIPT_DIR/build.env}"

  if [[ ! -f "$env_file" ]]; then
    echo "Missing $env_file. Create or fill in build.env with git source and build settings." >&2
    exit 1
  fi

  require_command git
  load_env_map "$env_file"
  set_workspace_root "$(clone_source_checkout)"
  prepare_m2_repository "${ENV_MAP[ITW_M2_REPOSITORY]:-${HOME}/.m2/repository}"
  export_optional_build_vars
  run_distribution_compose "$SCRIPT_DIR"
}

run_jenkins_controller() {
  local workflow_dir="$1"

  if [[ -z "${WORKSPACE:-}" ]]; then
    echo "WORKSPACE is not set." >&2
    exit 1
  fi

  set_workspace_root "$WORKSPACE"
  prepare_m2_repository "${ITW_M2_REPOSITORY:-/home/jenkins/.m2/repository}"
  run_distribution_compose "$workflow_dir"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  run_host_controller "$@"
fi
