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

$deadlineCases = @([pscustomobject]@{classname='desktop';name='a.spec.ts:submit';status='passed'})
$deadlineTest = @{id='one';project='desktop';file='C:/new/a.spec.ts';title='submit';status='passed';operations=1}
$deadlineReport = @{status='passed';problems=@();scopes=1;tests=@($deadlineTest)}
Assert-BrowserDeadlineEvidence $deadlineReport $deadlineCases
Must-Reject 'missing deadline tests' { Assert-BrowserDeadlineEvidence @{status='passed';problems=@();scopes=1;tests=@()} $deadlineCases }
Must-Reject 'deadline failure hidden by browser pass' { Assert-BrowserDeadlineEvidence @{status='failed';problems=@('late');scopes=1;tests=@($deadlineTest)} $deadlineCases }
$deadlineTest.operations = 0
Must-Reject 'browser pass without measured command' { Assert-BrowserDeadlineEvidence $deadlineReport $deadlineCases }
$deadlineTest.operations = 1
$deadlineTest.title = 'other'
Must-Reject 'deadline from a different test' { Assert-BrowserDeadlineEvidence $deadlineReport $deadlineCases }
$deadlineTest.title = 'submit'
$deadlineReport.tests = @($deadlineTest,$deadlineTest)
Must-Reject 'duplicate deadline evidence' { Assert-BrowserDeadlineEvidence $deadlineReport @($deadlineCases[0],$deadlineCases[0]) }
'PASS browser deadline evidence gates'

