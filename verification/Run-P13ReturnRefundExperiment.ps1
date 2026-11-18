param(
    [string]$EvidenceDirectory = ".local-tools/p13-b-$([guid]::NewGuid().ToString('N'))",
    [int]$TestPostgresPort,
    [string]$TestPostgresInstance,
    [string]$TestPostgresContainer,
    [switch]$WorkerTier1,
    [switch]$WarmupOnly,
    [string]$Request
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/PowerShellRuntime.ps1"
. "$PSScriptRoot/Preservation.ps1"
. "$PSScriptRoot/WorkerJob.ps1"
. "$PSScriptRoot/Gate.ps1"
. "$PSScriptRoot/DbGroups.ps1"
. "$PSScriptRoot/CompiledClasspath.ps1"

$testClass = 'com.example.marketplace.dbtest.P13ReturnRefundExperiment'
$testMethod = 'measurePreparedCrashAndSameAttemptRecovery'
$testId = "$testClass#$testMethod"
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Evidence([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), $utf8)
}

function Protect-Output([string]$Value, [string[]]$Secrets) {
    foreach ($secret in $Secrets) {
        if (-not $secret) { continue }
        $json = ConvertTo-Json -InputObject $secret -Compress
        foreach ($literal in @($secret, [Security.SecurityElement]::Escape($secret), $json.Substring(1,$json.Length-2)) |
                Sort-Object -Property @{Expression={$_.Length};Descending=$true}, @{Expression={$_}} -Unique) {
            $Value = $Value.Replace($literal, '[REDACTED]')
        }
    }
    $Value
}

function Read-ContainerCpu($Endpoint) {
    # Aggregate container CPU includes fixture setup, other activity and observer overhead.
    $value = @(& docker exec $Endpoint.container cat /sys/fs/cgroup/cpu.stat 2>$null)
    $metrics = [ordered]@{}
    $source = $null
    if ($LASTEXITCODE -eq 0) {
        foreach ($line in $value) {
            if ($line -cmatch '^([a-z_]+) ([0-9]+)$') { $metrics[$Matches[1]] = [long]$Matches[2] }
        }
    }
    if ($metrics.Contains('usage_usec')) {
        $source = 'cgroup-v2-cpu.stat'
    } else {
        $usage = @(& docker exec $Endpoint.container cat /sys/fs/cgroup/cpuacct/cpuacct.usage 2>$null)
        if ($LASTEXITCODE -eq 0 -and $usage.Count -eq 1 -and $usage[0] -cmatch '^[0-9]+$') {
            $source = 'cgroup-v1-cpuacct'
            $metrics.usage_nsec = [long]$usage[0]
            $metrics.usage_usec = [decimal]$metrics.usage_nsec / 1000
        }
    }
    @{status=if ($source) {'observed'} else {'unavailable'}; source=$source; observedUtc=[DateTimeOffset]::UtcNow.ToString('o'); counters=$metrics}
}

