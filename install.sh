#!/usr/bin/env bash
#
# adt-vscode-unlocked — enable RFC basic-auth logon in the official
# "ABAP Development Tools" (SAPSE.adt-vscode) extension on Cursor / VS Code.
#
# Scope: macOS, Apple Silicon (arm64). Patches your locally installed
# extension in place. Originals are backed up to *.orig next to each file.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> adt-vscode-unlocked installer (macOS / Apple Silicon)"

# --- Locate the installed extension (Cursor first, then VS Code) -------------
EXT=""
for base in "$HOME/.cursor/extensions" "$HOME/.vscode/extensions"; do
  cand="$(ls -d "$base"/sapse.adt-vscode-* 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$cand" ]; then EXT="$cand"; break; fi
done
if [ -z "$EXT" ]; then
  echo "ERROR: SAPSE.adt-vscode extension not found under ~/.cursor or ~/.vscode." >&2
  echo "       Install 'ABAP Development Tools' first, then re-run." >&2
  exit 1
fi
echo "    extension: $EXT"

PLUGINS="$EXT/adt-ls/macosx/cocoa/aarch64/Adt-ls.app/Contents/Eclipse/plugins"
EXT_JS="$EXT/dist/_bundle/extension.js"

LS_JAR="$(ls "$PLUGINS"/com.sap.adt.ls_*.jar 2>/dev/null | grep -v '\.\(orig\|bak[0-9]*\)$' | head -1 || true)"
if [ -z "$LS_JAR" ] || [ ! -f "$LS_JAR" ]; then
  echo "ERROR: language-server jar (com.sap.adt.ls_*.jar) not found." >&2
  echo "       Expected under: $PLUGINS" >&2
  echo "       (This build targets macOS Apple Silicon only.)" >&2
  exit 1
fi
if [ ! -f "$EXT_JS" ]; then
  echo "ERROR: extension bundle not found: $EXT_JS" >&2
  exit 1
fi

JAVA="$(ls "$EXT"/adt-ls/macosx/cocoa/aarch64/Adt-ls.app/Contents/Eclipse/plugins/com.sap.adt.jvm.*/jre/bin/java 2>/dev/null | head -1 || true)"
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "ERROR: bundled Java runtime not found inside the extension." >&2
  exit 1
fi

ASM="$(ls "$PLUGINS"/org.objectweb.asm_*.jar | head -1)"
ASM_TREE="$(ls "$PLUGINS"/org.objectweb.asm.tree_*.jar | head -1)"
if [ -z "$ASM" ] || [ -z "$ASM_TREE" ]; then
  echo "ERROR: bundled ASM libraries not found in plugins." >&2
  exit 1
fi

# --- Back up originals (only once) -------------------------------------------
[ -f "$LS_JAR.orig" ] || cp "$LS_JAR" "$LS_JAR.orig"
[ -f "$EXT_JS.orig" ] || cp "$EXT_JS" "$EXT_JS.orig"
echo "    backups:   $LS_JAR.orig"
echo "               $EXT_JS.orig"

# --- 1) Client: register the password prompt handler -------------------------
python3 "$HERE/scripts/patch_extension_js.py" "$EXT_JS"

# --- 2+3) Server: show non-SSO RFC systems + wire basic-auth logon -----------
"$JAVA" -cp "$HERE/tools/adt-unlock.jar:$ASM:$ASM_TREE" AdtUnlock "$LS_JAR"

echo ""
echo "==> Done. Reload the window (Command Palette: 'Developer: Reload Window')."
echo "    Then: Open Objects -> pick your RFC destination -> enter your password."
echo "    Restore anytime with ./uninstall.sh"
