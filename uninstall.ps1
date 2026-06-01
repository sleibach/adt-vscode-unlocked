#!/usr/bin/env pwsh
#
# Restore the official extension files from the *.orig backups created by
# install.ps1. Safe to run multiple times.
#
$ErrorActionPreference = 'Stop'

Write-Host '==> adt-vscode-unlocked uninstaller'

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

  throw 'ERROR: SAPSE.adt-vscode extension not found.'
}

$ext = Get-ExtensionRoot
$plugins = Join-Path $ext 'adt-ls'
$lsJar = Get-ChildItem -Path $plugins -Recurse -File -Filter 'com.sap.adt.ls_*.jar' |
  Where-Object { $_.FullName -notmatch '\.(orig|bak\d*)$' } |
  Select-Object -First 1
$extJs = Get-ChildItem -Path $ext -Recurse -File -Filter 'extension.js' |
  Where-Object { $_.FullName -match '\\dist\\_bundle\\extension\.js$' } |
  Select-Object -First 1

$restored = 0
if ($lsJar -and (Test-Path "$($lsJar.FullName).orig")) {
  Copy-Item "$($lsJar.FullName).orig" $lsJar.FullName -Force
  Write-Host "    restored: $($lsJar.FullName)"
  $restored = 1
}

if ($extJs -and (Test-Path "$($extJs.FullName).orig")) {
  Copy-Item "$($extJs.FullName).orig" $extJs.FullName -Force
  Write-Host "    restored: $($extJs.FullName)"
  $restored = 1
}

if ($restored -eq 0) {
  Write-Host '    nothing to restore (no *.orig backups found).'
} else {
  Write-Host '==> Done. Reload the window to load the original files.'
}