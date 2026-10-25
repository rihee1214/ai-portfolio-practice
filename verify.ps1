param(
    [string]$EvidenceDirectory = ".local-tools/harness-$([guid]::NewGuid().ToString('N'))",
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/verification/Gate.ps1"
. "$PSScriptRoot/verification/Preservation.ps1"
$output = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be inside .local-tools' }
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory' }
New-Item -ItemType Directory -Path $output | Out-Null
$oldPath = $env:PATH
$result = @{ status='failed'; started=(Get-Date).ToString('o'); scope='Existing implementation through P04-D'; evidence=$output }
function Get-Inputs {
    $files = @(& git ls-files --cached --others --exclude-standard -- backend frontend verification verify.ps1)
    if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed' }
    @($files | Sort-Object -Unique | ForEach-Object {
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
    $before = Get-PreservationSnapshot
    $before | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'before.json')
    & "$PSScriptRoot/verification/Test-Gate.ps1" | Set-Content (Join-Path $output 'gate-tests.txt')
    & "$PSScriptRoot/backend/verify-db.ps1" -EvidenceDirectory "$EvidenceDirectory/db" -Offline:$Offline -RunBrowser
    $java = @(Read-JUnitCases "$output/db/build/surefire-reports") + @(Read-JUnitCases "$output/db/build/failsafe-reports")
    $requirements = Get-Content "$PSScriptRoot/verification/required-tests.json" -Raw | ConvertFrom-Json
    $coverage = @(Assert-Coverage $java $requirements)
    $coverage | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'coverage.json')
    $java | ConvertTo-Json | Set-Content (Join-Path $output 'java-cases.json')
    $browser = Get-Content "$output/db/browser-report.json" -Raw | ConvertFrom-Json
    if ($browser.errors.Count -or $browser.stats.expected -lt 22 -or $browser.stats.skipped -or $browser.stats.unexpected -or $browser.stats.flaky) {
        throw 'Browser gate failed (minimum current baseline: 22, no skips or retries accepted)'
    }
    $browserCases = @(Read-BrowserCases $browser.suites)
    $browserRequired = Get-Content "$PSScriptRoot/verification/required-browser.json" -Raw | ConvertFrom-Json
    Assert-Coverage $browserCases $browserRequired -ExactNames | ConvertTo-Json -Depth 6 | Set-Content "$output/browser-coverage.json"
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
    $result.browser = $browser.stats.expected
    $result.status = 'passed'
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    try {
        if ($before) {
            $after = Get-PreservationSnapshot
            $after | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'after.json')
            Assert-Preservation $before $after
            $result.preservation = 'passed'
        }
    } catch {
        $result.status = 'failed'; $result.preservation = 'failed'; $result.preservationError = $_.Exception.Message
        throw
    } finally {
        $result.finished = (Get-Date).ToString('o')
        $result | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'result.json')
        $env:PATH = $oldPath
        Pop-Location
    }
}
"PASS integrated harness: $output/result.json"
