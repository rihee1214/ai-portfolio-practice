function Assert-Coverage($Cases, $Requirements, [switch]$ExactNames) {
    if (-not @($Cases).Count) { throw 'No executed test cases' }
    if (-not @($Requirements).Count) { throw 'No constraint mappings' }
    if (@($Cases | Where-Object status -ne 'passed').Count) { throw 'Failed, skipped or unknown test case' }
    $ids = @{}
    foreach ($requirement in $Requirements) {
        if (-not $requirement.id -or $ids.ContainsKey($requirement.id)) { throw 'Missing or duplicate constraint ID' }
        $ids[$requirement.id] = $true
        if (-not @($requirement.tests).Count) { throw "Empty mapping: $($requirement.id)" }
        foreach ($test in $requirement.tests) {
            $found = @($Cases | Where-Object {
                $name = if ($ExactNames) { $_.name } else { $_.name -replace '\(.*$', '' }
                ($_.classname + '#' + $name) -ceq $test
            })
            if (-not $found.Count) { throw "Missing required test: $test" }
        }
        [pscustomobject]@{ id=$requirement.id; status='passed'; tests=$requirement.tests }
    }
}

function Read-BrowserCases($Suites) {
    foreach ($suite in $Suites) {
        foreach ($spec in $suite.specs) {
            foreach ($test in $spec.tests) {
                $status = if ($test.status -eq 'expected' -and $test.expectedStatus -eq 'passed' -and
                    @($test.results).Count -eq 1 -and $test.results[0].status -eq 'passed') { 'passed' } else { 'failed' }
                [pscustomobject]@{ classname=$test.projectName; name=($spec.file.Replace('\','/') + ':' + $spec.title); status=$status }
            }
        }
        Read-BrowserCases $suite.suites
    }
}

function Assert-BrowserDeadlineEvidence($Report, $BrowserCases) {
    if ($Report.status -cne 'passed' -or @($Report.problems).Count -ne 0 -or $Report.scopes -le 0 -or
        @($Report.tests).Count -ne @($BrowserCases).Count) { throw 'Missing or failed browser deadline evidence' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($test in $Report.tests) {
        $key = $test.project + '#' + [IO.Path]::GetFileName($test.file.Replace('\','/')) + ':' + $test.title
        if ($test.status -cne 'passed' -or $test.operations -le 0 -or -not $seen.Add($key)) { throw 'Unmeasured, failed or duplicate deadline test' }
    }
    foreach ($case in $BrowserCases) {
        $colon = $case.name.IndexOf(':')
        if ($colon -lt 0) { throw 'Invalid browser case name' }
        $key = $case.classname + '#' + [IO.Path]::GetFileName($case.name.Substring(0,$colon).Replace('\','/')) + $case.name.Substring($colon)
        if ($case.status -cne 'passed' -or -not $seen.Contains($key)) { throw 'Deadline evidence differs from the actual browser report' }
    }
}

function Read-JUnitCases([string]$Directory) {
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml')
    if (-not $files.Count) { throw "Missing JUnit XML: $Directory" }
    foreach ($file in $files) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        $suites = @($xml.SelectNodes('//testsuite'))
        if (-not $suites.Count) { throw "Missing test suite: $file" }
        foreach ($suite in $suites) {
            if ([int]$suite.failures -gt 0 -or [int]$suite.errors -gt 0 -or [int]$suite.skipped -gt 0) { throw "Nonpassing suite: $file" }
            $cases = @($suite.SelectNodes('testcase'))
            if (-not $cases.Count -or $cases.Count -ne [int]$suite.tests) { throw "Empty or inconsistent suite: $file" }
            foreach ($case in $cases) {
                $status = if ($case.SelectSingleNode('failure|error|skipped')) { 'failed' } else { 'passed' }
                [pscustomobject]@{ classname=[string]$case.classname; name=[string]$case.name; status=$status; report=$file.FullName }
            }
        }
    }
}

function Read-VerifiedBrowserBundle([string]$Folder) {
    $base = Join-Path $Folder 'browser-app'
    $record = Get-Content -LiteralPath (Join-Path $Folder 'browser-app-build.json') -Raw | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath (Join-Path $base '.vite/manifest.json') -Raw | ConvertFrom-Json
    if ($record.mode -cne 'development' -or $record.nodeEnv -cne 'development' -or $record.buildMs -le 0 -or
        $manifest.'index.html'.isEntry -ne $true -or $manifest.'index.html'.file -cne $record.entry) {
        throw 'Current development browser build evidence is missing or inconsistent'
    }
    $expected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($file in $record.files) {
        if (-not $expected.TryAdd([string]$file.file,$file)) { throw 'Duplicate browser build asset' }
    }
    $actual = @(Get-ChildItem -LiteralPath $base -File -Recurse -Force)
    if ($actual.Count -ne $expected.Count -or -not $expected.ContainsKey('index.html') -or -not $expected.ContainsKey($record.entry)) {
        throw 'Browser build file set differs from manifest'
    }
    foreach ($file in $actual) {
        $relative = [IO.Path]::GetRelativePath($base,$file.FullName).Replace('\','/')
        if (-not $expected.ContainsKey($relative) -or $expected[$relative].bytes -ne $file.Length -or
            $expected[$relative].sha256 -ine (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
            throw "Browser build asset changed: $relative"
        }
    }
    @{mode=$record.mode;entry=$record.entry;assets=$actual.Count;buildMs=$record.buildMs;
      manifestSha256=(Get-FileHash -LiteralPath (Join-Path $Folder 'browser-app-build.json')).Hash}
}

function Assert-BrowserGroupCoverage($Group, $Cases, $Requirements) {
    $project = $Group.browser.project
    $required = @($Requirements | ForEach-Object {
        $tests = @($_.tests | Where-Object { -not $project -or $_.StartsWith($project+'#',[StringComparison]::Ordinal) })
        if ($tests.Count) { [pscustomobject]@{id=$_.id;tests=$tests} }
    })
    $expected = @($required.tests)
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($case in $Cases) {
        $id = $case.classname+'#'+$case.name
        if (($project -and $case.classname -cne $project) -or $id -cnotin $expected -or -not $seen.Add($id)) {
            throw "Unexpected, foreign or duplicate browser case in $($Group.id): $id"
        }
    }
    Assert-Coverage $Cases $required -ExactNames
}

function Read-VerifiedPackageBundle([string]$Folder) {
    $record = Get-Content -LiteralPath "$Folder/package-dist.json" -Raw | ConvertFrom-Json
    $proof = Get-Content -LiteralPath "$Folder/package-proof.json" -Raw | ConvertFrom-Json
    if ($record.mode -cne 'production' -or $proof.runtimeMode -cne 'public-demo' -or
        $proof.apiJarSha256 -cne $proof.workerJarSha256 -or $proof.workerWebServer -ne $false -or
        $proof.apiPid -eq $proof.workerPid -or $proof.apiProfiles -cne 'db' -or $proof.workerProfiles -cne 'db,worker') {
        throw 'Actual package artifact and separated role proof is missing or inconsistent'
    }
    if ((Get-FileHash -LiteralPath $proof.jar).Hash -ine $proof.apiJarSha256) { throw 'Packaged JAR changed' }
    $files = @(Get-ChildItem -LiteralPath "$Folder/browser-app" -Recurse -Force -File)
    if ($files.Count -ne $record.files.Count -or $record.files.file -cnotcontains 'index.html' -or
        $record.files.file -cnotcontains '.vite/manifest.json') { throw 'Package dist file inventory differs' }
    foreach ($file in $files) {
        $name = [IO.Path]::GetRelativePath("$Folder/browser-app", $file.FullName).Replace('\','/')
        $expected = @($record.files | Where-Object file -CEQ $name)
        if ($expected.Count -ne 1 -or $expected[0].bytes -ne $file.Length -or
            $expected[0].sha256 -ine (Get-FileHash -LiteralPath $file.FullName).Hash) { throw "Package dist changed: $name" }
    }
    $legacy = Get-Content -LiteralPath "$Folder/package-legacy-dist.json" -Raw | ConvertFrom-Json
    if ($legacy.sourceInputsSha256 -cne '93d5f36020475dc792e3fa2276f14a510d2ed51d12e59cd890e3cf8af32142a0' -or
        (Get-FileHash -LiteralPath $legacy.sourceInputs).Hash -ine $legacy.sourceInputsSha256 -or
        $legacy.retainedBuildMode -cne 'development') { throw 'Retained previous bundle source provenance differs' }
    $legacyFiles = @(Get-ChildItem -LiteralPath "$Folder/browser-legacy-app" -Recurse -Force -File)
    if ($legacyFiles.Count -ne $legacy.files.Count -or $legacy.files.file -cnotcontains 'index.html') { throw 'Previous dist inventory differs' }
    foreach ($file in $legacyFiles) {
        $name = [IO.Path]::GetRelativePath("$Folder/browser-legacy-app", $file.FullName).Replace('\','/')
        $expected = @($legacy.files | Where-Object file -CEQ $name)
        $original = Join-Path $legacy.source $name
        if ($expected.Count -ne 1 -or $expected[0].bytes -ne $file.Length -or
            $expected[0].sha256 -ine (Get-FileHash -LiteralPath $file.FullName).Hash -or
            $expected[0].sha256 -ine (Get-FileHash -LiteralPath $original).Hash) { throw "Previous bundle or preserved source changed: $name" }
    }
    $owner = Get-Content -LiteralPath "$Folder/proxy-owner.json" -Raw | ConvertFrom-Json
    if ($owner.mediaMount -ne $false -or $owner.databaseMount -ne $false -or
        $owner.apiPort -ne $proof.apiPort -or $owner.httpsPort -ne $proof.httpsPort) { throw 'Package proxy resource boundary differs' }
    @{mode='production';assets=$files.Count;jarSha256=$proof.apiJarSha256;runtimeMode=$proof.runtimeMode}
}

function Assert-ReleaseOperationTests([string]$Report) {
    [xml]$xml = Get-Content -LiteralPath $Report -Raw
    $required = Get-Content "$PSScriptRoot/required-release-operations.json" -Raw | ConvertFrom-Json
    $cases = @($xml.SelectNodes('//testcase'))
    if (-not $cases.Count -or @($xml.SelectNodes('//failure|//error|//skipped')).Count -or
        @($xml.SelectNodes('//testsuite') | Where-Object { [int]$_.failures -gt 0 -or [int]$_.errors -gt 0 -or [int]$_.skipped -gt 0 }).Count) {
        throw 'Missing, failed or skipped release operation assertions'
    }
    if ($cases.Count -ne $required.tests.Count) { throw 'Release operation assertion inventory changed without registration' }
    foreach ($name in $required.tests) {
        if (@($cases | Where-Object name -CEQ $name).Count -ne 1) { throw "Missing or duplicate release operation assertion: $name" }
    }
    @{status='passed';tests=$cases.Count;required=$required.tests}
}

function Assert-ExpectedReleaseFailures($Results, $Faults) {
    $expected = @(
        @{id=$Faults.mixedCheckpoint.commandId;action='Restore';error='Media namespace mismatch'},
        @{id=$Faults.schemaReadiness.commandId;action='Activate';error='Schema version mismatch'}
    )
    if (@($expected.id | Where-Object { $_ -cmatch '^[0-9a-f-]{36}$' } | Sort-Object -Unique).Count -ne 2) { throw 'Expected operator failure identities missing or duplicate' }
    foreach ($item in $expected) {
        $actual = @($Results | Where-Object commandId -CEQ $item.id)
        if ($actual.Count -ne 1 -or $actual[0].status -cne 'failed' -or $actual[0].action -cne $item.action -or
            $actual[0].error -cne $item.error) { throw 'Expected operator fault did not fail at its asserted boundary' }
    }
    if (@($Results | Where-Object { $_.status -cne 'passed' -and $_.commandId -cnotin $expected.id }).Count) { throw 'Unexpected failed operator command' }
    @{expectedFailures=2;commandIds=$expected.id}
}

function Assert-ReleaseRecoveryEvidence([string]$Folder, $Group) {
    $lifecycle = Get-Content -LiteralPath "$Folder/release-lifecycle.json" -Raw | ConvertFrom-Json
    $artifacts = Get-Content -LiteralPath "$Folder/release-artifacts.json" -Raw | ConvertFrom-Json
    $faults = Get-Content -LiteralPath "$Folder/operator-faults.json" -Raw | ConvertFrom-Json
    $cancellationFiles=@(Get-ChildItem -LiteralPath $Folder -File -Filter 'operation-cancellation-*.json')
    if ($cancellationFiles.Count -ne 1) { throw 'Release operator cancellation/join evidence missing or duplicated' }
    Assert-ReleaseOperationCancellation (Get-Content -LiteralPath $cancellationFiles[0].FullName -Raw)
    if ($lifecycle.stacks.Count -ne 4 -or $lifecycle.stacks.mode -cnotcontains 'L' -or
        $lifecycle.stacks.mode -cnotcontains 'P' -or $lifecycle.originalResourcesPreserved -ne $true -or
        $artifacts.localGuest.mode -cne 'development' -or $lifecycle.failedRestoreTargets.Count -ne 1 -or
        $faults.mixedCheckpoint.apiStarted -ne $false -or $faults.mixedCheckpoint.sourceRowsUnchanged -ne $true -or
        $faults.schemaReadiness.apiWorkerIngressRunning -ne $false -or $faults.schemaReadiness.sourceRowsUnchanged -ne $true) { throw 'Release lifecycle modes and isolated targets missing' }
    foreach ($field in @('database','project','container','root','mediaPath','postgresPort','httpsPort')) {
        if (@((@($lifecycle.stacks)+@($lifecycle.failedRestoreTargets)).target.$field | Sort-Object -Unique).Count -ne 5) { throw "Release targets share $field" }
    }
    if ($lifecycle.sourceDatabase -cne $lifecycle.stacks[0].target.database -or
        $lifecycle.sourceMedia -cne $lifecycle.stacks[0].target.mediaPath) { throw 'Release primary target identity mismatch' }
    foreach ($release in @('R0','R1','localGuest')) {
        $record = $artifacts.$release
        $actual = @(Get-ChildItem -LiteralPath $record.dist -Recurse -Force -File)
        if ($actual.Count -ne $record.files.Count -or $record.files.path -cnotcontains 'index.html') { throw "Release $release inventory missing" }
        foreach ($file in $actual) {
            $relative = [IO.Path]::GetRelativePath($record.dist,$file.FullName).Replace('\','/')
            $expected = @($record.files | Where-Object path -CEQ $relative)
            if ($expected.Count -ne 1 -or $expected[0].size -ne $file.Length -or
                $expected[0].sha256 -ine (Get-FileHash -LiteralPath $file.FullName).Hash) { throw "Release $release bytes changed: $relative" }
        }
        if ($release -cne 'localGuest' -and (Get-FileHash -LiteralPath $record.jar).Hash -ine $record.sha256) { throw "Release $release JAR changed" }
    }
    if ((Get-FileHash -LiteralPath $artifacts.candidateInputs).Hash -ine $artifacts.candidateInputsSha256) { throw 'Release candidate input manifest changed' }
    $requirements = Get-Content "$PSScriptRoot/required-browser-release.json" -Raw | ConvertFrom-Json
    $allCases = @()
    foreach ($mode in @('L','P')) {
        $scope = Join-Path $Folder "browser-$mode"
        $report = Get-Content "$scope/browser-report.json" -Raw | ConvertFrom-Json
        if ($report.errors.Count -or $report.stats.skipped -or $report.stats.unexpected -or $report.stats.flaky) { throw "Release $mode browser failed or retried" }
        $cases = @(Read-BrowserCases $report.suites)
        Assert-BrowserDeadlineEvidence (Get-Content "$scope/browser-deadlines.json" -Raw | ConvertFrom-Json) $cases
        $allCases += $cases
    }
    Assert-BrowserGroupCoverage $Group $allCases $requirements | ConvertTo-Json -Depth 6 | Set-Content "$Folder/browser-coverage.json"
    $results = @(Get-ChildItem -LiteralPath $Folder -Directory -Filter 'operation-*' | ForEach-Object {
        Get-Content -LiteralPath (Join-Path $_.FullName 'result.json') -Raw | ConvertFrom-Json
    })
    Assert-ExpectedReleaseFailures -Results $results -Faults $faults | ConvertTo-Json -Depth 4 | Set-Content "$Folder/operator-fault-coverage.json"
    $observerResults = @(Get-ChildItem -LiteralPath $Folder -File -Filter 'observer-operation-*.json' | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
    $preservationResults = @($observerResults | Where-Object action -CIn @('Rollback','Restore'))
    if ($preservationResults.Count -ne 3 -or @($preservationResults | Where-Object action -CEQ 'Rollback').Count -ne 1 -or
        @($preservationResults | Where-Object action -CEQ 'Restore').Count -ne 2) { throw 'Mandatory rollback and both restore preservation proofs missing' }
    foreach ($result in $preservationResults) {
        Assert-ReleasePreservationProof $result.preservation -Action $result.action
        if ($result.action -ceq 'Restore') {
            $selected = @($observerResults | Where-Object { $_.mode -ceq $result.mode -and $_.action -ceq 'Checkpoint' -and $_.checkpointRef.commandId -ceq $result.checkpointRef.commandId })
            if ($selected.Count -ne 1 -or $selected[0].checkpointRef.manifestSha256 -cne $result.checkpointRef.manifestSha256 -or
                $selected[0].quiescedSnapshot.full.sha256 -cne $result.preservation.boundaryExact.beforeDigest -or
                $selected[0].quiescedSnapshot.snapshotId -cne $result.preservation.live.beforeSnapshotId -or
                $result.preservation.sourceStopped.passed -ne $true -or
                $result.preservation.sourceStopped.beforeSnapshotId -ceq $result.preservation.live.beforeSnapshotId -or
                $result.preservation.sourceStopped.beforeDigest -cne $result.preservation.sourceStopped.afterDigest) { throw 'Restore did not retain the selected original checkpoint and stopped separate source' }
        }
    }
    $preservationResults | ConvertTo-Json -Depth 20 | Set-Content "$Folder/preservation-coverage.json"
    if (@('Prepare','Checkpoint','Restore','Activate','Rollback' | Where-Object { $_ -cnotin $results.action }).Count) { throw 'Actual release command evidence missing or failed' }
    foreach ($stack in $lifecycle.stacks) {
        $owner = Get-Content -LiteralPath (Join-Path $stack.target.root 'ownership.json') -Raw | ConvertFrom-Json
        $state = Get-Content -LiteralPath (Join-Path $stack.target.root 'state.json') -Raw | ConvertFrom-Json
        $expectedState = if ($stack.target.project -ceq $faults.schemaReadiness.targetProject) { 'QUIESCED' } else { 'CHECKPOINTED' }
        if ($owner.runId -cne $stack.runId -or $owner.target.database -cne $stack.target.database -or
            $state.state -cne $expectedState -or -not $state.artifactIdentities.Count) { throw 'Actual release ownership, activation or final quiescence proof missing' }
        $pointer = Get-Content -LiteralPath (Join-Path $stack.target.root 'active-release.json') -Raw | ConvertFrom-Json
        if (-not $pointer.controllerPayloadSha256 -or -not $pointer.operationalPayloadSha256) { throw 'Active release lacks actual immutable operational payload identity' }
    }
    @{modes=@('L','P');targets=5;expectedOperatorFaults=2;browserCases=$allCases.Count;operations=$results.Count;candidateInputsSha256=$artifacts.candidateInputsSha256}
}

function Read-ReleaseOperationInstant([string]$Value) {
    if ($Value -cnotmatch '\A(?<whole>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\.(?<fraction>[0-9]{1,9}))?(?<zone>Z|[+-][0-9]{2}:[0-9]{2})\z') {
        throw 'Invalid release operation timestamp'
    }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $whole = [DateTimeOffset]::ParseExact($Matches.whole + $Matches.zone, "yyyy-MM-dd'T'HH:mm:ssK", $culture)
    $fraction = [decimal]::Parse('0.' + ([string]$Matches.fraction).PadRight(9, '0'), $culture)
    # Java Instant emits up to nine digits. DateTimeOffset only stores seven.
    [decimal]$whole.ToUnixTimeSeconds() + $fraction
}

function Assert-ReleaseOperationCancellation([string]$ProofJson) {
    $Proof = ConvertFrom-Json -InputObject $ProofJson -DateKind String -ErrorAction Stop
    $failure = 'Release target cleanup began without finite completed operator and handler cancellation'
    if ($Proof.status -cne 'passed' -or $Proof.limitMs -ne 10000 -or $Proof.activeOperations -ne 0 -or $Proof.activeRequests -ne 0 -or
        $Proof.executorTerminated -ne $true -or @($Proof.observedHandles | Where-Object alive -NE $false).Count -or
        @($Proof.handlerThreads | Where-Object alive -NE $false).Count) { throw $failure }
    $elapsedSeconds = (Read-ReleaseOperationInstant $Proof.ended) - (Read-ReleaseOperationInstant $Proof.started)
    if ($elapsedSeconds -lt 0 -or $elapsedSeconds -gt 10) { throw $failure }
}

function Assert-ReleasePreservationProof($Proof, [string]$Action='') {
    $audit = @('claim_exchange_refund_recovery_run','claim_exchange_refund_recovery_stage','claim_exchange_refund_recovery_page',
        'claim_exchange_refund_recovery_page_complete','claim_return_recovery_run','claim_return_recovery_stage',
        'claim_return_recovery_page','claim_return_recovery_page_complete','claim_return_recovery_candidate','job_claim_fact',
        'job','outbox_event','dashboard_run','dashboard_snapshot','dashboard_metric','dashboard_head','notification_inbox')
    if ($Proof.boundaryExact.passed -ne $true -or $Proof.boundaryExact.beforeDigest -cnotmatch '^[0-9a-f]{64}$' -or
        $Proof.boundaryExact.beforeDigest -cne $Proof.boundaryExact.afterDigest -or
        $Proof.boundaryExact.tableInventory.Count -lt 1 -or $Proof.boundaryExact.sequencesEqual -ne $true -or $Proof.boundaryExact.mediaEqual -ne $true -or
        $Proof.live.passed -ne $true -or $Proof.live.allOldRowsPreserved -ne $true -or $Proof.live.coverageComplete -ne $true -or
        $Proof.live.unregisteredChanges.Count -ne 0 -or $Proof.live.beforeSnapshotId -cnotmatch '^[0-9a-f-]{36}$' -or
        $Proof.live.afterSnapshotId -cnotmatch '^[0-9a-f-]{36}$') { throw 'Exact quiet and typed live preservation assertions missing or failed' }
    $keys = @($Proof.live.typedResults.PSObject.Properties.Name | Sort-Object)
    if (($keys -join ',') -cne (@($Proof.live.typeRegistry) -join ',') -or @($audit | Where-Object { $_ -cnotin $keys }).Count) { throw 'Typed preservation registry coverage incomplete' }
    foreach ($table in $keys) {
        $validator=$Proof.live.typedResults.$table
        if ($validator.passed -ne $true -or -not $validator.validator -or $null -eq $validator.addedIds -or $null -eq $validator.changedIds) { throw "Typed row preservation validator failed: $table" }
    }
    if ($Action -ceq 'Rollback' -and ($Proof.live.inputSnapshotId -cnotmatch '^[0-9a-f-]{36}$' -or
        $Proof.live.inputSnapshotId -ceq $Proof.live.beforeSnapshotId -or
        $Proof.live.beforeSnapshotId -cne $Proof.boundaryExact.beforeSnapshotId -or
        $Proof.live.quietBaselineDigest -cne $Proof.boundaryExact.afterDigest)) { throw 'Rollback live proof is not bound to the actual operator quiet baseline and original input' }
}

function Assert-MaintenanceBrowserLifecycle($Group, $Proof, $CompletedGroups) {
    $expectedProject = $Group.id -creplace '^site-maintenance-',''
    if ($expectedProject -cnotin @('desktop','mobile') -or $Proof.project -cne $expectedProject -or
        $Proof.database -cne $Group.database -or $Proof.pid -ne $Group.pid -or $Proof.httpPort -ne $Group.httpPort -or
        [IO.Path]::GetFullPath($Proof.media) -cne [IO.Path]::GetFullPath($Group.media) -or $Proof.initialPolicy.version -ne 1 -or $Proof.initialPolicy.baseFee -ne 3000 -or
        $Proof.initialPolicy.freeThreshold -ne 50000 -or $Proof.finalPolicy.version -ne 2 -or
        $Proof.finalPolicy.baseFee -ne 4567 -or $Proof.finalPolicy.freeThreshold -ne 51000 -or
        $Proof.orders -lt 2 -or $Proof.businessRowsBefore -ne 0 -or $Proof.originalPolicyReset -ne $false) {
        throw 'Maintenance browser did not retain its own fresh migration policy and final changed data'
    }
    foreach ($previous in $CompletedGroups) {
        foreach ($field in @('database','pid','media','evidence')) {
            if ($null -ne $previous.$field -and $previous.$field -ceq $Group.$field) { throw "Browser scope reused $field from $($previous.id)" }
        }
    }
    [pscustomobject]@{project=$Proof.project;freshPolicy=$true;finalPolicyPreserved=$true;ownedDatabase=$Proof.database}
}