# An isolated project must never borrow the other project's green report.
$maintenanceGroup = @{id='site-maintenance-desktop';browser=@{project='desktop'}}
$maintenanceRequirements = @{id='maintenance';tests=@('desktop#site-maintenance.spec.ts:policy','mobile#site-maintenance.spec.ts:policy')}
$maintenanceCases = @(@{classname='desktop';name='site-maintenance.spec.ts:policy';status='passed'})
Assert-BrowserGroupCoverage $maintenanceGroup $maintenanceCases $maintenanceRequirements | Out-Null
Must-Reject 'foreign project in isolated browser group' { Assert-BrowserGroupCoverage $maintenanceGroup @(@{classname='mobile';name='site-maintenance.spec.ts:policy';status='passed'}) $maintenanceRequirements }
Must-Reject 'duplicate isolated browser result' { Assert-BrowserGroupCoverage $maintenanceGroup @($maintenanceCases[0],$maintenanceCases[0]) $maintenanceRequirements }
Must-Reject 'unregistered isolated browser result' { Assert-BrowserGroupCoverage $maintenanceGroup @($maintenanceCases[0],@{classname='desktop';name='other.spec.ts:other';status='passed'}) $maintenanceRequirements }
Must-Reject 'missing isolated browser requirement' { Assert-BrowserGroupCoverage $maintenanceGroup $maintenanceCases @{id='wrong';tests=@('mobile#site-maintenance.spec.ts:policy')} }
$scope = @{id='site-maintenance-desktop';database='d2_it_'+('a'*32);pid=101;httpPort=31001;media='D:/new/desktop/media';evidence='D:/new/desktop'}
$proof = @{project='desktop';database=$scope.database;pid=101;httpPort=31001;media=$scope.media;initialPolicy=@{version=1;baseFee=3000;freeThreshold=50000};finalPolicy=@{version=2;baseFee=4567;freeThreshold=51000};orders=2;businessRowsBefore=0;originalPolicyReset=$false}
Assert-MaintenanceBrowserLifecycle $scope $proof @() | Out-Null
Must-Reject 'policy scenario reused changed database' { $bad=$proof.Clone();$bad.initialPolicy=@{version=2;baseFee=3000;freeThreshold=50000}; Assert-MaintenanceBrowserLifecycle $scope $bad @() }
Must-Reject 'policy reset hidden after scenario' { $bad=$proof.Clone();$bad.finalPolicy=@{version=3;baseFee=3000;freeThreshold=50000}; Assert-MaintenanceBrowserLifecycle $scope $bad @() }
Must-Reject 'policy browser API identity mismatch' { $bad=$proof.Clone();$bad.pid=102; Assert-MaintenanceBrowserLifecycle $scope $bad @() }
foreach($field in @('database','pid','media','evidence')) {
    Must-Reject "reused isolated browser $field" { $previous=@{id='site-maintenance-mobile';database='d2_it_'+('b'*32);pid=102;httpPort=31002;media='D:/new/mobile/media';evidence='D:/new/mobile'};$previous[$field]=$scope[$field];Assert-MaintenanceBrowserLifecycle $scope $proof @($previous) }
}
'PASS isolated browser registration, project ownership and lifecycle gates'
$faults = [pscustomobject]@{
    mixedCheckpoint=[pscustomobject]@{commandId='11111111-1111-4111-8111-111111111111'}
    schemaReadiness=[pscustomobject]@{commandId='22222222-2222-4222-8222-222222222222'}
}
$expectedFaults = @(
    [pscustomobject]@{commandId=$faults.mixedCheckpoint.commandId;action='Restore';status='failed';error='Media namespace mismatch'},
    [pscustomobject]@{commandId=$faults.schemaReadiness.commandId;action='Activate';status='failed';error='Schema version mismatch'}
)
Assert-ExpectedReleaseFailures -Results $expectedFaults -Faults $faults | Out-Null
Must-Reject 'unexpected operator failure' { Assert-ExpectedReleaseFailures -Results @($expectedFaults + [pscustomobject]@{commandId='33333333-3333-4333-8333-333333333333';status='failed'}) -Faults $faults }
Must-Reject 'expected fault returned success' { $copy=$expectedFaults | ConvertTo-Json | ConvertFrom-Json; $copy[0].status='passed'; Assert-ExpectedReleaseFailures -Results $copy -Faults $faults }
Must-Reject 'fault failed at wrong boundary' { $copy=$expectedFaults | ConvertTo-Json | ConvertFrom-Json; $copy[0].error='File missing'; Assert-ExpectedReleaseFailures -Results $copy -Faults $faults }
Must-Reject 'duplicate expected failure result' { Assert-ExpectedReleaseFailures -Results @($expectedFaults + $expectedFaults[0]) -Faults $faults }
Must-Reject 'missing expected failure result' { Assert-ExpectedReleaseFailures -Results @($expectedFaults[0]) -Faults $faults }
'PASS exact expected operator fault identities, reasons and no unrelated failure'
$typed=@{}
foreach($table in @('claim_exchange_refund_recovery_run','claim_exchange_refund_recovery_stage','claim_exchange_refund_recovery_page',
    'claim_exchange_refund_recovery_page_complete','claim_return_recovery_run','claim_return_recovery_stage',
    'claim_return_recovery_page','claim_return_recovery_page_complete','claim_return_recovery_candidate','job_claim_fact',
    'job','outbox_event','dashboard_run','dashboard_snapshot','dashboard_metric','dashboard_head','notification_inbox')) {
    $typed[$table]=@{passed=$true;validator='typed-original-and-cause';addedIds=@();changedIds=@()}
}
$preservation=@{boundaryExact=@{passed=$true;beforeDigest=('a'*64);afterDigest=('a'*64);tableInventory=@('purchase_order');sequencesEqual=$true;mediaEqual=$true};
    live=@{passed=$true;allOldRowsPreserved=$true;coverageComplete=$true;unregisteredChanges=@();beforeSnapshotId='11111111-1111-4111-8111-111111111111';afterSnapshotId='22222222-2222-4222-8222-222222222222';typeRegistry=@($typed.Keys|Sort-Object);typedResults=$typed}} | ConvertTo-Json -Depth 10 | ConvertFrom-Json
