param(
    [string]$EvidenceDirectory = ".local-tools/d2-$([guid]::NewGuid().ToString('N'))",
    [switch]$Offline,
    [switch]$RunBrowser,
    [string]$DbGroups,
    [switch]$ReverseGroups,
    [ValidateSet('1','2')][string]$GroupConcurrency='2',
    [int]$TestPostgresPort = 15433,
    [string]$TestPostgresInstance = '',
    [string]$TestPostgresContainer = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. "$root/verification/PowerShellRuntime.ps1"
$null = Assert-VerificationPowerShellRuntime
. "$root/verification/DatabaseTarget.ps1"
. "$root/verification/DbGroups.ps1"
. "$root/verification/Gate.ps1"
$registry = Get-DbGroupRegistry
Assert-DbGroupRegistration -Registry $registry -Root $root
$selectionArgs = @{Registry=$registry}
if ($PSBoundParameters.ContainsKey('DbGroups')) { $selectionArgs.Selection=$DbGroups }
$selection = Resolve-DbGroups @selectionArgs
$ordered = @(Get-DbGroupExecutionOrder -Registry $registry -Selected $selection.selected -Reverse:$ReverseGroups)
$endpoint = Resolve-TestPostgresEndpoint -Port $TestPostgresPort -Instance $TestPostgresInstance -Container $TestPostgresContainer
$output = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $root '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be inside .local-tools' }
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory' }
New-Item -ItemType Directory -Path $output | Out-Null
$names = @('JAVA_HOME','MAVEN_USER_HOME','D2_DB_NAME','D2_DB_URL','D2_DB_USERNAME','D2_DB_PASSWORD','D2_ADMIN_PASSWORD','A3_RUN_BROWSER','A3_EVIDENCE','P12_B_BROWSER_PROJECT','D2_TEST_POSTGRES_PORT','D2_TEST_POSTGRES_INSTANCE','D2_TEST_POSTGRES_CONTAINER','D2_DB_ENDPOINT_CONFIRMATION','MAVEN_ARGS','P09_F_BROWSER_ONLY','D2_PRODUCTION_BUNDLE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secrets = @{}
$groups = @()
$compiledManifest=$null;$compiledManifestSha=$null
$result = @{status='failed';started=(Get-Date).ToString('o');selection=$selection;executionOrder=$ordered;reversePriority=[bool]$ReverseGroups;groups=@()}
function Invoke-ContractMaven([string[]]$Goals, [string]$Log, [string[]]$Properties=@()) {
    $arguments = @('-B','-ntp','-Pdb-it','-Dmaven.repo.local=../.local-tools/m2/repository',"-Dmarketplace.build.directory=$build", "-Djava.io.tmpdir=$output/java-tmp") + $Properties + $Goals
    if ($Offline) { $arguments = @('-o') + $arguments }
    Push-Location $PSScriptRoot
    try {
        & ./mvnw.cmd @arguments 2>&1 | ForEach-Object {
            $line = [string]$_
            foreach ($secret in $secrets.Values) { $line = $line.Replace($secret, '[REDACTED]') }
            $line
        } | Set-Content $Log
        $LASTEXITCODE
    } finally { Pop-Location }
}
try {
    if ($env:MAVEN_ARGS) { throw 'Ambient MAVEN_ARGS can bypass group selection; clear it explicitly' }
    $selected = Get-SelectedPostgresSnapshot $endpoint
    @{endpoint=$endpoint;selected=$selected} | ConvertTo-Json -Depth 6 | Set-Content "$output/endpoint.json"
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^(TEST_ADMIN_PASSWORD|TEST_DB_PASSWORD)=(.+)$') { $secrets[$Matches[1]] = $Matches[2] }
    }
    foreach ($key in @('TEST_ADMIN_PASSWORD','TEST_DB_PASSWORD')) { if (-not $secrets[$key]) { throw "Missing $key" } }
    $env:JAVA_HOME = Join-Path $root '.local-tools/jdk-21.0.12.1+1'
    $env:MAVEN_USER_HOME = Join-Path $root '.local-tools/m2'
    $env:D2_TEST_POSTGRES_PORT = [string]$endpoint.port
    $env:D2_TEST_POSTGRES_INSTANCE = $endpoint.instance
    $env:D2_TEST_POSTGRES_CONTAINER = $endpoint.container
    $env:D2_DB_ENDPOINT_CONFIRMATION = $endpoint.confirmation
    $env:D2_DB_USERNAME = 'marketplace_test'
    $env:D2_DB_PASSWORD = $secrets['TEST_DB_PASSWORD']
    $env:D2_ADMIN_PASSWORD = $secrets['TEST_ADMIN_PASSWORD']
    $build = Join-Path $output 'build'
    New-Item -ItemType Directory -Path "$output/java-tmp" | Out-Null
    $compileClock = [Diagnostics.Stopwatch]::StartNew()
    $compileExit = Invoke-ContractMaven -Goals @('package') -Log "$output/maven.txt"
    $result.commonBuildSeconds = $compileClock.Elapsed.TotalSeconds
    if ($compileExit -ne 0) { throw 'Common compile, Checkstyle, unit or architecture tests failed' }
    $common = @(Read-JUnitCases "$build/surefire-reports")
    $node=Join-Path $root '.local-tools/node-v24.22.0-win-x64/node.exe'
    $compiledClock=[Diagnostics.Stopwatch]::StartNew()
    $compiledManifest=Join-Path $output 'common-build-manifest.json'
    $compiledText=& $node "$root/verification/CompiledBuild.cjs" prepare $root $build $compiledManifest
    if($LASTEXITCODE -ne 0){throw 'Current compiled build manifest preparation failed'}
    $compiled=$compiledText|ConvertFrom-Json
    $compiledManifestSha=$compiled.manifestSha256
    $compiledText|Set-Content -Encoding utf8 "$output/common-build-preparation.json"
    $result.compiledBuildPreparationSeconds=$compiledClock.Elapsed.TotalSeconds
    $productionClock=[Diagnostics.Stopwatch]::StartNew()
    $node=Join-Path $root '.local-tools/node-v24.22.0-win-x64/node.exe'
    & $node "$root/verification/ProductionBundle.cjs" prepare $root "$output/current-production" 2>&1 | Set-Content -Encoding utf8 "$output/production-bundle.txt"
    if($LASTEXITCODE -ne 0){throw 'Same-run production bundle preparation failed'}
    $env:D2_PRODUCTION_BUNDLE=Join-Path $output 'current-production/manifest.json'
    $result.productionBundleSeconds=$productionClock.Elapsed.TotalSeconds
    $result.productionBundle=$env:D2_PRODUCTION_BUNDLE
    $schedulerRequest=@{root=$root;output=$output;build=$build;compiledManifest=$compiledManifest;compiledManifestSha256=$compiledManifestSha;offline=[bool]$Offline;endpoint=$endpoint;selected=@($selection.selected);priority=$ordered;concurrency=[int]$GroupConcurrency}
    $schedulerRequest|ConvertTo-Json -Depth 10|Set-Content -Encoding utf8 "$output/scheduler-request.json"
    [IO.File]::WriteAllText("$output/scheduler.txt",'',[Text.UTF8Encoding]::new($false))
    & $node "$root/verification/DbGroupExecution.cjs" "$output/scheduler-request.json" 2>&1|Set-Content -Encoding utf8 "$output/scheduler.txt"
    $schedulerExit=$LASTEXITCODE
    $scheduler=Get-Content -Encoding utf8 "$output/scheduler-result.json" -Raw|ConvertFrom-Json
    $result.scheduler=@{concurrency=$scheduler.concurrency;maximumActive=$scheduler.maximumActive;elapsedSeconds=$scheduler.elapsedSeconds;states=$scheduler.states}
    $result.compiledAdmissionVerificationSeconds=[double](($scheduler.events|Where-Object event -CEQ 'compiled-verified'|Measure-Object -Property elapsedMs -Sum).Sum)/1000
    $groups=@($scheduler.groups)
    # All parent aggregation uses validated terminal worker evidence, never completion order.
    $validated=@();$seenDatabases=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($group in $groups){
        if($group.status -cne 'passed'){continue}
        $definition=@($registry.groups|Where-Object id -CEQ $group.id)[0]
        $cases=@(Read-JUnitCases "$($group.evidence)/failsafe-reports")
        Assert-Coverage $cases @([pscustomobject]@{id=$group.id;tests=@($definition.tests.new)})|Out-Null
        foreach($test in $definition.tests){if(@($cases|Where-Object{($_.classname+'#'+($_.name -replace '\(.*$','')) -ceq $test.new}).Count -ne $test.invocations){throw 'Missing or duplicate parameter invocation after worker completion'}}
        $created=@(Select-String -LiteralPath "$($group.evidence)/maven.txt" -Pattern '^D2_CREATED_DATABASE='|ForEach-Object{$_.Line.Substring(20)})
        Assert-DbGroupDatabases -ExpectedCount $definition.expectedDatabaseCount -Primary $(if($group.database){$group.database}else{'d2_it_'+('0'*32)}) -Created $created|Out-Null
        foreach($database in $created){if(-not $seenDatabases.Add($database)){throw 'Different worker groups created a shared database'}}
        if($definition.browser -and $group.id -cne 'package-runtime' -and $group.id -cne 'release-recovery'){
            Assert-MaintenanceBrowserLifecycle $group (Get-Content -Encoding utf8 "$($group.evidence)/site-maintenance-lifecycle.json" -Raw|ConvertFrom-Json) $validated|Out-Null
        }
        $validated+=$group
    }
    if($schedulerExit -ne 0 -or $scheduler.status -cne 'passed'){throw 'One or more scheduled database groups failed, cancelled or did not execute'}
    if (@($groups | Where-Object status -CNE 'passed').Count) { throw 'One or more isolated database groups failed' }
    $timings = @(foreach ($group in $groups) {
        $phases = @{}
        $writes = 0.0
        $file = Join-Path $group.evidence 'contract-timing.ndjson'
        if (Test-Path -LiteralPath $file) {
            foreach ($line in Get-Content -LiteralPath $file) {
                $row = $line | ConvertFrom-Json
                if ($row.category -ceq 'evidence-overlap') { $writes += $row.writeNanos / 1e9 }
                else { $phases[$row.category] += $row.exclusiveNanos / 1e9 }
            }
        }
        $suiteSeconds = 0.0; $methodSeconds = 0.0
        foreach ($xmlFile in Get-ChildItem "$($group.evidence)/failsafe-reports/TEST-*.xml") {
            [xml]$xml = Get-Content $xmlFile.FullName -Raw
            $suiteSeconds += [double]$xml.testsuite.time
            foreach ($case in $xml.testsuite.testcase) { $methodSeconds += [double]$case.time }
        }
        @{id=$group.id;groupElapsedSeconds=$group.seconds;classElapsedSeconds=$suiteSeconds;
            methodSumSeconds=$methodSeconds;exclusiveMeasuredSeconds=$phases;evidenceWriteOverlapSeconds=$writes;
            launcherAndReportRemainderSeconds=($group.seconds-$suiteSeconds)}
    })
    $timings | ConvertTo-Json -Depth 8 | Set-Content "$output/timing.json"
    $result.status='passed'
} catch { $result.error=$_.Exception.Message; throw }
finally {
    $compiledFailureMustThrow=$false
    if($compiledManifestSha){
        $verificationClock=[Diagnostics.Stopwatch]::StartNew()
        [IO.File]::WriteAllText("$output/common-build-verification.txt",'',[Text.UTF8Encoding]::new($false))
        & $node "$root/verification/CompiledBuild.cjs" verify $root $build $compiledManifest $compiledManifestSha "$output/common-build-verification.json" 2>&1|Set-Content -Encoding utf8 "$output/common-build-verification.txt"
        if($LASTEXITCODE -ne 0){$compiledFailureMustThrow=$result.status -ceq 'passed';$result.status='failed';$result.compiledBuildVerification='failed'}else{$result.compiledBuildVerification='passed'}
        $result.compiledBuildVerificationSeconds=$verificationClock.Elapsed.TotalSeconds
    }
    $result.groups=$groups
    $result.finished=(Get-Date).ToString('o')
    $result | ConvertTo-Json -Depth 9 | Set-Content "$output/summary.json"
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if($compiledFailureMustThrow){throw 'Shared compiled outputs or source changed; original evidence retained'}
}
"PASS isolated database groups: $output/summary.json"
