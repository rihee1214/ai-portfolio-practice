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