Assert-ReleasePreservationProof $preservation
Must-Reject 'quiet preservation digest mismatch' {$copy=$preservation|ConvertTo-Json -Depth 10|ConvertFrom-Json;$copy.boundaryExact.afterDigest=('b'*64);Assert-ReleasePreservationProof $copy}
Must-Reject 'full table preservation coverage omitted' {$copy=$preservation|ConvertTo-Json -Depth 10|ConvertFrom-Json;$copy.live.coverageComplete=$false;Assert-ReleasePreservationProof $copy}
Must-Reject 'typed row validator failure hidden by overall pass' {$copy=$preservation|ConvertTo-Json -Depth 10|ConvertFrom-Json;$copy.live.typedResults.job_claim_fact.passed=$false;Assert-ReleasePreservationProof $copy}
$rollbackProof=$preservation|ConvertTo-Json -Depth 10|ConvertFrom-Json
$rollbackProof.boundaryExact|Add-Member beforeSnapshotId $rollbackProof.live.beforeSnapshotId
$rollbackProof.live|Add-Member inputSnapshotId '33333333-3333-4333-8333-333333333333'
$rollbackProof.live|Add-Member quietBaselineDigest $rollbackProof.boundaryExact.afterDigest
Assert-ReleasePreservationProof $rollbackProof -Action Rollback
Must-Reject 'running input disguised as quiet rollback baseline' {$copy=$rollbackProof|ConvertTo-Json -Depth 10|ConvertFrom-Json;$copy.live.inputSnapshotId=$copy.live.beforeSnapshotId;Assert-ReleasePreservationProof $copy -Action Rollback}
Must-Reject 'quiet rollback baseline digest unbound' {$copy=$rollbackProof|ConvertTo-Json -Depth 10|ConvertFrom-Json;$copy.live.quietBaselineDigest=('b'*64);Assert-ReleasePreservationProof $copy -Action Rollback}
'PASS exact full quiet and typed row preservation proof gates'
$cancel=@{status='passed';limitMs=10000;activeOperations=0;activeRequests=0;executorTerminated=$true;observedHandles=@(@{pid=12345;alive=$false});handlerThreads=@(@{id=12;alive=$false});started='2026-11-16T00:00:00Z';ended='2026-11-16T00:00:01Z'}
function Assert-SerializedCancellation($Evidence) {
    Assert-ReleaseOperationCancellation ($Evidence | ConvertTo-Json -Depth 4)
}
Assert-SerializedCancellation $cancel
Must-Reject 'operator still alive before target cleanup' {$bad=$cancel.Clone();$bad.observedHandles=@(@{pid=12345;alive=$true});Assert-SerializedCancellation $bad}
Must-Reject 'handler still running before target cleanup' {$bad=$cancel.Clone();$bad.handlerThreads=@(@{id=12;alive=$true});Assert-SerializedCancellation $bad}
Must-Reject 'operator join failed despite all other counters' {$bad=$cancel.Clone();$bad.status='failed';Assert-SerializedCancellation $bad}
$mixed=$cancel.Clone();$mixed.started='2026-11-19T15:12:27.509282Z';$mixed.ended='2026-11-19T15:12:27.510289800Z'
Assert-SerializedCancellation $mixed
$exact=$mixed.Clone();$exact.ended='2026-11-20T00:12:37.509282000+09:00';Assert-SerializedCancellation $exact
Must-Reject 'cancellation exceeds ten seconds by one nanosecond' {$bad=$exact.Clone();$bad.ended='2026-11-19T15:12:37.509282001Z';Assert-SerializedCancellation $bad}
Must-Reject 'cancellation timestamps reversed by one nanosecond' {$bad=$mixed.Clone();$bad.ended='2026-11-19T15:12:27.509281999Z';Assert-SerializedCancellation $bad}
'PASS finite operator and handler cancellation evidence before target cleanup'
