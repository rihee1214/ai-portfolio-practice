function Get-PreservationSnapshot {
    $hashes = @{}
    foreach ($pair in @(@('db-dev','marketplace_dev'), @('db-test','marketplace_test'))) {
        $dump = & docker compose exec -T $pair[0] pg_dump -U postgres -d $pair[1] --no-owner --no-privileges
        if ($LASTEXITCODE -ne 0) { throw 'Database snapshot failed' }
        $normalized = ($dump | Where-Object { $_ -notmatch '^\\(un)?restrict ' }) -join "`n"
        $hashes[$pair[0]] = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))
    }
    $databases = @(& docker compose exec -T db-test psql -U postgres -d postgres -Atc 'SELECT datname FROM pg_database ORDER BY datname')
    if ($LASTEXITCODE -ne 0) { throw 'Database inventory failed' }
    $containers = @(& docker ps --format '{{.ID}} {{.Names}}' --filter name=moa-market-local)
    if ($LASTEXITCODE -ne 0) { throw 'Container inventory failed' }
    $ports = @(Get-NetTCPConnection -State Listen | Where-Object LocalPort -in @(8080,4174,5174,15432,15433) |
        ForEach-Object { "$($_.LocalAddress)|$($_.LocalPort)|$($_.OwningProcess)" } | Sort-Object)
    $media = @{}
    foreach ($run in Get-ChildItem .local-tools -Directory) {
        foreach ($relative in @('media', 'db/media')) {
            $path = Join-Path $run.FullName $relative
            if (Test-Path -LiteralPath $path) {
                foreach ($file in Get-ChildItem -LiteralPath $path -File -Recurse | Where-Object Name -ne '.lock') {
                    $media[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName).Hash
                }
            }
        }
    }
    @{ hashes=$hashes; databases=$databases; containers=$containers; ports=$ports; media=$media }
}

function Assert-Preservation($Before, $After) {
    foreach ($key in $Before.hashes.Keys) {
        if ($Before.hashes[$key] -ne $After.hashes[$key]) { throw "Existing database changed: $key" }
    }
    if (@($Before.databases | Where-Object { $_ -notin $After.databases }).Count) { throw 'Existing database missing' }
    foreach ($name in @('containers','ports')) {
        if (@(Compare-Object @($Before[$name]) @($After[$name])).Count) { throw "Existing $name changed" }
    }
    foreach ($key in $Before.media.Keys) {
        if ($Before.media[$key] -ne $After.media[$key]) { throw "Existing media changed: $key" }
    }
}
