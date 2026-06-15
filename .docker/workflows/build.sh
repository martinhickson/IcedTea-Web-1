#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${1:-$SCRIPT_DIR/build.env}"
COMPOSE_FILE="$SCRIPT_DIR/build.compose"
DOCKER_BIN="${DOCKER_BIN:-docker}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Create or fill in build.env with git source and build settings." >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Build compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
  echo "Docker-compatible CLI not found: $DOCKER_BIN" >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is required on the host to clone source before running Docker compose." >&2
  exit 1
fi

declare -A ENV_MAP=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$line" == *"="* ]] || continue
  key="${line%%=*}"
  key="${key// /}"
  value="${line#*=}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  ENV_MAP["$key"]="$value"
done < "$ENV_FILE"

get_git_clone_url() {
  if [[ -n "${ENV_MAP[GIT_REMOTE_URL]:-}" ]]; then
    printf '%s' "${ENV_MAP[GIT_REMOTE_URL]}"
    return
  fi

  local repository="${ENV_MAP[GITHUB_REPOSITORY]:-martinhickson/IcedTea-Web-1}"
  printf 'https://github.com/%s.git' "$repository"
}

initialize_source_checkout() {
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

  cd "$clone_dir" >/dev/null
  printf '%s\n' "$(pwd)"
}

repo_root="$(initialize_source_checkout)"
export ITW_REPO_ROOT="$repo_root"

m2_repository="${ENV_MAP[ITW_M2_REPOSITORY]:-${HOME}/.m2/repository}"
mkdir -p "$m2_repository"
export ITW_M2_REPOSITORY="$m2_repository"

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

echo "Distribution build via Docker compose."
echo "Cloned source root: $ITW_REPO_ROOT"
echo "Compose file:       $COMPOSE_FILE"
echo "Maven repository:   $ITW_M2_REPOSITORY"

"$DOCKER_BIN" compose \
  -f "$COMPOSE_FILE" \
  --project-directory "$SCRIPT_DIR" \
  up --build --abort-on-container-exit --remove-orphans
