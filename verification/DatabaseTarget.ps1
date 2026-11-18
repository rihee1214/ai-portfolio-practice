# Test-only endpoint selection. No arbitrary host, URL, database or application role is accepted.
function Resolve-TestPostgresEndpoint {
    param([int]$Port = 15433, [string]$Instance = '', [string]$Container = '', [switch]$ResourceOnlyDiagnostic)
    if ($Port -lt 1024 -or $Port -gt 65535 -or $Port -eq 15432) { throw 'Unsafe test PostgreSQL port' }
    if ($Port -eq 15433) {
        if ($Instance -or $Container -or $ResourceOnlyDiagnostic) { throw 'Shared endpoint cannot be selected as an isolated instance or resource-only diagnostic' }
        $Instance = 'default'
    } else {
        if ($Instance -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
                $Container -cnotmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}$' -or $Container.StartsWith('moa-market-local')) {
            throw 'An isolated test endpoint requires an explicit UUID instance and separate container'
        }
    }
    [pscustomobject]@{port=$Port;instance=$Instance;container=$Container;confirmation="127.0.0.1:$Port|$Instance"}
}

function Get-SelectedPostgresSnapshot($Endpoint) {
    if ($Endpoint.port -eq 15433) { return $null }
    $raw = & docker inspect --format '{{json .}}' $Endpoint.container
    if ($LASTEXITCODE -ne 0) { throw 'Selected PostgreSQL container inventory failed' }
    # Only these nonsecret identity fields are persisted; Config.Env is deliberately excluded.
    $state = $raw | ConvertFrom-Json
    $bindings = @($state.NetworkSettings.Ports.'5432/tcp')
    if (-not $state.State.Running -or $state.Config.Labels.'marketplace.verification_instance' -cne $Endpoint.instance -or
            $bindings.Count -ne 1 -or $bindings[0].HostIp -cne '127.0.0.1' -or $bindings[0].HostPort -cne [string]$Endpoint.port) {
        throw 'Selected container does not exclusively expose the confirmed isolated loopback endpoint'
    }
    $identity = @(& docker exec $Endpoint.container psql -U postgres -d postgres -Atc "SELECT current_setting('marketplace.verification_instance', true), system_identifier::text FROM pg_control_system()")
    if ($LASTEXITCODE -ne 0 -or $identity.Count -ne 1 -or $identity[0] -cnotmatch ('^'+[regex]::Escape($Endpoint.instance)+'\|[0-9]+$')) {
        throw 'Selected PostgreSQL instance marker or system identifier is invalid'
    }
    $databases = @(& docker exec $Endpoint.container psql -U postgres -d postgres -Atc 'SELECT datname FROM pg_database ORDER BY datname')
    if ($LASTEXITCODE -ne 0 -or 'marketplace_test' -notin $databases) { throw 'Selected PostgreSQL baseline database missing' }
    $dump = & docker exec $Endpoint.container pg_dump -U postgres -d marketplace_test --no-owner --no-privileges
    if ($LASTEXITCODE -ne 0) { throw 'Selected PostgreSQL baseline snapshot failed' }
    $normalized = ($dump | Where-Object { $_ -notmatch '^\\(un)?restrict ' }) -join "`n"
    @{container=$state.Id;image=$state.Image;started=$state.State.StartedAt;restarts=$state.RestartCount;shm=$state.HostConfig.ShmSize;
        port=$Endpoint.port;instance=$Endpoint.instance;systemIdentifier=$identity[0].Split('|')[1];databases=$databases;
        baselineHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))}
}

function Assert-SelectedPostgresPreservation($Before, $After) {
    if (-not $Before -and -not $After) { return }
    if (-not $Before -or -not $After) { throw 'Selected PostgreSQL snapshot missing' }
    foreach ($key in @('container','image','started','restarts','shm','port','instance','systemIdentifier','baselineHash')) {
        if ($Before[$key] -cne $After[$key]) { throw "Selected PostgreSQL resource changed: $key" }
    }
    if (@($Before.databases | Where-Object { $_ -notin $After.databases }).Count) { throw 'Selected PostgreSQL previous database missing' }
}

function Assert-FullVerificationAllowed([switch]$ResourceOnlyDiagnostic) {
    if ($ResourceOnlyDiagnostic) { throw 'Resource-only diagnostic cannot certify an integrated whole verification; original database dumps were intentionally not performed' }
}

function New-PhoneHarnessCommand {
    param([string]$Root, [string]$EvidenceDirectory, $Endpoint, [switch]$Offline, [switch]$ResourceOnlyDiagnostic, [string]$DbGroups, [string]$ChangedContracts, [switch]$ReverseGroups,
        [ValidateSet('1','2')][string]$GroupConcurrency='2')
    . "$PSScriptRoot/PowerShellRuntime.ps1"
    $command = '"' + (Get-VerificationPowerShellPath) + '" -NoProfile -File "' + (Join-Path $Root 'verify.ps1') + '" -PhoneEmulatorReady -EvidenceDirectory "' + $EvidenceDirectory + '"'
    $command += ' -GroupConcurrency '+$GroupConcurrency
    if ($Offline) { $command += ' -Offline' }
    $command += ' -TestPostgresPort ' + $Endpoint.port
    if ($Endpoint.port -ne 15433) {
        $command += ' -TestPostgresInstance ' + $Endpoint.instance + ' -TestPostgresContainer ' + $Endpoint.container
    }
    if ($ResourceOnlyDiagnostic) { $command += ' -ResourceOnlyDiagnostic' }
    foreach ($key in @('DbGroups','ChangedContracts')) {
        if ($PSBoundParameters.ContainsKey($key)) {
            $value = $PSBoundParameters[$key]
            if ($value -cnotmatch '^[a-z][a-z0-9-]*(,[a-z][a-z0-9-]*)*$') { throw 'Unsafe or empty group argument' }
            $command += ' -' + $key + ' ' + $value
        }
    }
    if ($ReverseGroups) { $command += ' -ReverseGroups' }
    $command
}
