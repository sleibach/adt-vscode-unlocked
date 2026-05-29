#!/usr/bin/env bash
#
# Restore the official extension files from the *.orig backups created by
# install.sh. Safe to run multiple times.
#
set -euo pipefail

echo "==> adt-vscode-unlocked uninstaller"

EXT=""
for base in "$HOME/.cursor/extensions" "$HOME/.vscode/extensions"; do
  cand="$(ls -d "$base"/sapse.adt-vscode-* 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$cand" ]; then EXT="$cand"; break; fi
done
if [ -z "$EXT" ]; then
  echo "ERROR: SAPSE.adt-vscode extension not found." >&2
  exit 1
fi

PLUGINS="$EXT/adt-ls/macosx/cocoa/aarch64/Adt-ls.app/Contents/Eclipse/plugins"
EXT_JS="$EXT/dist/_bundle/extension.js"
LS_JAR="$(ls "$PLUGINS"/com.sap.adt.ls_*.jar 2>/dev/null | grep -v '\.\(orig\|bak[0-9]*\)$' | head -1 || true)"

restored=0
if [ -n "$LS_JAR" ] && [ -f "$LS_JAR.orig" ]; then
  cp "$LS_JAR.orig" "$LS_JAR"; echo "    restored: $LS_JAR"; restored=1
fi
if [ -f "$EXT_JS.orig" ]; then
  cp "$EXT_JS.orig" "$EXT_JS"; echo "    restored: $EXT_JS"; restored=1
fi

if [ "$restored" -eq 0 ]; then
  echo "    nothing to restore (no *.orig backups found)."
else
  echo "==> Done. Reload the window to load the original files."
fi
