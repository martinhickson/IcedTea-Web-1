#!/usr/bin/env bash
# Prepare bash javaws + Windows javaws.cmd for Failsafe ProcessBuilder launches.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="${1:-$ROOT/target/bin}"
UBER_JAR="${2:?uber jar path required}"
MAIN_CLASS="${3:-net.sourceforge.jnlp.runtime.JavawsUberLauncher}"

mkdir -p "$BIN_DIR"
BB_SRC="$ROOT/../icedtea-web/target/bin/byte-buddy-agent.jar"
cp -f "$BB_SRC" "$BIN_DIR/byte-buddy-agent.jar"

# Prefer POSIX paths so the bash wrapper works under Git Bash / MSYS.
to_posix() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$p"
  else
    echo "$p" | sed -e 's#\\#/#g' -e 's#^\([A-Za-z]\):#/\L\1#'
  fi
}

UBER_POSIX="$(to_posix "$UBER_JAR")"
BB_POSIX="$(to_posix "$BIN_DIR/byte-buddy-agent.jar")"

sed -e "s|@ITW_UBER_JAR@|${UBER_POSIX}|g" \
    -e "s|@BYTEBUDDY_AGENT_JAR@|${BB_POSIX}|g" \
    -e "s|@MAIN_CLASS@|${MAIN_CLASS}|g" \
    "$ROOT/../scripts/javaws.sh" > "$BIN_DIR/javaws"
chmod +x "$BIN_DIR/javaws"

cat > "$BIN_DIR/javaws.cmd" <<'EOF'
@echo off
setlocal
bash "%~dp0javaws" %*
EOF

echo "Prepared $BIN_DIR/javaws and $BIN_DIR/javaws.cmd"
