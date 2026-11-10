param(
    [string]$EvidenceDirectory = ".local-tools/d2-$([guid]::NewGuid().ToString('N'))",
    [switch]$Offline,
    [switch]$RunBrowser,
    [string]$DbGroups,
    [switch]$ReverseGroups,
    [int]$TestPostgresPort = 15433,
    [string]$TestPostgresInstance = '',
    [string]$TestPostgresContainer = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. "$root/verification/DatabaseTarget.ps1"
. "$root/verification/DbGroups.ps1"
. "$root/verification/Gate.ps1"
$registry = Get-DbGroupRegistry
Assert-DbGroupRegistration -Registry $registry -Root $root
$selectionArgs = @{Registry=$registry}
if ($PSBoundParameters.ContainsKey('DbGroups')) { $selectionArgs.Selection=$DbGroups }
$selection = Resolve-DbGroups @selectionArgs
$endpoint = Resolve-TestPostgresEndpoint -Port $TestPostgresPort -Instance $TestPostgresInstance -Container $TestPostgresContainer
$output = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $root '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be inside .local-tools' }
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory' }
New-Item -ItemType Directory -Path $output | Out-Null
$names = @('JAVA_HOME','MAVEN_USER_HOME','D2_DB_NAME','D2_DB_URL','D2_DB_USERNAME','D2_DB_PASSWORD','D2_ADMIN_PASSWORD','A3_RUN_BROWSER','A3_EVIDENCE','D2_TEST_POSTGRES_PORT','D2_TEST_POSTGRES_INSTANCE','D2_DB_ENDPOINT_CONFIRMATION','MAVEN_ARGS','P09_F_BROWSER_ONLY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secrets = @{}
$groups = @()
$result = @{status='failed';started=(Get-Date).ToString('o');selection=$selection;groups=@()}
function Invoke-ContractMaven([string[]]$Goals, [string]$Log, [string[]]$Properties=@()) {
    $arguments = @('-B','-ntp','-Pdb-it','-Dmaven.repo.local=../.local-tools/m2/repository',"-Dmarketplace.build.directory=$build") + $Properties + $Goals
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
    $env:D2_DB_ENDPOINT_CONFIRMATION = $endpoint.confirmation
    $env:D2_DB_USERNAME = 'marketplace_test'
    $env:D2_DB_PASSWORD = $secrets['TEST_DB_PASSWORD']
    $env:D2_ADMIN_PASSWORD = $secrets['TEST_ADMIN_PASSWORD']
    $build = Join-Path $output 'build'
    $compileClock = [Diagnostics.Stopwatch]::StartNew()
    $compileExit = Invoke-ContractMaven -Goals @('package') -Log "$output/maven.txt"
    $result.commonBuildSeconds = $compileClock.Elapsed.TotalSeconds
    if ($compileExit -ne 0) { throw 'Common compile, Checkstyle, unit or architecture tests failed' }
    $common = @(Read-JUnitCases "$build/surefire-reports")
    $ordered = @($selection.selected)
    if ($ReverseGroups) { [array]::Reverse($ordered) }
    foreach ($id in $ordered) {
        $definition = @($registry.groups | Where-Object id -CEQ $id)[0]
        $folder = Join-Path $output "groups/$id"
        New-Item -ItemType Directory -Path $folder | Out-Null
        $env:D2_DB_NAME = 'd2_it_' + [guid]::NewGuid().ToString('N')
        $env:D2_DB_URL = "jdbc:postgresql://127.0.0.1:$($endpoint.port)/$env:D2_DB_NAME"
        $env:A3_EVIDENCE = $folder
        # The registered browser contract always runs its browser assertions, including standalone execution.
        $env:A3_RUN_BROWSER = if ($id -ceq 'browser-api') { 'true' } else { 'false' }
        $group = @{id=$id;status='failed';database=$env:D2_DB_NAME;media="$folder/media";evidence=$folder;started=(Get-Date).ToString('o');classes=$definition.classes}
        $clock = [Diagnostics.Stopwatch]::StartNew()
        try {
            $reports = Join-Path $folder 'failsafe-reports'
            $props = @("-Dit.test=$($definition.classes -join ',')","-Dmarketplace.failsafe.reports=$reports")
            $group.exitCode = Invoke-ContractMaven -Goals @('failsafe:integration-test','failsafe:verify') -Properties $props -Log "$folder/maven.txt"
            if ($group.exitCode -ne 0) { throw 'Group JVM failed' }
            $created = @(Select-String -Path "$folder/maven.txt" -Pattern '^D2_CREATED_DATABASE=' | ForEach-Object { $_.Line.Substring(20) })
            $group.databaseProof = Assert-DbGroupDatabases -ExpectedCount $definition.expectedDatabaseCount -Primary $env:D2_DB_NAME -Created $created
            if ($id -cne 'phone-provider') {
                $identity = Get-Content "$folder/execution-identity.json" -Raw | ConvertFrom-Json
                if ($identity.database -cne $env:D2_DB_NAME -or $identity.media -cne "$folder\media") { throw 'Group scope identity mismatch' }
                $group.pid = $identity.pid
                $group.httpPort = $identity.httpPort
            } else { $group.database=$null; $group.media=$null }
            $cases = @(Read-JUnitCases $reports)
            $expected = @($definition.tests.new)
            Assert-Coverage $cases @([pscustomobject]@{id=$id;tests=$expected}) | ConvertTo-Json -Depth 5 | Set-Content "$folder/coverage.json"
            foreach ($case in $cases) {
                $actual = $case.classname+'#'+($case.name -replace '\(.*$','')
                if ($expected -cnotcontains $actual) { throw "Unexpected executed test: $actual" }
            }
            foreach ($test in $definition.tests) {
                $actualCases = @($cases | Where-Object { ($_.classname+'#'+($_.name -replace '\(.*$','')) -ceq $test.new })
                if ($actualCases.Count -ne $test.invocations) { throw "Missing or duplicate parameter invocation: $($test.new)" }
            }
            if ($id -ceq 'browser-api') {
                $browserReport = Get-Content "$folder/browser-report.json" -Raw | ConvertFrom-Json
                if ($browserReport.errors.Count -or $browserReport.stats.skipped -or $browserReport.stats.unexpected -or $browserReport.stats.flaky) { throw 'Browser group failed or retried' }
                $browserCases = @(Read-BrowserCases $browserReport.suites)
                $browserRequired = Get-Content "$root/verification/required-browser.json" -Raw | ConvertFrom-Json
                Assert-Coverage $browserCases $browserRequired -ExactNames | ConvertTo-Json -Depth 6 | Set-Content "$folder/browser-coverage.json"
            }
            $cases | ConvertTo-Json -Depth 4 | Set-Content "$folder/java-cases.json"
            $group.tests = $cases.Count
            $group.status = 'passed'
        } catch { $group.error=$_.Exception.Message }
        finally {
            $group.seconds=$clock.Elapsed.TotalSeconds
            $group.finished=(Get-Date).ToString('o')
            $group | ConvertTo-Json -Depth 5 | Set-Content "$folder/result.json"
            $groups += $group
        }
    }
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
    $result.groups=$groups
    $result.finished=(Get-Date).ToString('o')
    $result | ConvertTo-Json -Depth 9 | Set-Content "$output/summary.json"
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
"PASS isolated database groups: $output/summary.json"
