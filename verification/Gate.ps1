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
