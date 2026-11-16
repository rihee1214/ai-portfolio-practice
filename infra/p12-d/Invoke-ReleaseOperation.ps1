param([Parameter(Mandatory=$true)][string]$Request, [string]$Node = 'node')
$ErrorActionPreference = 'Stop'
& $Node (Join-Path $PSScriptRoot 'release-operations.cjs') ([IO.Path]::GetFullPath($Request))
if ($LASTEXITCODE -ne 0) { throw "Release operation failed; retained evidence is identified in the request ($LASTEXITCODE)." }
