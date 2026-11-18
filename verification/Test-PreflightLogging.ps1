$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/PowerShellRuntime.ps1"
$null=Assert-VerificationPowerShellRuntime
. "$PSScriptRoot/Preflight.ps1"
$folder=Join-Path $root ('.local-tools/preflight-logging-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$node=Join-Path $root '.local-tools/node-v24.22.0-win-x64/node.exe'
$silent=Join-Path $folder 'silent.txt'
$silentExit=Invoke-PreflightLoggedCommand -Program $node -Arguments @('-e','process.exit(0)') -Log $silent
if($silentExit -ne 0 -or -not(Test-Path -LiteralPath $silent -PathType Leaf) -or (Get-Item -LiteralPath $silent).Length -ne 0){throw 'Silent successful producer must create an empty owned log'}
$consumer=Join-Path $folder 'root-copy.txt'
Copy-Item -LiteralPath $silent -Destination $consumer
if(-not(Test-Path -LiteralPath $consumer -PathType Leaf) -or (Get-Item -LiteralPath $consumer).Length -ne 0){throw 'Root copy consumer lost empty successful log'}
$failed=Join-Path $folder 'failed.txt'
$failedExit=Invoke-PreflightLoggedCommand -Program $node -Arguments @('-e',"process.stderr.write('실패 UTF8 retained');process.exit(7)") -Log $failed
if($failedExit -ne 7 -or -not(Test-Path -LiteralPath $failed -PathType Leaf) -or -not((Get-Content -Encoding utf8 -LiteralPath $failed -Raw).Contains('실패 UTF8 retained'))){throw 'Failed producer must preserve exact exit and UTF8 failure output'}
$reused=$false;try{Invoke-PreflightLoggedCommand -Program $node -Arguments @('-e','process.exit(0)') -Log $failed|Out-Null}catch{$reused=$true}
if(-not $reused -or -not((Get-Content -Encoding utf8 -LiteralPath $failed -Raw).Contains('실패 UTF8 retained'))){throw 'Reused log must be rejected without overwriting prior failure'}
@{status='passed';silentExit=$silentExit;failureExit=$failedExit;copiedBytes=(Get-Item -LiteralPath $consumer).Length;failurePreserved=$true;evidence=$folder}|ConvertTo-Json -Compress
