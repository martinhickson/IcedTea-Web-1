#!/usr/bin/env bash
# Seed deployment.properties with autodetected JDKs and file:// JNLP assignments
# for all itw-assertj-it sample apps (interactive control panel testing).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ITW_ASSERTJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ITW_TEST_HOME="${1:?usage: seed-interactive-home.sh ITW_TEST_HOME}"

"$SCRIPT_DIR/install-test-trust-cert.sh" "$ITW_TEST_HOME"

PROPS="$ITW_TEST_HOME/.config/icedtea-web/deployment.properties"
JNLP_BUILT_ROOT="$ITW_ASSERTJ_DIR/target/test-jnlp-samples"
JNLP_RESOURCE_ROOT="$ITW_ASSERTJ_DIR/src/test/resources/jnlp-samples"

mkdir -p "$(dirname "$PROPS")"

file_url() {
  local path
  path="$(readlink -f "$1")"
  echo "file://${path}"
}

jdk_major() {
  local java_bin="$1/bin/java"
  if [[ ! -x "$java_bin" ]]; then
    echo 0
    return
  fi
  local version
  version="$("$java_bin" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  if [[ "$version" == 1.* ]]; then
    echo "${version#1.}" | cut -d. -f1
  else
    echo "${version%%.*}"
  fi
}

declare -a JDK_HOMES=()
declare -a JDK_MAJORS=()

add_jdk_home() {
  local home="$1"
  [[ -d "$home" ]] || return 0
  home="$(readlink -f "$home")"
  local major
  major="$(jdk_major "$home")"
  [[ "$major" -ge 8 ]] || return 0
  local i
  for i in "${!JDK_HOMES[@]}"; do
    if [[ "${JDK_HOMES[$i]}" == "$home" ]]; then
      return 0
    fi
  done
  JDK_HOMES+=("$home")
  JDK_MAJORS+=("$major")
}

for candidate in \
  "${ITW_JDK17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" \
  "${ITW_JDK21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" \
  "${ITW_JDK25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}" \
  /usr/lib/jvm/*; do
  add_jdk_home "$candidate"
done

if [[ ${#JDK_HOMES[@]} -eq 0 ]]; then
  echo "No valid JDK homes found; skipping deployment.properties seed." >&2
  exit 0
fi

find_jdk_index() {
  local want_major="$1"
  local min_ok="${2:-0}"
  local best_index=-1
  local best_major=0
  local i
  for i in "${!JDK_HOMES[@]}"; do
    local major="${JDK_MAJORS[$i]}"
    if [[ "$min_ok" -eq 1 ]]; then
      if [[ "$major" -ge "$want_major" ]] && { [[ "$best_index" -lt 0 ]] || [[ "$major" -lt "$best_major" ]]; }; then
        best_index=$((i + 1))
        best_major="$major"
      fi
    elif [[ "$major" -eq "$want_major" ]]; then
      echo $((i + 1))
      return
    fi
  done
  if [[ "$best_index" -ge 1 ]]; then
    echo "$best_index"
    return
  fi
  echo 1
}

{
  echo "# Seeded by itw-assertj-it/scripts/seed-interactive-home.sh"
  idx=1
  for home in "${JDK_HOMES[@]}"; do
    echo "deployment.jdk.$idx=$home"
    idx=$((idx + 1))
  done
  echo "deployment.jdk.matchStrategy=MAXIMUM"

  declare -A NEXT_ASSIGNMENT=()

  add_assignment() {
    local sample="$1"
    local want_major="$2"
    local min_ok="${3:-0}"
    local jnlp_path=""

    if [[ -f "$JNLP_BUILT_ROOT/$sample/app.jnlp" ]]; then
      jnlp_path="$JNLP_BUILT_ROOT/$sample/app.jnlp"
    elif [[ -f "$JNLP_RESOURCE_ROOT/$sample/app.jnlp" ]]; then
      jnlp_path="$JNLP_RESOURCE_ROOT/$sample/app.jnlp"
    else
      return 0
    fi

    local jdk_index
    jdk_index="$(find_jdk_index "$want_major" "$min_ok")"
    local slot="${NEXT_ASSIGNMENT[$jdk_index]:-0}"
    slot=$((slot + 1))
    NEXT_ASSIGNMENT[$jdk_index]="$slot"
    echo "deployment.jdk${jdk_index}.assignment${slot}=$(file_url "$jnlp_path")"
  }

  add_assignment java17-app 17
  add_assignment java21-app 21
  add_assignment java25-app 25

  echo "deployment.log=true"
  echo "deployment.log.file=true"
} > "$PROPS"

echo "Seeded $PROPS with ${#JDK_HOMES[@]} JDK(s) and sample JNLP assignments."
