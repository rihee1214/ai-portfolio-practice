param([string]$EvidenceDirectory = '.local-tools/c3-verification')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $root '.local-tools')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EvidenceDirectory must be inside the project .local-tools directory.'
}
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence directory to preserve previous results.' }
New-Item -ItemType Directory -Path $output | Out-Null
$build = Join-Path $root '.local-tools/c3-build'
$javaHomeBefore = $env:JAVA_HOME
$mavenHomeBefore = $env:MAVEN_USER_HOME
$adminBefore = $env:C3_TEST_ADMIN_PASSWORD
$passwordBefore = $env:C3_TEST_DB_PASSWORD
$secrets = @{}
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^(TEST_ADMIN_PASSWORD|TEST_DB_PASSWORD)=(.+)$') {
            $secrets[$Matches[1]] = $Matches[2]
        }
    }
    foreach ($key in @('TEST_ADMIN_PASSWORD', 'TEST_DB_PASSWORD')) {
        if (-not $secrets[$key]) { throw "Missing $key in the local root .env" }
    }
    $env:JAVA_HOME = Join-Path $root '.local-tools/jdk-21.0.12.1+1'
    $env:MAVEN_USER_HOME = Join-Path $root '.local-tools/m2'
    Push-Location $PSScriptRoot
    try {
        & ./mvnw.cmd -o -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' "-Dmarketplace.build.directory=$build" verify > (Join-Path $output 'build.txt') 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $output/build.txt" }
    } finally { Pop-Location }
    $libs = Join-Path $output 'lib'
    New-Item -ItemType Directory -Path $libs | Out-Null
    $jar = [IO.Compression.ZipFile]::OpenRead((Join-Path $build 'marketplace-api-0.0.1-SNAPSHOT.jar'))
    try {
        foreach ($entry in $jar.Entries) {
            if ($entry.FullName -match '^BOOT-INF/lib/[^/]+\.jar$') {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $libs $entry.Name))
            }
        }
    } finally { $jar.Dispose() }
    $classpath = "$build/test-classes;$build/classes;$libs/*"
    & "$env:JAVA_HOME/bin/java.exe" -cp $classpath com.example.marketplace.jdbcfixture.TargetContract > (Join-Path $output 'guard.txt') 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Target guard failed before any database creation: $output/guard.txt" }
    $env:C3_TEST_ADMIN_PASSWORD = $secrets['TEST_ADMIN_PASSWORD']
    $env:C3_TEST_DB_PASSWORD = $secrets['TEST_DB_PASSWORD']
    & "$env:JAVA_HOME/bin/java.exe" "-Dc3.output=$output" -cp $classpath com.example.marketplace.jdbcfixture.JdbcContract > (Join-Path $output 'runtime.txt') 2>&1
    $result = $LASTEXITCODE
    # Sanitize before displaying or retaining captured runtime errors.
    $log = Get-Content -LiteralPath (Join-Path $output 'runtime.txt') -Raw
    foreach ($secret in $secrets.Values) { $log = $log.Replace($secret, '[REDACTED]') }
    Set-Content -LiteralPath (Join-Path $output 'runtime.txt') -Value $log
    Get-Content -LiteralPath (Join-Path $output 'guard.txt')
    if (Test-Path (Join-Path $output 'results.txt')) { Get-Content (Join-Path $output 'results.txt') }
    if ($result -ne 0) { throw "C3 contract failed; see $output/runtime.txt" }
    "C3 complete; evidence: $output"
} finally {
    $env:JAVA_HOME = $javaHomeBefore
    $env:MAVEN_USER_HOME = $mavenHomeBefore
    $env:C3_TEST_ADMIN_PASSWORD = $adminBefore
    $env:C3_TEST_DB_PASSWORD = $passwordBefore
}
