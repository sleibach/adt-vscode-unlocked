#!/usr/bin/env pwsh
#
# Rebuild tools/adt-unlock.jar from src/. Requires a JDK 21+ (javac) and the
# SAPSE.adt-vscode extension installed (its plugin jars are the compile
# classpath). End users do NOT need this — the jar is committed.
#
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

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

  throw 'ERROR: SAPSE.adt-vscode extension not found (needed for classpath).'
}

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) {
  throw 'ERROR: javac (JDK 21+) not found.'
}

$jar = Get-Command jar -ErrorAction SilentlyContinue
if (-not $jar) {
  throw 'ERROR: jar command not found.'
}

$ext = Get-ExtensionRoot
$plugins = Join-Path $ext 'adt-ls'

$out = Join-Path $here 'out'
if (Test-Path $out) {
  Remove-Item $out -Recurse -Force
}
New-Item -ItemType Directory -Path $out | Out-Null
New-Item -ItemType Directory -Path (Join-Path $here 'tools') -Force | Out-Null

& $javac.Source --release 21 -cp "$plugins\*" -d $out `
  (Join-Path $here 'src\com\sap\adt\patch\BasicAuthRfcLogon.java') `
  (Join-Path $here 'src\AdtUnlock.java')

$classFiles = Get-ChildItem -Path $out -Recurse -File -Filter '*.class' |
  ForEach-Object { $_.FullName.Substring($out.Length + 1).Replace('\', '/') }

& $jar.Source cfe (Join-Path $here 'tools\adt-unlock.jar') AdtUnlock @classFiles
Write-Host "built: $(Join-Path $here 'tools\adt-unlock.jar')"