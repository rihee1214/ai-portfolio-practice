$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DbGroups.ps1"
function Must-RejectGroup([string]$Label, [scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Accepted invalid selection: $Label" }
}
$registry = Get-DbGroupRegistry
Assert-DbGroupScheduling -Registry $registry
foreach($mutation in @('missing','unknown','duplicate','reason','cap')){
    $badExecution=$registry|ConvertTo-Json -Depth 20|ConvertFrom-Json
    switch($mutation){
        'missing' {$badExecution.groups[0].PSObject.Properties.Remove('execution')}
        'unknown' {$badExecution.groups[0].execution.resources=@('unknown')}
        'duplicate' {$badExecution.groups[0].execution.resources=@('fault-injection','fault-injection')}
        'reason' {$badExecution.groups[0].execution.reason=''}
        'cap' {$badExecution.executionResources.maxConcurrency=3}
    }
    Must-RejectGroup "execution $mutation" {Assert-DbGroupScheduling -Registry $badExecution}
}
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
$unhintedRegistry=$registry|ConvertTo-Json -Depth 20|ConvertFrom-Json
$unhintedRegistry.PSObject.Properties.Remove('executionPriority')
$normal = @(Get-DbGroupExecutionOrder -Registry $unhintedRegistry -Selected $all.selected)
$reverse = @(Get-DbGroupExecutionOrder -Registry $unhintedRegistry -Selected $all.selected -Reverse)
Assert-GroupOrder 'existing normal registry order' $normal $all.selected
Assert-PrerequisiteOrder $registry $normal
Assert-PrerequisiteOrder $registry $reverse
if ($reverse.Count -ne $all.selected.Count -or (Compare-Object $reverse $all.selected -CaseSensitive)) { throw 'Reverse execution changed the selected scope' }
Assert-GroupOrder 'reverse ready priority at prerequisite boundary' $reverse[0..2] @('release-upgrade','public-demo-runtime','release-recovery')
Assert-GroupOrder 'exact release normal' @(Get-DbGroupExecutionOrder -Registry $registry -Selected $releaseSelection.selected) @('public-demo-runtime','release-recovery')
Assert-GroupOrder 'exact release reverse' @(Get-DbGroupExecutionOrder -Registry $registry -Selected $releaseSelection.selected -Reverse) @('public-demo-runtime','release-recovery')
$actualNormal=@(Get-DbGroupExecutionOrder -Registry $registry -Selected $all.selected)
$actualReverse=@(Get-DbGroupExecutionOrder -Registry $registry -Selected $all.selected -Reverse)
$expectedMeasured='browser-api,return-recovery,process-recovery,return-finance,exchange-recovery,legacy,refund,aftercare-contracts,exchange-finance,exchange,fulfillment,return-physical,package-runtime,release-upgrade,dashboard,identity,payment,support,settlement,confirmation,questions,promotion,catalog,job-contracts,moderation,reports,checkout,site-maintenance-mobile,site-maintenance-desktop,guest,order-history,content,inventory,database-runtime,operations,site-management,seller-store,media,shopping,public-demo-runtime,release-recovery,phone-provider'.Split(',')
Assert-GroupOrder 'actual source-bound measured whole normal priority' $actualNormal $expectedMeasured
Assert-GroupOrder 'actual source-bound measured whole reverse tie priority' $actualReverse $expectedMeasured
foreach($actualOrder in @($actualNormal,$actualReverse)){
    Assert-PrerequisiteOrder $registry $actualOrder
    if($actualOrder.Count -ne 42 -or (Compare-Object $actualOrder $all.selected -CaseSensitive)){throw 'Advisory ordering changed exact whole42 membership'}
}
if($registry.executionPriority.source.inputsSha256 -cne '299d4040349ed1f6710d1f7bc41beb109793d16205dce28da9babf962e97576d' -or
    $registry.executionPriority.source.evidence -cne '.local-tools/perf2-baseline2-fc14033b6dce4c70a2ab55326e593f6a' -or
    $registry.executionPriority.durations.Count -ne 42 -or
    (Compare-Object @($registry.executionPriority.durations.id) $all.selected -CaseSensitive)){throw 'Checked-in hints lost exact baseline provenance or42 group coverage'}
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
function New-DurationPriority([object[]]$Durations) {
    [pscustomobject]@{schemaVersion=1;policy='measured-duration-descending';tieBreak='existing-normal-or-reverse-order';
        source=[pscustomobject]@{evidence='.local-tools/synthetic-duration-source';inputsSha256=('a'*64);timingBoundary='worker-start-to-validated-terminal-seconds'};
        durations=$Durations}
}
$weighted=[pscustomobject]@{groups=@('a','b','c','d','e'|ForEach-Object{[pscustomobject]@{id=$_}});
    executionPriority=(New-DurationPriority @([pscustomobject]@{id='a';seconds=10},[pscustomobject]@{id='b';seconds=30},[pscustomobject]@{id='c';seconds=30},[pscustomobject]@{id='d';seconds=0}))}
$weightedBefore=$weighted|ConvertTo-Json -Depth 15 -Compress
Assert-GroupOrder 'duration descending with stable normal ties and zero missing fallback' @(Get-DbGroupExecutionOrder -Registry $weighted -Selected @('a','b','c','d','e')) @('b','c','a','d','e')
Assert-GroupOrder 'reverse changes only duration ties and fallback' @(Get-DbGroupExecutionOrder -Registry $weighted -Selected @('a','b','c','d','e') -Reverse) @('c','b','a','e','d')
Assert-GroupOrder 'duration selection remains exact' @(Get-DbGroupExecutionOrder -Registry $weighted -Selected @('a','d','e')) @('a','d','e')
Assert-GroupOrder 'duration selection reverse remains exact' @(Get-DbGroupExecutionOrder -Registry $weighted -Selected @('a','d','e') -Reverse) @('a','e','d')
if(($weighted|ConvertTo-Json -Depth 15 -Compress) -cne $weightedBefore){throw 'Advisory ordering mutated metadata'}
$emptyHints=$weighted|ConvertTo-Json -Depth 15|ConvertFrom-Json
$emptyHints.executionPriority.durations=@()
Assert-GroupOrder 'empty hints preserve normal fallback' @(Get-DbGroupExecutionOrder -Registry $emptyHints -Selected @('a','b','c','d','e')) @('a','b','c','d','e')
Assert-GroupOrder 'empty hints preserve reverse fallback' @(Get-DbGroupExecutionOrder -Registry $emptyHints -Selected @('a','b','c','d','e') -Reverse) @('e','d','c','b','a')
$weightedChain=$chain|ConvertTo-Json -Depth 15|ConvertFrom-Json
$weightedChain|Add-Member -NotePropertyName executionPriority -NotePropertyValue (New-DurationPriority @([pscustomobject]@{id='c';seconds=100},[pscustomobject]@{id='b';seconds=20},[pscustomobject]@{id='a';seconds=1}))
Assert-GroupOrder 'duration hints cannot bypass chain producers' @(Get-DbGroupExecutionOrder -Registry $weightedChain -Selected @('a','b','c')) @('a','b','c')
$weightedDiamond=$diamond|ConvertTo-Json -Depth 15|ConvertFrom-Json
$weightedDiamond|Add-Member -NotePropertyName executionPriority -NotePropertyValue (New-DurationPriority @([pscustomobject]@{id='d';seconds=100},[pscustomobject]@{id='b';seconds=20},[pscustomobject]@{id='c';seconds=20},[pscustomobject]@{id='a';seconds=1}))
Assert-GroupOrder 'duration diamond normal ready ties' @(Get-DbGroupExecutionOrder -Registry $weightedDiamond -Selected @('a','b','c','d')) @('a','b','c','d')
Assert-GroupOrder 'duration diamond reverse ready ties' @(Get-DbGroupExecutionOrder -Registry $weightedDiamond -Selected @('a','b','c','d') -Reverse) @('a','c','b','d')
$interaction=[pscustomobject]@{groups=@(
    [pscustomobject]@{id='p';prerequisites=@();execution=@{exclusive=$false;resources=@()}},
    [pscustomobject]@{id='x';prerequisites=@('p');execution=@{exclusive=$true;resources=@()}},
    [pscustomobject]@{id='b';prerequisites=@();execution=@{exclusive=$false;resources=@()}},
    [pscustomobject]@{id='a';prerequisites=@();execution=@{exclusive=$false;resources=@()}});
    executionPriority=(New-DurationPriority @([pscustomobject]@{id='x';seconds=100},[pscustomobject]@{id='a';seconds=50},[pscustomobject]@{id='b';seconds=20},[pscustomobject]@{id='p';seconds=10}))}
$effective=@(Get-DbGroupExecutionOrder -Registry $interaction -Selected @('p','x','b','a'))
Assert-GroupOrder 'measured order retains prerequisite exclusive interaction' $effective @('a','b','p','x')
$encodedInteraction=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((@{groups=$interaction.groups;priority=$effective}|ConvertTo-Json -Depth 10 -Compress)))
$interactionScript=@'
const assert=require('node:assert/strict'),{readyGroups}=require('./verification/DbGroupExecution.cjs');
const input=JSON.parse(Buffer.from(process.argv[1],'base64').toString('utf8')),defs=new Map(input.groups.map(g=>[g.id,g])),groups=input.priority.map(id=>defs.get(id));
const ids=(states,active)=>readyGroups(groups,states,active.map(id=>defs.get(id)),2).map(g=>g.id);
assert.deepEqual(ids({a:'pending',b:'pending',p:'pending',x:'pending'},[]),['a','b']);
assert.deepEqual(ids({a:'running',b:'passed',p:'pending',x:'pending'},['a']),['p']);
assert.deepEqual(ids({a:'running',b:'passed',p:'passed',x:'pending'},['a']),[]);
assert.deepEqual(ids({a:'passed',b:'passed',p:'passed',x:'pending'},[]),['x']);
assert.deepEqual(ids({a:'pending',b:'passed',p:'passed',x:'running'},['x']),[]);
console.log('PASS actual returned duration priority preserves producer admission and exclusive drain/cap');
'@
& (Join-Path (Split-Path $PSScriptRoot -Parent) '.local-tools/node-v24.22.0-win-x64/node.exe') -e $interactionScript $encodedInteraction
if($LASTEXITCODE -ne 0){throw 'Measured priority bypassed actual scheduler readiness'}
foreach($kind in @('null-metadata','schema','schema-string','policy','tie','source','source-evidence','source-hash','source-boundary','durations','unknown','duplicate','null-hint','missing-seconds','null','string','bool','nan','infinite','negative')) {
    $badHints=$weighted|ConvertTo-Json -Depth 15|ConvertFrom-Json
    switch($kind){
        'null-metadata' {$badHints.executionPriority=$null}
        'schema' {$badHints.executionPriority.schemaVersion=2}
        'schema-string' {$badHints.executionPriority.schemaVersion='1'}
        'policy' {$badHints.executionPriority.policy='unknown'}
        'tie' {$badHints.executionPriority.tieBreak='shortest-first'}
        'source' {$badHints.executionPriority.source=$null}
        'source-evidence' {$badHints.executionPriority.source.evidence='../unbound-source'}
        'source-hash' {$badHints.executionPriority.source.inputsSha256='not-source-hash'}
        'source-boundary' {$badHints.executionPriority.source.timingBoundary='deadline'}
        'durations' {$badHints.executionPriority.durations=[pscustomobject]@{id='a';seconds=1}}
        'unknown' {$badHints.executionPriority.durations[1].id='unregistered'}
        'duplicate' {$badHints.executionPriority.durations[1].id='a'}
        'null-hint' {$badHints.executionPriority.durations[1]=$null}
        'missing-seconds' {$badHints.executionPriority.durations[1].PSObject.Properties.Remove('seconds')}
        'null' {$badHints.executionPriority.durations[1].seconds=$null}
        'string' {$badHints.executionPriority.durations[1].seconds='30'}
        'bool' {$badHints.executionPriority.durations[1].seconds=$true}
        'nan' {$badHints.executionPriority.durations[1].seconds=[double]::NaN}
        'infinite' {$badHints.executionPriority.durations[1].seconds=[double]::PositiveInfinity}
        'negative' {$badHints.executionPriority.durations[1].seconds=-1}
    }
    $partial=[Collections.Generic.List[string]]::new();$rejected=$false
    try {Get-DbGroupExecutionOrder -Registry $badHints -Selected @('a')|ForEach-Object{$partial.Add($_)}}catch{$rejected=$true}
    if(-not $rejected -or $partial.Count){throw "Malformed global/unselected advisory hint emitted schedule: $kind"}
    "PASS invalid global duration hint rejected without partial output: $kind"
}
$badRegisteredHints=$registry|ConvertTo-Json -Depth 20|ConvertFrom-Json
$badRegisteredHints|Add-Member -NotePropertyName executionPriority -NotePropertyValue (New-DurationPriority @([pscustomobject]@{id='identity';seconds='invalid'})) -Force
Must-RejectGroup 'registration rejects unselected invalid duration hints' {Assert-DbGroupScheduling -Registry $badRegisteredHints}
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
$schedulerAssignments=@($backendAst.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'schedulerRequest'},$true))
$schedulerCalls=@($backendAst.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.Extent.Text.Contains('DbGroupExecution.cjs')},$true))
if ($orderAssignments.Count -ne 1 -or $endpointCalls.Count -ne 1 -or $schedulerAssignments.Count -ne 1 -or $schedulerCalls.Count -ne 1 -or
    -not $schedulerAssignments[0].Right.Extent.Text.Contains('priority=$ordered') -or
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
$serialCommand=New-PhoneHarnessCommand -Root '.' -EvidenceDirectory '.local-tools/unit' -Endpoint $target -GroupConcurrency '1'
if(-not $serialCommand.Contains('-GroupConcurrency 1')){throw 'Emulator lost explicit serial mode'}
Must-RejectGroup 'fractional concurrency' {New-PhoneHarnessCommand -Root '.' -EvidenceDirectory '.local-tools/unit' -Endpoint $target -GroupConcurrency '1.5'}
Must-RejectGroup 'concurrency beyond cap2' {New-PhoneHarnessCommand -Root '.' -EvidenceDirectory '.local-tools/unit' -Endpoint $target -GroupConcurrency '3'}
Must-RejectGroup 'unsafe shell selector' { New-PhoneHarnessCommand -Root '.' -EvidenceDirectory '.local-tools/unit' -Endpoint $target -DbGroups 'exchange;whoami' }
'PASS database group selection, impact expansion, registration and default-full gates'
'PASS prerequisite-safe normal/reverse execution, exact scope, deterministic ready priority and graph preflight rejection'
