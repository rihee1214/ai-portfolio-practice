param([Parameter(Mandatory)][string]$EvidenceDirectory, [switch]$Offline,
    [int]$TestPostgresPort = 15433, [string]$TestPostgresInstance = '', [string]$TestPostgresContainer = '',
    [switch]$ResourceOnlyDiagnostic, [string]$DbGroups, [string]$ChangedContracts, [switch]$ReverseGroups)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/DatabaseTarget.ps1"
$endpoint = Resolve-TestPostgresEndpoint -Port $TestPostgresPort -Instance $TestPostgresInstance -Container $TestPostgresContainer -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
$cli = Join-Path $root '.local-tools/p06-a-provider-probe-20261025/node_modules/.bin/firebase.cmd'
if (-not (Test-Path $cli)) { throw 'P06-A Firebase CLI cache missing; see docs/work-items/P06-A.md' }
$run = Join-Path $root ('.local-tools/phone-emulator-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $run | Out-Null
$listeners = @()
try {
    1..3 | ForEach-Object {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $listeners += $listener
    }
    $ports = @($listeners | ForEach-Object { $_.LocalEndpoint.Port })
} finally { $listeners | ForEach-Object { $_.Stop() } }
$config = @{emulators=@{auth=@{host='127.0.0.1';port=$ports[0]};hub=@{host='127.0.0.1';port=$ports[1]};logging=@{host='127.0.0.1';port=$ports[2]};ui=@{enabled=$false};singleProjectMode=$true}}
$config | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $run 'firebase.json')
$project = 'demo-p06-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$oldPath=$env:PATH
$env:PATH=(Join-Path $root '.local-tools/node-v24.22.0-win-x64')+';'+$oldPath
$selectionForward = @{}
if ($PSBoundParameters.ContainsKey('DbGroups')) { $selectionForward.DbGroups=$DbGroups }
if ($PSBoundParameters.ContainsKey('ChangedContracts')) { $selectionForward.ChangedContracts=$ChangedContracts }
if ($ReverseGroups) { $selectionForward.ReverseGroups=$true }
$command = New-PhoneHarnessCommand @selectionForward -Root $root -EvidenceDirectory $EvidenceDirectory -Endpoint $endpoint -Offline:$Offline -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
Push-Location $run
try {
    & $cli emulators:exec --only auth --project $project --config (Join-Path $run 'firebase.json') $command *> (Join-Path $run 'emulator.txt')
    $code = $LASTEXITCODE
    [pscustomobject]@{project=$project;ports=$ports;exitCode=$code;evidence=$EvidenceDirectory} | ConvertTo-Json | Set-Content (Join-Path $run 'result.json')
    if ($code -ne 0) { throw "Phone-enabled harness failed; preserved logs: $run" }
    Write-Output "PASS phone-enabled harness; emulator evidence: $run"
} finally {
    Pop-Location
    $env:PATH=$oldPath
}
