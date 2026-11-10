. "$PSScriptRoot/DatabaseTarget.ps1"
function Get-PreservationSnapshot {
    param($Endpoint = (Resolve-TestPostgresEndpoint), [switch]$ResourceOnlyDiagnostic)
    $selected = Get-SelectedPostgresSnapshot $Endpoint
    $hashes = @{}
    $developmentDatabases = @()
    $databases = @()
    if (-not $ResourceOnlyDiagnostic) {
    foreach ($pair in @(@('db-dev','marketplace_dev'), @('db-test','marketplace_test'))) {
        $dump = & docker compose exec -T $pair[0] pg_dump -U postgres -d $pair[1] --no-owner --no-privileges
        if ($LASTEXITCODE -ne 0) { throw 'Database snapshot failed' }
        $normalized = ($dump | Where-Object { $_ -notmatch '^\\(un)?restrict ' }) -join "`n"
        $hashes[$pair[0]] = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))
    }
    $developmentDatabases = @(& docker compose exec -T db-dev psql -U postgres -d postgres -Atc 'SELECT datname FROM pg_database ORDER BY datname')
    if ($LASTEXITCODE -ne 0) { throw 'Development database inventory failed' }
    foreach ($database in $developmentDatabases | Where-Object { $_ -match '^app_[0-9a-f]{32}$' }) {
        $dump = & docker compose exec -T db-dev pg_dump -U postgres -d $database --no-owner --no-privileges
        if ($LASTEXITCODE -ne 0) { throw 'Demo database snapshot failed' }
        $normalized = ($dump | Where-Object { $_ -notmatch '^\\(un)?restrict ' }) -join "`n"
        $hashes["demo:$database"] = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))
    }
    $databases = @(& docker compose exec -T db-test psql -U postgres -d postgres -Atc 'SELECT datname FROM pg_database ORDER BY datname')
    if ($LASTEXITCODE -ne 0) { throw 'Database inventory failed' }
    }
    $containers = @(& docker ps --format '{{.ID}} {{.Names}}' --filter name=moa-market-local)
    if ($LASTEXITCODE -ne 0) { throw 'Container inventory failed' }
    $ports = @(Get-NetTCPConnection -State Listen | Where-Object LocalPort -in @(8080,4174,5174,15432,15433,$Endpoint.port) |
        ForEach-Object { "$($_.LocalAddress)|$($_.LocalPort)|$($_.OwningProcess)" } | Sort-Object)
    $media = @{}
    foreach ($run in Get-ChildItem .local-tools -Directory) {
        $relatives = @('media', 'db/media')
        foreach ($parent in @('db/groups','groups')) {
            $groupRoot = Join-Path $run.FullName $parent
            if (Test-Path -LiteralPath $groupRoot) {
                $relatives += @(Get-ChildItem -LiteralPath $groupRoot -Directory | ForEach-Object { "$parent/$($_.Name)/media" })
            }
        }
        foreach ($relative in $relatives) {
            $path = Join-Path $run.FullName $relative
            if (Test-Path -LiteralPath $path) {
                foreach ($file in Get-ChildItem -LiteralPath $path -File -Recurse | Where-Object Name -ne '.lock') {
                    $media[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName).Hash
                }
            }
        }
    }
    @{ hashes=$hashes; databases=$databases; developmentDatabases=$developmentDatabases; containers=$containers; ports=$ports; media=$media;
        selectedPostgres=$selected; resourceOnlyDiagnostic=[bool]$ResourceOnlyDiagnostic }
}

function Assert-Preservation($Before, $After) {
    if ([bool]$Before.resourceOnlyDiagnostic -ne [bool]$After.resourceOnlyDiagnostic) { throw 'Preservation scope changed' }
    Assert-SelectedPostgresPreservation $Before.selectedPostgres $After.selectedPostgres
    foreach ($key in $Before.hashes.Keys) {
        if ($Before.hashes[$key] -ne $After.hashes[$key]) { throw "Existing database changed: $key" }
    }
    if (@($Before.databases | Where-Object { $_ -notin $After.databases }).Count) { throw 'Existing database missing' }
    if (@($Before.developmentDatabases | Where-Object { $_ -notin $After.developmentDatabases }).Count) { throw 'Existing development database missing' }
    foreach ($name in @('containers','ports')) {
        if (@(Compare-Object @($Before[$name]) @($After[$name])).Count) { throw "Existing $name changed" }
    }
    foreach ($key in $Before.media.Keys) {
        if ($Before.media[$key] -ne $After.media[$key]) { throw "Existing media changed: $key" }
    }
}
