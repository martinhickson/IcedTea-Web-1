#!/usr/bin/env bash
set -euo pipefail

CHANGESETS="${1:?changeset count required}"

OUT_DIR="target/generated-resources/net/sourceforge/jnlp/resources"
WORK_DIR="target/html-gen-work"

mkdir -p "$OUT_DIR" "$WORK_DIR/html-gen"
cp ../AUTHORS ../NEWS ../COPYING ../ChangeLog "$WORK_DIR/html-gen/"
(cd "$WORK_DIR" && bash ../../../html-gen.sh "$CHANGESETS")
cp "$WORK_DIR/html-gen"/*.html "$OUT_DIR/"
