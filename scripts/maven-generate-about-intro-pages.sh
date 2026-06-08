#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?project version required}"

OUT_DIR="target/generated-resources/net/sourceforge/jnlp/resources"
mkdir -p "$OUT_DIR"

case "$(uname -s 2>/dev/null || echo)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac

TP_CLASSPATH="target/classes"
for JAR in target/dependency-jars/*.jar; do
  [ -f "$JAR" ] || continue
  TP_CLASSPATH="${TP_CLASSPATH}${CP_SEP}${JAR}"
done

for LANG_ID in en_US.UTF-8 cs_CZ.UTF-8 pl_PL.UTF-8 de_DE.UTF-8; do
  ID="${LANG_ID%%_*}"
  LANG="$LANG_ID" java -cp "$TP_CLASSPATH" net.sourceforge.jnlp.util.docprovider.TextsProvider \
    htmlIntro "$OUT_DIR/about_${ID}.html" false "$VERSION"
done
