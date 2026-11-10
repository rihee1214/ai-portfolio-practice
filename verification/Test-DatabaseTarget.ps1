$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DatabaseTarget.ps1"
. "$PSScriptRoot/Preservation.ps1"
function Must-RejectEndpoint([string]$Name, [scriptblock]$Check) {
    $rejected = $false
    try { & $Check | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Expected rejection: $Name" }
    "PASS $Name"
}
$instance = '4abe1092-7cdf-414b-8a2d-e9130af4012e'
$default = Resolve-TestPostgresEndpoint
if ($default.confirmation -cne '127.0.0.1:15433|default') { throw 'Default changed' }
'PASS unchanged default endpoint'
$target = Resolve-TestPostgresEndpoint -Port 26543 -Instance $instance -Container 'p09-e-diagnostic-unit'
Resolve-TestPostgresEndpoint -Port 26543 -Instance $instance -Container 'p09-e-diagnostic-unit' -ResourceOnlyDiagnostic | Out-Null
Assert-FullVerificationAllowed
'PASS isolated endpoint supports normal full and separate diagnostic modes'
Must-RejectEndpoint 'diagnostic cannot certify whole PASS' { Assert-FullVerificationAllowed -ResourceOnlyDiagnostic }
Must-RejectEndpoint 'shared diagnostic forbidden' { Resolve-TestPostgresEndpoint -ResourceOnlyDiagnostic }
Must-RejectEndpoint 'protected development port' { Resolve-TestPostgresEndpoint -Port 15432 -Instance $instance -Container 'new-test' }
Must-RejectEndpoint 'missing instance' { Resolve-TestPostgresEndpoint -Port 26543 -Container 'new-test' }
Must-RejectEndpoint 'missing container' { Resolve-TestPostgresEndpoint -Port 26543 -Instance $instance }
Must-RejectEndpoint 'container shell expression' { Resolve-TestPostgresEndpoint -Port 26543 -Instance $instance -Container 'a;echo' }
Must-RejectEndpoint 'existing compose server' { Resolve-TestPostgresEndpoint -Port 26543 -Instance $instance -Container 'moa-market-local-db-test-1' }
Must-RejectEndpoint 'malformed instance' { Resolve-TestPostgresEndpoint -Port 26543 -Instance 'not-a-uuid' -Container 'new-test' }
Must-RejectEndpoint 'shared instance override' { Resolve-TestPostgresEndpoint -Port 15433 -Instance $instance -Container 'new-test' }
foreach ($port in @(0,1023,65536)) { Must-RejectEndpoint "invalid port $port" { Resolve-TestPostgresEndpoint -Port $port -Instance $instance -Container 'new-test' } }
$command = New-PhoneHarnessCommand -Root (Split-Path $PSScriptRoot -Parent) -EvidenceDirectory '.local-tools/endpoint-unit' -Endpoint $target -Offline
foreach ($expected in @('-TestPostgresPort 26543',"-TestPostgresInstance $instance",'-TestPostgresContainer p09-e-diagnostic-unit','-Offline','-PhoneEmulatorReady')) {
    if (-not $command.Contains($expected)) { throw "Phone child lost explicit argument: $expected" }
}
if ($command.Contains('-ResourceOnlyDiagnostic')) { throw 'Endpoint selection silently enabled limited preservation' }
$diagnostic = New-PhoneHarnessCommand -Root (Split-Path $PSScriptRoot -Parent) -EvidenceDirectory '.local-tools/endpoint-unit' -Endpoint $target -ResourceOnlyDiagnostic
if (-not $diagnostic.Contains('-ResourceOnlyDiagnostic')) { throw 'Diagnostic scope was dropped' }
'PASS actual emulator command preserves exact endpoint and independent scope'

# Container identity tests are intentionally connection-free. The external command is replaced only in this test scope.
$script:endpointCalls = @()
$script:reportedMarker = $instance
$script:reportedLabel = $instance
$script:reportedHost = '127.0.0.1'
function docker {
    $script:endpointCalls += ($args -join ' ')
    if ($args[0] -eq 'inspect') {
        @{Id='isolated-container-id';Image='sha256:cached-image';State=@{Running=$true;StartedAt='fixed-start'};RestartCount=0;
            Config=@{Labels=@{'marketplace.verification_instance'=$script:reportedLabel}};HostConfig=@{ShmSize=67108864};
            NetworkSettings=@{Ports=@{'5432/tcp'=@(@{HostIp=$script:reportedHost;HostPort='26543'})}}} | ConvertTo-Json -Depth 8 -Compress
    } elseif ($args[0] -eq 'ps') { 'original-container-id moa-market-local-db-test-1' }
    elseif ($args[0] -eq 'compose' -and ($args -join ' ').Contains('SELECT datname')) {
        if ($args -contains 'db-dev') { @('marketplace_dev','postgres') } else { @('marketplace_test','d2_it_existing','postgres') }
    } elseif (($args -join ' ').Contains('pg_control_system')) { "$script:reportedMarker|123456789" }
    elseif (($args -join ' ').Contains('SELECT datname')) { @('marketplace_test','postgres','template0','template1') }
    elseif (($args -join ' ').Contains('pg_dump')) { @('\restrict random-nonce','baseline','\unrestrict random-nonce') }
    else { throw 'Unexpected endpoint command' }
    $global:LASTEXITCODE = 0
}
function Get-NetTCPConnection { [pscustomobject]@{LocalAddress='127.0.0.1';LocalPort=15433;OwningProcess=111} }
try {
    $before = Get-SelectedPostgresSnapshot $target
    $after = Get-SelectedPostgresSnapshot $target
    Assert-SelectedPostgresPreservation $before $after
    if ($before.systemIdentifier -cne '123456789' -or $before.shm -ne 67108864) { throw 'Actual identity fields lost' }
    $script:endpointCalls=@();$normal=Get-PreservationSnapshot -Endpoint $target
    if (-not @($script:endpointCalls | Where-Object { $_.StartsWith('compose') -and $_.Contains('db-dev') -and $_.Contains('pg_dump') }).Count -or
            -not @($script:endpointCalls | Where-Object { $_.StartsWith('compose') -and $_.Contains('db-test') -and $_.Contains('pg_dump') }).Count -or
            $normal.hashes.Count -ne 2 -or -not $normal.selectedPostgres -or $normal.resourceOnlyDiagnostic) { throw 'Custom full endpoint lost original or selected preservation' }
    $script:endpointCalls=@();$limited=Get-PreservationSnapshot -Endpoint $target -ResourceOnlyDiagnostic
    if (@($script:endpointCalls | Where-Object { $_.StartsWith('compose') }).Count -or $limited.hashes.Count -or $limited.databases.Count -or $limited.developmentDatabases.Count -or
            -not $limited.selectedPostgres -or -not $limited.resourceOnlyDiagnostic -or -not $limited.containers.Count -or -not $limited.ports.Count) { throw 'Resource-only diagnostic scope is incorrect' }
    $script:endpointCalls=@();$original=Get-PreservationSnapshot
    if ($original.selectedPostgres -or $original.hashes.Count -ne 2 -or $original.databases.Count -ne 3 -or
            @($script:endpointCalls | Where-Object { $_.StartsWith('inspect') -or $_.StartsWith('exec') }).Count) { throw 'Default preservation changed' }
    'PASS real snapshot branches: default full, selected full preserving both, diagnostic without shared database calls'
    foreach ($key in @('container','image','started','restarts','shm','port','instance','systemIdentifier','baselineHash')) {
        $changed = $after.Clone(); $changed[$key] = 'changed'
        Must-RejectEndpoint "selected resource mutation $key" { Assert-SelectedPostgresPreservation $before $changed }
    }
    $changed=$after.Clone();$changed.databases=@('postgres')
    Must-RejectEndpoint 'selected previous database deletion' { Assert-SelectedPostgresPreservation $before $changed }
    $script:endpointCalls=@();$script:reportedMarker='wrong'
    Must-RejectEndpoint 'actual instance mismatch before database dump' { Get-SelectedPostgresSnapshot $target }
    if ($script:endpointCalls.Count -ne 2 -or @($script:endpointCalls | Where-Object { $_.Contains('pg_dump') -or $_.Contains('SELECT datname') }).Count) { throw 'Mismatched instance reached data snapshot' }
    $script:reportedMarker=$instance;$script:reportedHost='0.0.0.0';$script:endpointCalls=@()
    Must-RejectEndpoint 'nonloopback binding before SQL' { Get-SelectedPostgresSnapshot $target }
    if ($script:endpointCalls.Count -ne 1) { throw 'Unsafe binding reached SQL' }
    $script:reportedHost='127.0.0.1';$script:reportedLabel='wrong';$script:endpointCalls=@()
    Must-RejectEndpoint 'container instance label mismatch before SQL' { Get-SelectedPostgresSnapshot $target }
    if ($script:endpointCalls.Count -ne 1) { throw 'Unsafe container reached SQL' }
    'PASS selected container/instance/sysid checked before writable preparation'
} finally { Remove-Item Function:docker; Remove-Item Function:Get-NetTCPConnection }

# The root and DB scripts must both carry the three independently validated endpoint parameters.
foreach ($path in @((Join-Path (Split-Path $PSScriptRoot -Parent) 'verify.ps1'),(Join-Path $PSScriptRoot 'Run-PhoneEmulator.ps1'),(Join-Path (Split-Path $PSScriptRoot -Parent) 'backend/verify-db.ps1'))) {
    $tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw "Invalid endpoint script: $path" }
    foreach ($name in @('TestPostgresPort','TestPostgresInstance','TestPostgresContainer')) {
        if ($name -notin $tree.ParamBlock.Parameters.Name.VariablePath.UserPath) { throw "Endpoint parameter dropped: $path $name" }
    }
}
'PASS root/emulator/backend endpoint entry parameters and syntax'

$oldSnapshot=@{hashes=@{db='same'};databases=@('old');developmentDatabases=@('development');containers=@('container');ports=@('port');media=@{image='hash'}}
$newSnapshot=$oldSnapshot.Clone();$newSnapshot.resourceOnlyDiagnostic=$false;$newSnapshot.selectedPostgres=$null
Assert-Preservation $oldSnapshot $newSnapshot
Must-RejectEndpoint 'normal and resource-only snapshots cannot be interchanged' { $changed=$newSnapshot.Clone();$changed.resourceOnlyDiagnostic=$true;Assert-Preservation $oldSnapshot $changed }
foreach ($key in @('hashes','databases','developmentDatabases','containers','ports','media')) {
    Must-RejectEndpoint "normal preservation retains $key" {
        $changed=$newSnapshot.Clone()
        switch($key) {
            'hashes' { $changed.hashes=@{db='changed'} }
            'media' { $changed.media=@{image='changed'} }
            default { $changed[$key]=@('different') }
        }
        Assert-Preservation $oldSnapshot $changed
    }
}
'PASS historical baseline compatibility and unchanged full preservation assertions'
