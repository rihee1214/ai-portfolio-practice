param(
    [string]$EvidenceDirectory = ".local-tools/d2-$([guid]::NewGuid().ToString('N'))",
    [switch]$Offline,
    [switch]$RunBrowser
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $root '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EvidenceDirectory must be inside the project .local-tools directory.'
}
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence directory; existing results are preserved.' }
New-Item -ItemType Directory -Path $output | Out-Null
$names = @('JAVA_HOME','MAVEN_USER_HOME','D2_DB_NAME','D2_DB_URL','D2_DB_USERNAME','D2_DB_PASSWORD','D2_ADMIN_PASSWORD','A3_RUN_BROWSER','A3_EVIDENCE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secrets = @{}
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^(TEST_ADMIN_PASSWORD|TEST_DB_PASSWORD)=(.+)$') { $secrets[$Matches[1]] = $Matches[2] }
    }
    foreach ($key in @('TEST_ADMIN_PASSWORD','TEST_DB_PASSWORD')) {
        if (-not $secrets[$key]) { throw "Missing $key in local root .env" }
    }
    $env:JAVA_HOME = Join-Path $root '.local-tools/jdk-21.0.12.1+1'
    $env:MAVEN_USER_HOME = Join-Path $root '.local-tools/m2'
    $env:D2_DB_NAME = 'd2_it_' + [guid]::NewGuid().ToString('N')
    $env:D2_DB_URL = "jdbc:postgresql://127.0.0.1:15433/$env:D2_DB_NAME"
    $env:D2_DB_USERNAME = 'marketplace_test'
    $env:D2_DB_PASSWORD = $secrets['TEST_DB_PASSWORD']
    $env:D2_ADMIN_PASSWORD = $secrets['TEST_ADMIN_PASSWORD']
    $env:A3_RUN_BROWSER = if ($RunBrowser) { 'true' } else { 'false' }
    $env:A3_EVIDENCE = $output
    "REQUESTED_DATABASE=$env:D2_DB_NAME" | Set-Content (Join-Path $output 'target.txt')
    $build = Join-Path $output 'build'
    $arguments = @('-B','-ntp','-Pdb-it','-Dmaven.repo.local=../.local-tools/m2/repository',"-Dmarketplace.build.directory=$build",'verify')
    if ($Offline) { $arguments = @('-o') + $arguments }
    Push-Location $PSScriptRoot
    try {
        & ./mvnw.cmd @arguments 2>&1 | ForEach-Object {
            $text = [string]$_
            foreach ($secret in $secrets.Values) { $text = $text.Replace($secret, '[REDACTED]') }
            $text
        } | Set-Content (Join-Path $output 'maven.txt')
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $counts = @()
    foreach ($phase in @('surefire','failsafe')) {
        foreach ($file in Get-ChildItem (Join-Path $build "$phase-reports/TEST-*.xml") -ErrorAction SilentlyContinue) {
            [xml]$report = Get-Content $file.FullName -Raw
            $counts += [pscustomobject]@{phase=$phase;name=$report.testsuite.name;tests=[int]$report.testsuite.tests;
                failures=[int]$report.testsuite.failures;errors=[int]$report.testsuite.errors;skipped=[int]$report.testsuite.skipped}
        }
    }
    $counts | ConvertTo-Json | Set-Content (Join-Path $output 'summary.json')
    $created = Select-String -Path (Join-Path $output 'maven.txt') -Pattern '^D2_CREATED_DATABASE=' | ForEach-Object Line
    if ($created) { $created | Add-Content (Join-Path $output 'target.txt') }
    $counts | Format-Table
    if ($code -ne 0) { throw "PostgreSQL integration verification failed; see $output/maven.txt" }
    if (($counts | Measure-Object failures -Sum).Sum -gt 0 -or
            ($counts | Measure-Object errors -Sum).Sum -gt 0 -or
            ($counts | Measure-Object skipped -Sum).Sum -gt 0) {
        throw 'Test reports contain failures, errors or skipped cases, even though Maven returned success.'
    }
    $integration = @($counts | Where-Object phase -eq 'failsafe')
    if (-not $integration.Count -or ($integration | Measure-Object tests -Sum).Sum -eq 0 -or
            ($integration | Measure-Object skipped -Sum).Sum -gt 0) {
        throw 'No complete PostgreSQL integration run was recorded.'
    }
    "PASS PostgreSQL integration verification; evidence: $output"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
