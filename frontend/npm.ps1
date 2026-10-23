# Use the project-local Node and its bundled npm, without changing system settings.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$NpmArguments)

$ErrorActionPreference = 'Stop'
$nodeDirectory = Join-Path $PSScriptRoot '../.local-tools/node-v24.22.0-win-x64'
$node = Join-Path $nodeDirectory 'node.exe'
$npm = Join-Path $nodeDirectory 'node_modules/npm/bin/npm-cli.js'
if (-not (Test-Path -LiteralPath $node) -or -not (Test-Path -LiteralPath $npm)) {
    throw 'Prepare the project-local Node 24.22.0 Windows x64 ZIP first. See docs/work-items/P01-B2.md.'
}
$previousPath = $env:PATH
$previousCache = $env:npm_config_cache
try {
    $env:PATH = "$nodeDirectory;$previousPath"
    $env:npm_config_cache = Join-Path $PSScriptRoot '../.local-tools/npm-cache'
    Push-Location $PSScriptRoot
    try {
        & $node $npm @NpmArguments
        if ($LASTEXITCODE -ne 0) { throw "npm failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:PATH = $previousPath
    $env:npm_config_cache = $previousCache
}
