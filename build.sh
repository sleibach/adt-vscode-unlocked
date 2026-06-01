#!/usr/bin/env bash
#
# Rebuild tools/adt-unlock.jar from src/. Requires a JDK 21+ (javac) and the
# SAPSE.adt-vscode extension installed (its plugin jars are the compile
# classpath). End users do NOT need this — the jar is committed.
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v javac >/dev/null 2>&1 || { echo "ERROR: javac (JDK 21+) not found." >&2; exit 1; }

EXT=""
for base in "$HOME/.cursor/extensions" "$HOME/.vscode/extensions"; do
  cand="$(ls -d "$base"/sapse.adt-vscode-* 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$cand" ]; then EXT="$cand"; break; fi
done
[ -n "$EXT" ] || { echo "ERROR: SAPSE.adt-vscode extension not found (needed for classpath)." >&2; exit 1; }

PLUGINS="$EXT/adt-ls/macosx/cocoa/aarch64/Adt-ls.app/Contents/Eclipse/plugins"

rm -rf "$HERE/out"; mkdir -p "$HERE/out" "$HERE/tools"
javac --release 21 -cp "$PLUGINS/*" -d "$HERE/out" \
  $(find "$HERE/src" -name '*.java')
( cd "$HERE/out" && jar cfe "$HERE/tools/adt-unlock.jar" AdtUnlock \
    $(find . -name '*.class' | sed 's|^\./||') )
echo "built: $HERE/tools/adt-unlock.jar"