if ($Request) {
    $null = Assert-VerificationPowerShellRuntime
    $data = Get-Content -LiteralPath $Request -Raw | ConvertFrom-Json
    $job = [MarketplaceVerification.WorkerJob]::Snapshot($data.jobName)
    if ($job.ProcessIds -notcontains $PID -or -not (Test-Path -LiteralPath (Join-Path (Split-Path $Request -Parent) 'job-admit.json'))) {
        throw 'Experiment phase requires an admitted kernel job'
    }
    $endpoint = Resolve-TestPostgresEndpoint -Port $data.endpoint.port -Instance $data.endpoint.instance -Container $data.endpoint.container
    if ($data.action -cin @('before','after')) {
        Push-Location $root
        try { Write-Evidence "$($data.folder)/snapshot.json" (Get-PreservationSnapshot -Endpoint $endpoint) }
        finally { Pop-Location }
        exit 0
    }

    $folder = $data.folder
    $build = Join-Path $folder 'build'
    $javaTmp = Join-Path $folder 'java-tmp'
    New-Item -ItemType Directory -Path $build,$javaTmp | Out-Null
    $arguments = @('-o','-B','-ntp','-Pdb-it',"-Dmaven.repo.local=$env:MAVEN_USER_HOME/repository",
        "-Dmarketplace.build.directory=$build","-Djava.io.tmpdir=$javaTmp")
    if ($data.action -ceq 'compile') {
        $arguments += 'test-compile'
    } elseif ($data.action -cmatch '^(warmup|run-[1-3])$') {
        if ($env:P13_REFUND_EXPERIMENT -cne $data.action -or $env:A3_EVIDENCE -ine $folder -or
                $data.workerJvmMode -cnotin @('baseline','tier1') -or $env:P13_REFUND_WORKER_JVM_MODE -cne $data.workerJvmMode -or
                $env:D2_DB_NAME -cnotmatch '^d2_it_[0-9a-f]{32}$' -or
                $env:D2_DB_URL -cne "jdbc:postgresql://127.0.0.1:$($endpoint.port)/$env:D2_DB_NAME") {
            throw 'Invalid isolated experiment environment'
        }
        $arguments += @("-Dmarketplace.failsafe.classesDirectory=$($data.commonBuild)/classes",
            "-Dmarketplace.failsafe.testClassesDirectory=$($data.commonBuild)/test-classes",
            "-Dmarketplace.failsafe.reports=$folder/failsafe-reports","-Dit.test=$testId",'failsafe:integration-test','failsafe:verify')
    } else { throw 'Unknown experiment phase' }
    $secrets = @($env:D2_DB_PASSWORD,$env:D2_ADMIN_PASSWORD)
    $observing = $data.action -cne 'compile'
    if ($observing) { Write-Evidence "$folder/container-cpu-before.json" (Read-ContainerCpu $endpoint) }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $code = $null
    Push-Location "$root/backend"
    try {
        & $data.maven @arguments 2>&1 | ForEach-Object { Protect-Output ([string]$_) $secrets } | Set-Content -Encoding utf8 "$folder/maven.txt"
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
        $seconds = $clock.Elapsed.TotalSeconds
        if ($observing) { Write-Evidence "$folder/container-cpu-after.json" (Read-ContainerCpu $endpoint) }
        Write-Evidence "$folder/maven-command.json" @{executable=$data.maven;arguments=$arguments;workingDirectory="$root/backend";exitCode=$code;elapsedSeconds=$seconds}
    }
    if ($code -ne 0) { throw 'Offline Maven phase failed' }
    exit 0
}

