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
Must-RejectGroup 'release provider consumer proof omitted' { Resolve-DbGroups -Registry $registry -Selection 'release-recovery' }
$releaseSelection = Resolve-DbGroups -Registry $registry -Selection 'public-demo-runtime,release-recovery'
if ($releaseSelection.selected.Count -ne 2 -or $releaseSelection.certificationEligible) { throw 'Release selection must retain exact prerequisite scope and remain partial' }
function Assert-GroupOrder([string]$Label, [string[]]$Actual, [string[]]$Expected) {
    if ($Actual.Count -ne $Expected.Count -or ($Actual -join ',') -cne ($Expected -join ',')) {
        throw "Unexpected group execution order: $Label ($($Actual -join ','))"
    }
    "PASS group order: $Label"
}
function Assert-PrerequisiteOrder($Registry, [string[]]$Order) {
    if ($Order.Count -ne @($Order | Sort-Object -Unique).Count) { throw 'Execution order duplicated a group' }
    foreach ($definition in @($Registry.groups | Where-Object id -CIn $Order)) {
        foreach ($prerequisite in $definition.prerequisites) {
            $before = [array]::IndexOf($Order,$prerequisite)
            if ($before -lt 0 -or $before -ge [array]::IndexOf($Order,$definition.id)) { throw 'Consumer executed before its declared prerequisite' }
        }
    }
}
$registryBeforeOrder = $registry | ConvertTo-Json -Depth 15 -Compress
$selectionBeforeOrder = $releaseSelection | ConvertTo-Json -Depth 15 -Compress
$normal = @(Get-DbGroupExecutionOrder -Registry $registry -Selected $all.selected)
$reverse = @(Get-DbGroupExecutionOrder -Registry $registry -Selected $all.selected -Reverse)
Assert-GroupOrder 'existing normal registry order' $normal $all.selected
Assert-PrerequisiteOrder $registry $normal
Assert-PrerequisiteOrder $registry $reverse
if ($reverse.Count -ne $all.selected.Count -or (Compare-Object $reverse $all.selected -CaseSensitive)) { throw 'Reverse execution changed the selected scope' }
Assert-GroupOrder 'reverse ready priority at prerequisite boundary' $reverse[0..2] @('release-upgrade','public-demo-runtime','release-recovery')
Assert-GroupOrder 'exact release normal' @(Get-DbGroupExecutionOrder -Registry $registry -Selected $releaseSelection.selected) @('public-demo-runtime','release-recovery')
Assert-GroupOrder 'exact release reverse' @(Get-DbGroupExecutionOrder -Registry $registry -Selected $releaseSelection.selected -Reverse) @('public-demo-runtime','release-recovery')
if (($registry | ConvertTo-Json -Depth 15 -Compress) -cne $registryBeforeOrder -or
    ($releaseSelection | ConvertTo-Json -Depth 15 -Compress) -cne $selectionBeforeOrder) { throw 'Scheduling mutated registry or selection/certification metadata' }
