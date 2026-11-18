param([Parameter(Mandatory)][string]$Request,[Parameter(Mandatory)][string]$WorkerScript)
$ErrorActionPreference='Stop'
$requestPath=[IO.Path]::GetFullPath($Request)
$data=Get-Content -Encoding utf8 -LiteralPath $requestPath -Raw|ConvertFrom-Json
$folder=[IO.Path]::GetDirectoryName($requestPath)
. "$PSScriptRoot/PowerShellRuntime.ps1"
$null=Assert-VerificationPowerShellRuntime
$started=[Diagnostics.Stopwatch]::StartNew()
try{
    . "$PSScriptRoot/WorkerJob.ps1"
    [MarketplaceVerification.WorkerJob]::AttachSelf($data.jobName)
    $members=[MarketplaceVerification.WorkerJob]::Snapshot($data.jobName)
    if($members.ProcessIds -notcontains $PID){throw 'Worker kernel job self membership missing'}
    $proof=@{jobName=$data.jobName;pid=$PID;requestSha256=(Get-FileHash -LiteralPath $requestPath).Hash.ToLowerInvariant();killOnJobClose=$true;breakawayAllowed=$false;attachmentSeconds=$started.Elapsed.TotalSeconds;processIds=@($members.ProcessIds)}
    $proof|ConvertTo-Json -Depth 5|Set-Content -Encoding utf8 "$folder/job-ready.tmp"
    Move-Item -LiteralPath "$folder/job-ready.tmp" -Destination "$folder/job-ready.json"
    $wait=[Diagnostics.Stopwatch]::StartNew()
    while(-not(Test-Path -LiteralPath "$folder/job-admit.json")){if($wait.ElapsedMilliseconds -ge 10000){throw 'Finite worker attachment admission wait expired'};Start-Sleep -Milliseconds 20}
    $admit=Get-Content -Encoding utf8 "$folder/job-admit.json" -Raw|ConvertFrom-Json
    if($admit.requestSha256 -cne $proof.requestSha256){throw 'Worker admission request identity mismatch'}
    & $WorkerScript -Request $requestPath
    $success=$?;$code=[int]$LASTEXITCODE
    if(-not $success -and $code -eq 0){$code=1}
    $remaining=[MarketplaceVerification.WorkerJob]::Snapshot($data.jobName)
    $descendants=@($remaining.ProcessIds|Where-Object{$_ -ne $PID})
    @{jobName=$data.jobName;pid=$PID;requestSha256=$proof.requestSha256;status=if($code -eq 0 -and -not $descendants.Count){'passed'}else{'failed'};descendantIds=$descendants;processExitCode=$code}|ConvertTo-Json -Depth 5|Set-Content -Encoding utf8 "$folder/job-final.json"
    if($descendants.Count -and $code -eq 0){$code=1}
    exit $code
}catch{
    @{status='failed';error=$_.Exception.Message;jobName=$data.jobName;pid=$PID;workloadAdmitted=(Test-Path -LiteralPath "$folder/job-admit.json")}|ConvertTo-Json -Depth 5|Set-Content -Encoding utf8 "$folder/job-attachment-failure.json"
    Write-Error $_ -ErrorAction Continue
    exit 1
}