function Invoke-OwnedPhase([string]$Action, [string]$Folder, [int]$TimeoutSeconds, [string]$Database) {
    New-Item -ItemType Directory -Path $Folder | Out-Null
    $jobName = 'Local\marketplace-verify-' + [guid]::NewGuid().ToString('N')
    $requestPath = Join-Path $Folder 'request.json'
    Write-Evidence $requestPath @{action=$Action;folder=$Folder;maven=$maven;commonBuild=$commonBuild;endpoint=$endpoint;jobName=$jobName;workerJvmMode=$workerJvmMode}
    $requestSha = (Get-FileHash -LiteralPath $requestPath).Hash.ToLowerInvariant()
    $start = [Diagnostics.ProcessStartInfo]::new((Get-VerificationPowerShellPath))
    $start.WorkingDirectory = $root
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $utf8
    $start.StandardErrorEncoding = $utf8
    foreach ($argument in @('-NoProfile','-NonInteractive','-File',"$PSScriptRoot/Invoke-DbGroupWorker.ps1",
            '-Request',$requestPath,'-WorkerScript',$PSCommandPath)) { $start.ArgumentList.Add($argument) }
    foreach ($key in @($start.Environment.Keys)) {
        if ($key -match '^(D2_|A3_|P13_REFUND_|FIREBASE_|GCLOUD_PROJECT$|MAVEN_|MVNW_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|VERIFY_POWERSHELL_EVIDENCE$)') {
            [void]$start.Environment.Remove($key)
        }
    }
    $start.Environment['JAVA_HOME'] = $javaHome
    $start.Environment['MAVEN_USER_HOME'] = $mavenHome
    $start.Environment['MAVEN_SKIP_RC'] = 'true'
    if ($Database) {
        $trialEnvironment = @{
            D2_DB_NAME=$Database; D2_DB_URL="jdbc:postgresql://127.0.0.1:$($endpoint.port)/$Database";
            D2_DB_USERNAME='marketplace_test'; D2_DB_PASSWORD=$passwords.TEST_DB_PASSWORD;
            D2_ADMIN_PASSWORD=$passwords.TEST_ADMIN_PASSWORD; A3_EVIDENCE=$Folder;
            D2_TEST_POSTGRES_PORT=[string]$endpoint.port; D2_TEST_POSTGRES_INSTANCE=$endpoint.instance;
            D2_TEST_POSTGRES_CONTAINER=$endpoint.container; D2_DB_ENDPOINT_CONFIRMATION=$endpoint.confirmation;
            P13_REFUND_EXPERIMENT=$Action; P13_REFUND_WORKER_JVM_MODE=$workerJvmMode
        }
        foreach ($key in $trialEnvironment.Keys) { $start.Environment[$key] = $trialEnvironment[$key] }
    }

    $clock = [Diagnostics.Stopwatch]::StartNew()
    $child = [Diagnostics.Process]::Start($start)
    $stdout = $child.StandardOutput.ReadToEndAsync()
    $stderr = $child.StandardError.ReadToEndAsync()
    $admitted = $false
    $phase = [ordered]@{action=$Action;pid=$child.Id;timeoutSeconds=$TimeoutSeconds;status='failed'}
    try {
        $readyPath = Join-Path $Folder 'job-ready.json'
        while (-not (Test-Path -LiteralPath $readyPath)) {
            if ($child.HasExited -or $clock.ElapsedMilliseconds -ge 10000) { throw 'Experiment phase attachment failed or timed out' }
            Start-Sleep -Milliseconds 20
        }
        $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
        $members = [MarketplaceVerification.WorkerJob]::Snapshot($jobName)
        if ($ready.jobName -cne $jobName -or $ready.requestSha256 -cne $requestSha -or $ready.pid -ne $child.Id -or
                -not $ready.killOnJobClose -or $ready.breakawayAllowed -or $members.ProcessIds.Count -ne 1 -or
                $members.ProcessIds[0] -ne $child.Id) { throw 'Experiment phase job membership mismatch' }
        Write-Evidence "$Folder/job-admit.tmp" @{requestSha256=$requestSha}
        $admitted = $true
        [IO.File]::Move("$Folder/job-admit.tmp","$Folder/job-admit.json")
        $remaining = [Math]::Max(1, ($TimeoutSeconds * 1000) - [int]$clock.ElapsedMilliseconds)
        if (-not $child.WaitForExit($remaining)) { throw 'Experiment phase native timeout' }
        $phase.exitCode = $child.ExitCode
        if ($phase.exitCode -ne 0) { throw 'Owned experiment phase failed' }
        $final = Get-Content -LiteralPath "$Folder/job-final.json" -Raw | ConvertFrom-Json
        if ($final.status -cne 'passed' -or $final.jobName -cne $jobName -or $final.pid -ne $child.Id -or
                $final.requestSha256 -cne $requestSha -or $final.processExitCode -ne 0 -or @($final.descendantIds).Count) {
            throw 'Experiment phase descendant or exit proof failed'
        }
        $phase.status = 'passed'
    } catch {
        $phase.error = Protect-Output $_.Exception.Message @($passwords.Values)
        throw "Experiment phase $Action failed. See $Folder/phase.json"
    } finally {
        try {
            if ($admitted) { $phase.ownership = Stop-VerificationWorkerJob $jobName }
            if (-not $child.HasExited) { $child.Kill(); [void]$child.WaitForExit(2000) }
            $phase.processExited = $child.HasExited
            if ($phase.ownership.status -ceq 'unresolved' -or -not $phase.processExited) { throw 'Owned native process cleanup incomplete' }
        } catch {
            $phase.status = 'failed'
            $phase.cleanupError = Protect-Output $_.Exception.Message @($passwords.Values)
        } finally {
            foreach ($pair in @(@('stdout',$stdout),@('stderr',$stderr))) {
                if ($pair[1].Wait(2000)) {
                    [IO.File]::WriteAllText("$Folder/worker-$($pair[0]).txt", (Protect-Output $pair[1].Result @($passwords.Values)), $utf8)
                }
            }
            $phase.elapsedSeconds = $clock.Elapsed.TotalSeconds
            Write-Evidence "$Folder/phase.json" $phase
            $child.Dispose()
        }
        if ($phase.cleanupError) { throw "Experiment phase $Action cleanup failed. See $Folder/phase.json" }
    }
}

function Read-LogicalScale($Scale) {
    if ($Scale.database_bytes -le 0 -or -not $Scale.postgres_version -or $Scale.indexes -le 0 -or
        $Scale.successful_migrations -le 0 -or $Scale.index_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $Scale.migration_sha256 -cnotmatch '^[0-9a-f]{64}$' -or -not $Scale.rows.Count -or
        @($Scale.rows.Values | Where-Object { $_ -isnot [long] -or $_ -lt 0 }).Count) {
        throw 'Missing or invalid starting database scale'
    }
    $logical = [ordered]@{postgres_version=$Scale.postgres_version;indexes=$Scale.indexes;index_sha256=$Scale.index_sha256;
        successful_migrations=$Scale.successful_migrations;migration_sha256=$Scale.migration_sha256;rows=[ordered]@{}}
    foreach ($table in ($Scale.rows.Keys | Sort-Object -CaseSensitive)) { $logical.rows[$table] = $Scale.rows[$table] }
    $logical | ConvertTo-Json -Compress
}