$free = [pscustomobject]@{groups=@([pscustomobject]@{id='a';provides=@('x');consumes=@('y')},[pscustomobject]@{id='b';provides=@('y');consumes=@('x')},[pscustomobject]@{id='c'})}
Assert-GroupOrder 'independent normal' @(Get-DbGroupExecutionOrder -Registry $free -Selected @('a','b','c')) @('a','b','c')
Assert-GroupOrder 'independent reverse' @(Get-DbGroupExecutionOrder -Registry $free -Selected @('a','b','c') -Reverse) @('c','b','a')
$chain = [pscustomobject]@{groups=@([pscustomobject]@{id='c';prerequisites=@('b')},[pscustomobject]@{id='b';prerequisites=@('a')},[pscustomobject]@{id='a'})}
Assert-GroupOrder 'reordered chain normal' @(Get-DbGroupExecutionOrder -Registry $chain -Selected @('a','b','c')) @('a','b','c')
Assert-GroupOrder 'reordered chain reverse' @(Get-DbGroupExecutionOrder -Registry $chain -Selected @('a','b','c') -Reverse) @('a','b','c')
$diamond = [pscustomobject]@{groups=@([pscustomobject]@{id='d';prerequisites=@('b','c')},[pscustomobject]@{id='b';prerequisites=@('a')},[pscustomobject]@{id='a'},[pscustomobject]@{id='c';prerequisites=@('a')})}
Assert-GroupOrder 'diamond normal' @(Get-DbGroupExecutionOrder -Registry $diamond -Selected @('a','b','c','d')) @('a','b','c','d')
Assert-GroupOrder 'diamond reverse ready priority' @(Get-DbGroupExecutionOrder -Registry $diamond -Selected @('a','b','c','d') -Reverse) @('a','c','b','d')
Must-RejectGroup 'scheduler missing selected prerequisite' { Get-DbGroupExecutionOrder -Registry $registry -Selected @('release-recovery') }
Must-RejectGroup 'scheduler unknown selected ID' { Get-DbGroupExecutionOrder -Registry $registry -Selected @('unregistered') }
Must-RejectGroup 'scheduler duplicate selected ID' { Get-DbGroupExecutionOrder -Registry $free -Selected @('a','a') }
Must-RejectGroup 'scheduler empty selection' { Get-DbGroupExecutionOrder -Registry $free -Selected @() }
Must-RejectGroup 'scheduler empty registry' { Get-DbGroupExecutionOrder -Registry ([pscustomobject]@{groups=@()}) -Selected @('a') }
Must-RejectGroup 'scheduler duplicate registry IDs' { Get-DbGroupExecutionOrder -Registry ([pscustomobject]@{groups=@([pscustomobject]@{id='a'},[pscustomobject]@{id='a'})}) -Selected @('a') }
foreach ($kind in @('unknown','self','duplicate','empty','cycle','three-cycle')) {
    $bad = $registry | ConvertTo-Json -Depth 15 | ConvertFrom-Json
    $producer = $bad.groups | Where-Object id -CEQ 'public-demo-runtime'
    $consumer = $bad.groups | Where-Object id -CEQ 'release-recovery'
    switch ($kind) {
        'unknown' { $consumer.prerequisites=@('unregistered') }
        'self' { $consumer.prerequisites=@('release-recovery') }
        'duplicate' { $consumer.prerequisites=@('public-demo-runtime','public-demo-runtime') }
        'empty' { $consumer.prerequisites=@('') }
        'cycle' { $producer | Add-Member -NotePropertyName prerequisites -NotePropertyValue @('release-recovery') -Force }
        'three-cycle' {
            $producer | Add-Member -NotePropertyName prerequisites -NotePropertyValue @('release-upgrade') -Force
            ($bad.groups | Where-Object id -CEQ 'release-upgrade') | Add-Member -NotePropertyName prerequisites -NotePropertyValue @('release-recovery') -Force
        }
    }
    Must-RejectGroup "global prerequisite registration $kind" { Assert-DbGroupRegistration -Registry $bad }
    $partial = [Collections.Generic.List[string]]::new()
    $rejected = $false
    try { Get-DbGroupExecutionOrder -Registry $bad -Selected @('identity') | ForEach-Object { $partial.Add($_) } } catch { $rejected=$true }
    if (-not $rejected -or $partial.Count) { throw "Malformed whole registry emitted a partial/excluded-graph schedule: $kind" }
    "PASS prerequisite graph rejection without partial output: $kind"
}
$groupTokens=$null; $groupParseErrors=$null
$backendAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path (Split-Path $PSScriptRoot -Parent) 'backend/verify-db.ps1'),[ref]$groupTokens,[ref]$groupParseErrors)
if ($groupParseErrors.Count) { throw 'Backend group runner syntax is invalid' }
$orderAssignments=@($backendAst.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'ordered'},$true))
$endpointCalls=@($backendAst.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Resolve-TestPostgresEndpoint'},$true))
$executionLoops=@($backendAst.FindAll({param($node) $node -is [Management.Automation.Language.ForEachStatementAst] -and $node.Variable.VariablePath.UserPath -ceq 'id' -and $node.Condition.Extent.Text -ceq '$ordered'},$true))
if ($orderAssignments.Count -ne 1 -or $endpointCalls.Count -ne 1 -or $executionLoops.Count -ne 1 -or
    $orderAssignments[0].Right.Extent.Text -notmatch 'Get-DbGroupExecutionOrder' -or
    $orderAssignments[0].Extent.StartOffset -ge $endpointCalls[0].Extent.StartOffset) { throw 'Runner must consume its prerequisite-safe schedule before acquiring an endpoint' }
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
'PASS prerequisite-safe normal/reverse execution, exact scope, deterministic ready priority and graph preflight rejection'
