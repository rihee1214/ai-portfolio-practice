$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DbGroups.ps1"
function Must-RejectGroup([string]$Label, [scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Accepted invalid selection: $Label" }
}
$registry = Get-DbGroupRegistry
$badBrowser = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
$isolatedIndex = [array]::IndexOf(@($badBrowser.groups.id),'site-maintenance-desktop')
$badBrowser.groups[$isolatedIndex].browser.project='desktop,mobile'
Must-RejectGroup 'multiple projects in singleton policy group' { Assert-DbGroupRegistration -Registry $badBrowser }
$badBrowser.groups[$isolatedIndex].browser.project='desktop'
$badBrowser.groups[$isolatedIndex].classes += 'com.example.marketplace.dbtest.SiteManagementIT'
Must-RejectGroup 'multiple execution classes in isolated browser group' { Assert-DbGroupRegistration -Registry $badBrowser }
$primary = 'd2_it_' + ('a' * 32)
$created = @($primary) + @(1..13 | ForEach-Object { 'd2_it_' + $_.ToString('x32') })
$proof = Assert-DbGroupDatabases -ExpectedCount 14 -Primary $primary -Created $created
if ($proof.auxiliary.Count -ne 13) { throw 'Auxiliary database ownership lost' }
Assert-DbGroupDatabases -ExpectedCount 1 -Primary $primary -Created @($primary) | Out-Null
Assert-DbGroupDatabases -ExpectedCount 0 -Primary $primary -Created @() | Out-Null
Must-RejectGroup 'missing primary database' { Assert-DbGroupDatabases -ExpectedCount 1 -Primary $primary -Created @($created[1]) }
Must-RejectGroup 'duplicate database' { Assert-DbGroupDatabases -ExpectedCount 2 -Primary $primary -Created @($primary,$primary) }
Must-RejectGroup 'external database' { Assert-DbGroupDatabases -ExpectedCount 1 -Primary 'production' -Created @('production') }
Must-RejectGroup 'wrong database count' { Assert-DbGroupDatabases -ExpectedCount 14 -Primary $primary -Created @($primary) }
Must-RejectGroup 'phone database creation' { Assert-DbGroupDatabases -ExpectedCount 0 -Primary $primary -Created @($primary) }
$missingCount = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
$missingCount.groups[0].PSObject.Properties.Remove('expectedDatabaseCount')
Must-RejectGroup 'missing expected database count' { Assert-DbGroupRegistration -Registry $missingCount }
$all = Resolve-DbGroups -Registry $registry
if ($all.mode -cne 'full' -or $all.selected.Count -ne $registry.groups.Count) { throw 'Default must select the full registry' }
$isolated = Resolve-DbGroups -Registry $registry -Selection 'site-maintenance-desktop,site-maintenance-mobile'
if ($isolated.selected.Count -ne 2 -or $isolated.certificationEligible -or $isolated.selected -ccontains 'browser-api') { throw 'Exact maintenance selection borrowed the existing browser group' }
$siteImpact = Resolve-DbGroups -Registry $registry -ChangedContracts 'site-management'
foreach($id in @('site-maintenance-desktop','site-maintenance-mobile','browser-api')) {
    if ($id -cnotin $all.selected -or $id -cnotin $siteImpact.selected) { throw "Full/site impact omitted browser consumer: $id" }
}
Must-RejectGroup 'empty explicit selection' { Resolve-DbGroups -Registry $registry -Selection '' }
Must-RejectGroup 'unknown group' { Resolve-DbGroups -Registry $registry -Selection 'misspelled' }
Must-RejectGroup 'empty list member' { Resolve-DbGroups -Registry $registry -Selection 'exchange-recovery,' }
Must-RejectGroup 'duplicate group' { Resolve-DbGroups -Registry $registry -Selection 'exchange-recovery,exchange-recovery' }
$chosen = Resolve-DbGroups -Registry $registry -Selection 'exchange-recovery'
if ($chosen.mode -cne 'selected' -or $chosen.certificationEligible -or $chosen.selected.Count -eq 0) { throw 'Selected execution must remain partial' }
$exchange = Resolve-DbGroups -Registry $registry -ChangedContracts 'exchange'
foreach ($expected in @('exchange-recovery','aftercare-contracts','inventory','confirmation','refund')) {
    if ($expected -cnotin $exchange.selected) { throw "Exchange impact omitted $expected" }
}
foreach ($shared in @('authentication','job-runner','job-contracts','financial-common','schema','unknown','test-tools')) {
    $wide = Resolve-DbGroups -Registry $registry -ChangedContracts $shared
    if (Compare-Object $registry.groups.id $wide.selected -CaseSensitive) { throw "Shared impact did not widen: $shared" }
}
$unmapped = Resolve-DbGroups -Registry $registry -ChangedContracts 'phone-provider'
if ($unmapped.selected.Count -ne $registry.groups.Count) { throw 'Incomplete impact row must conservatively widen' }
Assert-DbGroupRegistration -Registry $registry
Must-RejectGroup 'ambiguous mode' { Resolve-DbGroups -Registry $registry -Selection 'exchange' -ChangedContracts 'exchange' }
Must-RejectGroup 'empty impact' { Resolve-DbGroups -Registry $registry -ChangedContracts '' }
Must-RejectGroup 'unknown impact' { Resolve-DbGroups -Registry $registry -ChangedContracts 'new-unregistered-domain' }
$copy = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
$copy.groups[0].tests[0].new = 'com.example.marketplace.dbtest.MissingIT#missing'
Must-RejectGroup 'missing mandatory registration' { Assert-DbGroupRegistration -Registry $copy }
$copy = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
$copy.groups[0].tests += $copy.groups[0].tests[0]
Must-RejectGroup 'duplicate registration' { Assert-DbGroupRegistration -Registry $copy }
$copy = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
$copy.impact.catalog.select = @($copy.impact.catalog.select | Where-Object { $_ -cne 'refund' })
Must-RejectGroup 'catalog return-policy consumer omitted' { Assert-DbGroupRegistration -Registry $copy }
$catalog = Resolve-DbGroups -Registry $registry -ChangedContracts 'catalog'
foreach ($consumer in @('refund','return-physical','return-finance','exchange-finance','aftercare-contracts')) {
    if ($consumer -cnotin $catalog.selected) { throw "Catalog snapshot policy consumer omitted: $consumer" }
}
. "$PSScriptRoot/DatabaseTarget.ps1"
$target = Resolve-TestPostgresEndpoint
$command = New-PhoneHarnessCommand -Root (Split-Path $PSScriptRoot -Parent) -EvidenceDirectory '.local-tools/group-unit' -Endpoint $target -DbGroups 'database-runtime,job-contracts' -ReverseGroups
if (-not $command.Contains('-DbGroups database-runtime,job-contracts') -or -not $command.Contains('-ReverseGroups')) { throw 'Emulator lost exact group selection/order' }
$command = New-PhoneHarnessCommand -Root (Split-Path $PSScriptRoot -Parent) -EvidenceDirectory '.local-tools/group-unit' -Endpoint $target -ChangedContracts 'exchange'
if (-not $command.Contains('-ChangedContracts exchange')) { throw 'Emulator lost impact mode' }
Must-RejectGroup 'unsafe shell selector' { New-PhoneHarnessCommand -Root '.' -EvidenceDirectory '.local-tools/unit' -Endpoint $target -DbGroups 'exchange;whoami' }
'PASS database group selection, impact expansion, registration and default-full gates'