function Read-SourceIdentity {
    $identity = & $node -e 'process.stdout.write(JSON.stringify(require(process.argv[1]).snapshotSources()))' "$PSScriptRoot/Preflight.cjs"
    if ($LASTEXITCODE -ne 0 -or -not $identity) { throw 'Experiment source inventory failed' }
    $identity
}

$endpoint = Resolve-TestPostgresEndpoint -Port $TestPostgresPort -Instance $TestPostgresInstance -Container $TestPostgresContainer
if ($endpoint.port -eq 15433) { throw 'Select the dedicated, validated PostgreSQL test instance' }
$output = [IO.Path]::GetFullPath($EvidenceDirectory, $root)
$local = [IO.Path]::GetFullPath('.local-tools', $root)
if ([IO.Path]::GetDirectoryName($output) -ine $local -or (Test-Path -LiteralPath $output) -or
        ((Get-Item -LiteralPath $local -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Use a fresh evidence directory directly under the repository .local-tools'
}
$null = Assert-VerificationPowerShellRuntime
New-Item -ItemType Directory -Path $output | Out-Null
$null = Assert-VerificationPowerShellRuntime -EvidencePath "$output/powershell.json"
Write-Output "EVIDENCE=$output"
$passwords = @{}
$workerJvmMode = if ($WorkerTier1) { 'tier1' } else { 'baseline' }
$executionOrder = if ($WarmupOnly) { @('warmup') } else { @('warmup','run-1','run-2','run-3') }
$result = [ordered]@{schemaVersion=1;status='failed';certificationEligible=$false;started=[DateTimeOffset]::UtcNow.ToString('o');
    testId=$testId;endpoint=$endpoint;workerJvmMode=$workerJvmMode;executionOrder=@($executionOrder);trials=@()}
$before = $null
$source = $null
$javaHome = Join-Path $local 'jdk-21.0.12.1+1'
$mavenHome = Join-Path $local 'm2'
$node = Join-Path $local 'node-v24.22.0-win-x64/node.exe'
$commonBuild = Join-Path $output 'compile/build'
$maven = $null
try {
    $distribution = @((Get-Content -LiteralPath "$root/backend/.mvn/wrapper/maven-wrapper.properties") -cmatch '^distributionUrl=')[0].Substring(16)
    $versioned = [IO.Path]::GetFileName($distribution) -creplace '-bin\.zip$',''
    $distributionHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($distribution))).ToLowerInvariant()
    $maven = Join-Path $mavenHome "wrapper/dists/$versioned/$distributionHash/bin/mvn.cmd"
    foreach ($tool in @("$javaHome/bin/java.exe",$node,$maven)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'Required prepared offline executable is missing' }
    }
    if (-not (Test-Path -LiteralPath "$mavenHome/repository" -PathType Container)) { throw 'Prepared offline Maven cache is missing' }
    $result.head = & git -c ("safe.directory=" + $root.Replace('\','/')) -C $root rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $result.head -cnotmatch '^[0-9a-f]{40}$') { throw 'Experiment Git identity failed' }
    $result.tools = @{java=@{path="$javaHome/bin/java.exe";sha256=(Get-FileHash -LiteralPath "$javaHome/bin/java.exe").Hash};
        maven=@{path=$maven;sha256=(Get-FileHash -LiteralPath $maven).Hash};node=$node}
    $source = Read-SourceIdentity
    [IO.File]::WriteAllText("$output/source.json", $source, $utf8)
    $result.sourceSha256 = (Get-FileHash -LiteralPath "$output/source.json").Hash.ToLowerInvariant()
    foreach ($line in Get-Content -LiteralPath "$root/.env") {
        if ($line -cmatch '^(TEST_ADMIN_PASSWORD|TEST_DB_PASSWORD)=(.+)$') { $passwords[$Matches[1]] = $Matches[2] }
    }
    if (-not $passwords.TEST_ADMIN_PASSWORD -or -not $passwords.TEST_DB_PASSWORD) { throw 'Required test database credentials are missing' }
    Invoke-OwnedPhase -Action before -Folder "$output/before" -TimeoutSeconds 300
    $before = Get-Content -LiteralPath "$output/before/snapshot.json" -Raw | ConvertFrom-Json -AsHashtable
    Invoke-OwnedPhase -Action compile -Folder "$output/compile" -TimeoutSeconds 300
    $logical = $null
    foreach ($action in $result.executionOrder) {
        if ((Read-SourceIdentity) -cne $source) { throw 'Experiment source changed before trial' }
        $folder = Join-Path $output "groups/$action"
        $database = 'd2_it_' + [guid]::NewGuid().ToString('N')
        $row = [ordered]@{trial=$action;database=$database;media=(Join-Path $folder 'media');evidence=$folder;
            includedInMeasurements=($action -cne 'warmup');status='failed'}
        $result.trials += $row
        try {
            Invoke-OwnedPhase -Action $action -Folder $folder -TimeoutSeconds 180 -Database $database
        } finally {
            foreach ($file in Get-ChildItem -LiteralPath $folder -File -Recurse | Where-Object Extension -in @('.txt','.xml','.json','.ndjson','.log','.args','.dump','.dumpstream')) {
                $raw = [IO.File]::ReadAllText($file.FullName)
                $safe = Protect-Output $raw @($passwords.Values)
                if ($safe -cne $raw) { [IO.File]::WriteAllText($file.FullName, $safe, $utf8) }
            }
        }
        $reports = Join-Path $folder 'failsafe-reports'
        $cases = @(Read-JUnitCases $reports)
        if ($cases.Count -ne 1 -or $cases[0].status -cne 'passed' -or $cases[0].classname -cne $testClass -or
                $cases[0].name -cne $testMethod) { throw 'Expected exactly one passing experiment case' }
        $row.junit = $cases[0]
        $row.classpath = Read-VerifiedCompiledClasspath -ReportDirectory $reports -ClassesDirectory "$commonBuild/classes" -TestClassesDirectory "$commonBuild/test-classes" -PrivateBuild "$folder/build"
        $created = @(Select-String -LiteralPath "$folder/maven.txt" -Pattern '^D2_CREATED_DATABASE=' | ForEach-Object { $_.Line.Substring(20) })
        $row.databaseProof = Assert-DbGroupDatabases -ExpectedCount 1 -Primary $database -Created $created
        $trial = Get-Content -LiteralPath "$folder/trial.json" -Raw | ConvertFrom-Json -AsHashtable
        if ($trial.status -cne 'passed' -or $trial.schemaVersion -ne 1 -or $trial.trial -cne $action -or
                $trial.workerJvmMode -cne $workerJvmMode -or
                $trial.database -cne $database -or $trial.media -ine $row.media -or -not (Test-Path -LiteralPath $row.media -PathType Container)) {
            throw 'Experiment report or private resources do not match the trial'
        }
        $row.startingScale = $trial.startingScale
        $trialScale = Read-LogicalScale $trial.startingScale
        if ($logical -and $trialScale -cne $logical) { throw 'Experiment starting logical scale differs between trials' }
        $logical = $trialScale
        $row.status = 'passed'
    }
    $result.status = 'passed'
} catch {
    $result.error = Protect-Output $_.Exception.Message @($passwords.Values)
} finally {
    if ($before) {
        try {
            Invoke-OwnedPhase -Action after -Folder "$output/after" -TimeoutSeconds 300
            $after = Get-Content -LiteralPath "$output/after/snapshot.json" -Raw | ConvertFrom-Json -AsHashtable
            Assert-Preservation $before $after
            $missing = @($result.trials | Where-Object { $_.status -ceq 'passed' -and $_.database -cnotin $after.selectedPostgres.databases })
            if ($missing.Count) { throw 'One or more experiment databases are missing from the final inventory' }
            $result.preservation = 'passed'
        } catch {
            $result.status = 'failed'
            $result.preservation = 'failed'
            $result.preservationError = Protect-Output $_.Exception.Message @($passwords.Values)
        }
    }
    if ($source) {
        try {
            $lastSource = Read-SourceIdentity
            [IO.File]::WriteAllText("$output/source-after.json", $lastSource, $utf8)
            if ($lastSource -cne $source) { throw 'Experiment source changed before final verification' }
            $result.sourceVerification = 'passed'
        } catch {
            $result.status = 'failed'
            $result.sourceVerification = 'failed'
            $result.sourceError = Protect-Output $_.Exception.Message @($passwords.Values)
        }
    }
    $result.finished = [DateTimeOffset]::UtcNow.ToString('o')
    Write-Evidence "$output/result.json" $result
}
if ($result.status -cne 'passed') { throw "Experiment failed. See $output/result.json" }
Write-Output "PASS experiment: $output/result.json"
