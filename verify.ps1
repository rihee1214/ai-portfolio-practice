param(
    [string]$EvidenceDirectory = ".local-tools/harness-$([guid]::NewGuid().ToString('N'))",
    [switch]$Offline,
    [switch]$PhoneEmulatorReady,
    [string]$DbGroups,
    [string]$ChangedContracts,
    [switch]$ReverseGroups,
    [int]$TestPostgresPort = 15433,
    [string]$TestPostgresInstance = '',
    [string]$TestPostgresContainer = '',
    [switch]$ResourceOnlyDiagnostic
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/verification/DatabaseTarget.ps1"
. "$PSScriptRoot/verification/DbGroups.ps1"
$registry = Get-DbGroupRegistry
Assert-DbGroupRegistration -Registry $registry
$selectionArgs = @{Registry=$registry}
if ($PSBoundParameters.ContainsKey('DbGroups')) { $selectionArgs.Selection=$DbGroups }
if ($PSBoundParameters.ContainsKey('ChangedContracts')) { $selectionArgs.ChangedContracts=$ChangedContracts }
$selection = Resolve-DbGroups @selectionArgs
$forwardSelection = @{}
if ($PSBoundParameters.ContainsKey('DbGroups')) { $forwardSelection.DbGroups=$DbGroups }
if ($PSBoundParameters.ContainsKey('ChangedContracts')) { $forwardSelection.ChangedContracts=$ChangedContracts }
if ($ReverseGroups) { $forwardSelection.ReverseGroups=$true }
$endpoint = Resolve-TestPostgresEndpoint -Port $TestPostgresPort -Instance $TestPostgresInstance -Container $TestPostgresContainer -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
if (-not $PhoneEmulatorReady) {
    & "$PSScriptRoot/verification/Run-PhoneEmulator.ps1" -EvidenceDirectory $EvidenceDirectory @forwardSelection -Offline:$Offline -TestPostgresPort $TestPostgresPort -TestPostgresInstance $TestPostgresInstance -TestPostgresContainer $TestPostgresContainer -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
    return
}
if (-not $env:FIREBASE_AUTH_EMULATOR_HOST -or -not $env:GCLOUD_PROJECT.StartsWith('demo-')) { throw 'Fresh local phone emulator required' }
. "$PSScriptRoot/verification/Gate.ps1"
. "$PSScriptRoot/verification/Preservation.ps1"
$output = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be inside .local-tools' }
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory' }
New-Item -ItemType Directory -Path $output | Out-Null
$oldPath = $env:PATH
$wholeClock = [Diagnostics.Stopwatch]::StartNew()
$result = @{ status='failed'; started=(Get-Date).ToString('o'); scope='Local marketplace verification. See selection.json for the selected and excluded contracts and the case reports for the executed assertions.'; evidence=$output }
$result.testPostgres = $endpoint
$result.resourceOnlyDiagnostic = [bool]$ResourceOnlyDiagnostic
$result.certificationEligible = -not $ResourceOnlyDiagnostic -and $selection.certificationEligible
$result.selection = $selection
if ($ResourceOnlyDiagnostic) { $result.scope = 'DIAGNOSTIC ONLY: original database dump preservation omitted; whole verification cannot be certified. ' + $result.scope }
function Get-Inputs {
    $files = @(& git ls-files --cached --others --exclude-standard -- backend frontend verification verify.ps1)
    if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed' }
    @($files | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | ForEach-Object {
        [pscustomobject]@{ path=$_; sha256=(Get-FileHash -LiteralPath $_).Hash }
    })
}
Push-Location $PSScriptRoot
try {
    $env:PATH = (Join-Path $PSScriptRoot '.local-tools/node-v24.22.0-win-x64') + ';' + $oldPath
    $inputs = Get-Inputs
    $inputs | ConvertTo-Json | Set-Content (Join-Path $output 'inputs.json')
    $result.commit = (& git rev-parse HEAD)
    $result.dirty = @(& git status --short)
    $preservationClock = [Diagnostics.Stopwatch]::StartNew()
    $before = Get-PreservationSnapshot -Endpoint $endpoint -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
    $before | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'before.json')
    $result.preservationBeforeSeconds = $preservationClock.Elapsed.TotalSeconds
    $selection | ConvertTo-Json -Depth 8 | Set-Content "$output/selection.json"
    & "$PSScriptRoot/verification/Test-DbGroups.ps1" | Set-Content "$output/db-group-tests.txt"
    & "$PSScriptRoot/verification/Test-Gate.ps1" | Set-Content (Join-Path $output 'gate-tests.txt')
    & "$PSScriptRoot/verification/Test-DatabaseTarget.ps1" | Set-Content (Join-Path $output 'database-target-tests.txt')
    $backendSelection = @{}
    if ($selection.mode -ceq 'selected') { $backendSelection.DbGroups=($selection.selected -join ',') }
    & "$PSScriptRoot/backend/verify-db.ps1" -EvidenceDirectory "$EvidenceDirectory/db" @backendSelection -ReverseGroups:$ReverseGroups -Offline:$Offline -RunBrowser -TestPostgresPort $TestPostgresPort -TestPostgresInstance $TestPostgresInstance -TestPostgresContainer $TestPostgresContainer
    $java = @(Read-JUnitCases "$output/db/build/surefire-reports")
    foreach ($id in $selection.selected) { $java += @(Read-JUnitCases "$output/db/groups/$id/failsafe-reports") }
    $requirements = Get-Content "$PSScriptRoot/verification/required-tests.json" -Raw | ConvertFrom-Json
    if ($selection.mode -ceq 'selected') {
        $selectedTests = @($registry.groups | Where-Object { $_.id -cin $selection.selected } | ForEach-Object { $_.tests.new })
        $requirements = @($requirements | ForEach-Object {
            $kept = @($_.tests | Where-Object { $_ -notmatch 'IT#' -or $_ -cin $selectedTests })
            if ($kept.Count) { [pscustomobject]@{id=$_.id;tests=$kept} }
        })
    }
    $coverage = @(Assert-Coverage $java $requirements)
    $coverage | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'coverage.json')
    $java | ConvertTo-Json | Set-Content (Join-Path $output 'java-cases.json')
    if ($selection.selected -ccontains 'browser-api') {
    $browser = Get-Content "$output/db/groups/browser-api/browser-report.json" -Raw | ConvertFrom-Json
    if ($browser.errors.Count -or $browser.stats.expected -lt 22 -or $browser.stats.skipped -or $browser.stats.unexpected -or $browser.stats.flaky) {
        throw 'Browser gate failed (minimum current baseline: 22, no skips or retries accepted)'
    }
    $browserCases = @(Read-BrowserCases $browser.suites)
    $browserRequired = Get-Content "$PSScriptRoot/verification/required-browser.json" -Raw | ConvertFrom-Json
    Assert-Coverage $browserCases $browserRequired -ExactNames | ConvertTo-Json -Depth 6 | Set-Content "$output/browser-coverage.json"
    }
    Push-Location frontend
    try {
        # Proxy preview tests require dist even in a fresh checkout.
        foreach ($task in @('typecheck','lint','test:unit','build','test')) {
            $arguments = @('run',$task)
            if ($task -eq 'test:unit') { $arguments += @('--','--reporter=default','--reporter=junit',"--outputFile=$output/TEST-frontend.xml") }
            & npm.cmd @arguments 2>&1 | Set-Content (Join-Path $output ($task.Replace(':','-')+'.txt'))
            if ($LASTEXITCODE -ne 0) { throw "Frontend $task failed" }
        }
    } finally { Pop-Location }
    $frontend = @(Read-JUnitCases $output)
    if ($frontend.Count -lt 96 -or @($frontend | Where-Object status -ne 'passed').Count) { throw 'Frontend unit gate failed (minimum current baseline: 96)' }
    $frontendRequired = Get-Content "$PSScriptRoot/verification/required-frontend.json" -Raw | ConvertFrom-Json
    Assert-Coverage $frontend $frontendRequired -ExactNames | ConvertTo-Json -Depth 6 | Set-Content "$output/frontend-coverage.json"
    $afterInputs = Get-Inputs
    if (($inputs | ConvertTo-Json -Compress) -cne ($afterInputs | ConvertTo-Json -Compress)) { throw 'Source changed during verification' }
    $result.junit = $java.Count
    $result.frontendUnit = $frontend.Count
    $result.browser = if ($selection.selected -ccontains 'browser-api') { $browser.stats.expected } else { 0 }
    Assert-FullVerificationAllowed -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
    $result.status = if ($selection.mode -ceq 'full') { 'passed' } else { 'selected-passed' }
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    try {
        if ($before) {
            $preservationClock.Restart()
            $after = Get-PreservationSnapshot -Endpoint $endpoint -ResourceOnlyDiagnostic:$ResourceOnlyDiagnostic
            $after | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'after.json')
            Assert-Preservation $before $after
            $result.preservation = 'passed'
            $result.preservationAfterSeconds = $preservationClock.Elapsed.TotalSeconds
        }
    } catch {
        $result.status = 'failed'; $result.preservation = 'failed'; $result.preservationError = $_.Exception.Message
        throw
    } finally {
        $result.finished = (Get-Date).ToString('o')
        $result.elapsedSeconds = $wholeClock.Elapsed.TotalSeconds
        $result | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'result.json')
        $env:PATH = $oldPath
        Pop-Location
    }
}
"$($result.status) integrated harness: $output/result.json"
