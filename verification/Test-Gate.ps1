$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Gate.ps1"
$case = @{ classname='ExampleTest'; name='required()'; status='passed' }
$requirement = @{ id='constraint'; tests=@('ExampleTest#required') }
function Reject($label, $cases, $requirements) {
    $rejected = $false
    try { Assert-Coverage $cases $requirements | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "False PASS: $label" }
    "PASS reject $label"
}
Assert-Coverage @($case) @($requirement) | Out-Null
Reject 'missing required test' @(@{classname='Other';name='other';status='passed'}) @($requirement)
Reject 'skipped required test' @(@{classname='ExampleTest';name='required()';status='skipped'}) @($requirement)
Reject 'unmapped failing test' @($case, @{classname='Other';name='other';status='failed'}) @($requirement)
Reject 'empty reports' @() @($requirement)
Reject 'empty constraints' @($case) @()
Reject 'empty mapping' @($case) @(@{id='empty';tests=@()})
Reject 'duplicate constraint' @($case) @($requirement,$requirement)
Reject 'parameter case skipped' @($case,@{classname='ExampleTest';name='required(String)[1]';status='skipped'}) @($requirement)
'PASS valid required test and 8 rejection cases'
. "$PSScriptRoot/Preservation.ps1"
function Must-Reject($label, [scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "False PASS: $label" }
    "PASS reject $label"
}
$snapshot = @{hashes=@{db='same'};databases=@('old');containers=@('container');ports=@('port');media=@{image='hash'}}
Assert-Preservation $snapshot $snapshot
Must-Reject 'removed old database' { Assert-Preservation $snapshot @{hashes=@{db='same'};databases=@('new');containers=@('container');ports=@('port');media=@{image='hash'}} }
Must-Reject 'modified old media' { Assert-Preservation $snapshot @{hashes=@{db='same'};databases=@('old');containers=@('container');ports=@('port');media=@{image='changed'}} }
$skippedBrowser = @(@{specs=@(@{file='test.ts';title='required';tests=@(@{projectName='mobile';status='expected';expectedStatus='skipped';results=@(@{status='skipped'})})})})
Must-Reject 'expected browser skip' { Assert-Coverage @(Read-BrowserCases $skippedBrowser) @(@{id='browser';tests=@('mobile#test.ts:required')}) -ExactNames }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('harness-gate-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
'<testsuite name="example" tests="1" failures="0" errors="0" skipped="0"><testcase classname="ExampleTest" name="required()" /></testsuite>' | Set-Content "$fixture/TEST-example.xml"
Assert-Coverage @(Read-JUnitCases $fixture) @($requirement) | Out-Null
'<testsuite name="example" tests="1" failures="0" errors="0" skipped="0"><testcase classname="ExampleTest" name="required()"><skipped /></testcase></testsuite>' | Set-Content "$fixture/TEST-example.xml"
Must-Reject 'XML skip with false suite totals' { Assert-Coverage @(Read-JUnitCases $fixture) @($requirement) }
'<testsuite name="example" tests="2" failures="0" errors="0" skipped="0"><testcase classname="ExampleTest" name="required()" /></testsuite>' | Set-Content "$fixture/TEST-example.xml"
Must-Reject 'XML case count mismatch' { Read-JUnitCases $fixture }
'PASS report parsing, browser skip and preservation gates'

# A stale or altered asset must invalidate an otherwise complete browser report.
$bundleRun=New-Item -ItemType Directory -Path (Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) '.local-tools') ('browser-build-gate-'+[guid]::NewGuid().ToString('N')))
$bundleRoot=New-Item -ItemType Directory -Path (Join-Path $bundleRun.FullName 'browser-app')
$assets=New-Item -ItemType Directory -Path (Join-Path $bundleRoot.FullName 'assets')
$vite=New-Item -ItemType Directory -Path (Join-Path $bundleRoot.FullName '.vite')
Set-Content (Join-Path $bundleRoot.FullName 'index.html') '<script type="module" src="/assets/current.js"></script>'
Set-Content (Join-Path $assets.FullName 'current.js') 'document.title="current"'
Set-Content (Join-Path $vite.FullName 'manifest.json') '{"index.html":{"isEntry":true,"file":"assets/current.js"}}'
$files=@(Get-ChildItem $bundleRoot.FullName -File -Recurse -Force | ForEach-Object {
    @{file=[IO.Path]::GetRelativePath($bundleRoot.FullName,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
@{mode='development';nodeEnv='development';entry='assets/current.js';buildMs=1;files=$files} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $bundleRun.FullName 'browser-app-build.json')
Read-VerifiedBrowserBundle -Folder $bundleRun.FullName | Out-Null
Set-Content (Join-Path $assets.FullName 'current.js') 'document.title="changed"'
$didReject=$false
try { Read-VerifiedBrowserBundle -Folder $bundleRun.FullName | Out-Null } catch { $didReject=$true }
if (-not $didReject) { throw 'False PASS: changed browser asset' }
'PASS reject changed browser asset'
