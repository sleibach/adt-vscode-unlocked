#!/usr/bin/env pwsh
#
# adt-vscode-unlocked installer for Windows.
# Patches your locally installed SAPSE.adt-vscode extension in place and keeps
# .orig backups next to the modified files.
#
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host '==> adt-vscode-unlocked installer (Windows)'

function Get-ExtensionRoot {
  $bases = @(
    Join-Path $HOME '.cursor\extensions'
    Join-Path $HOME '.vscode\extensions'
  )

  foreach ($base in $bases) {
    if (-not (Test-Path $base)) {
      continue
    }

    $candidate = Get-ChildItem -Path $base -Directory -Filter 'sapse.adt-vscode-*' |
      Sort-Object Name |
      Select-Object -Last 1

    if ($candidate) {
      return $candidate.FullName
    }
  }

  throw 'ERROR: SAPSE.adt-vscode extension not found under ~/.cursor or ~/.vscode. Install "ABAP Development Tools" first, then re-run.'
}

function Get-PythonCommand {
  $python = Get-Command python -ErrorAction SilentlyContinue
  if ($python) {
    return $python.Source
  }

  $python = Get-Command py -ErrorAction SilentlyContinue
  if ($python) {
    return $python.Source
  }

  throw 'ERROR: Python 3 not found. Install Python 3, then re-run.'
}

$ext = Get-ExtensionRoot
Write-Host "    extension: $ext"

$plugins = Join-Path $ext 'adt-ls'
$lsJar = Get-ChildItem -Path $plugins -Recurse -File -Filter 'com.sap.adt.ls_*.jar' |
  Where-Object { $_.FullName -notmatch '\.(orig|bak\d*)$' } |
  Select-Object -First 1
if (-not $lsJar) {
  throw "ERROR: language-server jar (com.sap.adt.ls_*.jar) not found. Expected under: $plugins"
}

$extJs = Get-ChildItem -Path $ext -Recurse -File -Filter 'extension.js' |
  Where-Object { $_.FullName -match '\\dist\\_bundle\\extension\.js$' } |
  Select-Object -First 1
if (-not $extJs) {
  throw 'ERROR: extension bundle not found: dist/_bundle/extension.js'
}

$java = Get-ChildItem -Path $ext -Recurse -File -Filter 'java.exe' |
  Where-Object { $_.FullName -match '\\jre\\bin\\java\.exe$' } |
  Select-Object -First 1
if (-not $java) {
  throw 'ERROR: bundled Java runtime not found inside the extension.'
}

$asm = Get-ChildItem -Path (Split-Path -Parent $lsJar.FullName) -File -Filter 'org.objectweb.asm_*.jar' |
  Select-Object -First 1
$asmTree = Get-ChildItem -Path (Split-Path -Parent $lsJar.FullName) -File -Filter 'org.objectweb.asm.tree_*.jar' |
  Select-Object -First 1
if (-not $asm -or -not $asmTree) {
  throw 'ERROR: bundled ASM libraries not found in plugins.'
}

if (-not (Test-Path "$($lsJar.FullName).orig")) {
  Copy-Item $lsJar.FullName "$($lsJar.FullName).orig"
}
if (-not (Test-Path "$($extJs.FullName).orig")) {
  Copy-Item $extJs.FullName "$($extJs.FullName).orig"
}
Write-Host "    backups:   $($lsJar.FullName).orig"
Write-Host "               $($extJs.FullName).orig"

$python = Get-PythonCommand
& $python "$here\scripts\patch_extension_js.py" $extJs.FullName

& $java.FullName -cp "$here\tools\adt-unlock.jar;$($asm.FullName);$($asmTree.FullName)" AdtUnlock $lsJar.FullName

Write-Host ''
Write-Host '==> Done. Reload the window (Command Palette: ''Developer: Reload Window'').' 
Write-Host '    Then: Open Objects -> pick your RFC destination -> enter your password.'
Write-Host '    Restore anytime with .\uninstall.ps1'