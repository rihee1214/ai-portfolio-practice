function Get-DbGroupRegistry {
    Get-Content "$PSScriptRoot/db-groups.json" -Raw | ConvertFrom-Json
}

function Assert-DbGroupScheduling($Registry) {
    $resources=$Registry.executionResources
    if($resources.schemaVersion -ne 1 -or $resources.maxConcurrency -ne 2){throw 'Explicit execution resource schema and cap2 required'}
    $known=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($key in $resources.resources){if($key -cnotmatch '^[a-z][a-z0-9-]*$' -or -not $known.Add($key)){throw 'Invalid or duplicate execution resource'}}
    foreach($group in $Registry.groups){
        $execution=$group.execution
        if(-not $execution -or $execution.exclusive -isnot [bool] -or -not($execution.PSObject.Properties.Name -ccontains 'resources') -or [string]::IsNullOrWhiteSpace($execution.reason)){throw "Missing execution classification: $($group.id)"}
        $claimed=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($key in $execution.resources){if(-not $known.Contains($key) -or -not $claimed.Add($key)){throw "Unknown or duplicate execution claim: $($group.id)"}}
    }
    $null=Get-DbGroupDurationHints -Registry $Registry
}

function Get-DbGroupDurationHints($Registry) {
    $hints=[Collections.Generic.Dictionary[string,double]]::new([StringComparer]::Ordinal)
    if(-not ($Registry.PSObject.Properties.Name -ccontains 'executionPriority')){return $hints}
    $metadata=$Registry.executionPriority
    if(-not $metadata -or
        ($metadata.schemaVersion -isnot [int] -and $metadata.schemaVersion -isnot [long]) -or $metadata.schemaVersion -ne 1 -or
        $metadata.policy -cne 'measured-duration-descending' -or $metadata.tieBreak -cne 'existing-normal-or-reverse-order'){
        throw 'Invalid advisory duration scheduling policy'
    }
    $source=$metadata.source
    if(-not $source -or $source.evidence -isnot [string] -or $source.evidence -cnotmatch '^\.local-tools/[A-Za-z0-9][A-Za-z0-9/_-]*$' -or
        $source.inputsSha256 -isnot [string] -or $source.inputsSha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or
        $source.timingBoundary -cne 'worker-start-to-validated-terminal-seconds' -or $metadata.durations -isnot [array]){
        throw 'Advisory durations require explicit source identity and ownership boundary'
    }
    $known=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($group in $Registry.groups){$null=$known.Add($group.id)}
    # Validate every hint before selecting work. Provenance is descriptive only:
    # no historical evidence is read, and advisory seconds never become deadlines.
    foreach($hint in $metadata.durations){
        if($hint.id -isnot [string] -or -not $known.Contains($hint.id) -or $hints.ContainsKey($hint.id)){throw 'Unknown or duplicate advisory duration group'}
        $seconds=$hint.seconds
        $numeric=$seconds -is [byte] -or $seconds -is [sbyte] -or $seconds -is [short] -or $seconds -is [ushort] -or
            $seconds -is [int] -or $seconds -is [uint] -or $seconds -is [long] -or $seconds -is [ulong] -or
            $seconds -is [single] -or $seconds -is [double] -or $seconds -is [decimal]
        if(-not $numeric -or -not [double]::IsFinite([double]$seconds) -or [double]$seconds -lt 0){throw 'Advisory duration must be finite nonnegative numeric seconds'}
        $hints.Add($hint.id,[double]$seconds)
    }
    return $hints
}

function Get-DbGroupTopologicalOrder {
    param([string[]]$Priority, $Dependencies)
    $pending = [Collections.Generic.List[string]]::new()
    foreach ($id in $Priority) { $pending.Add($id) }
    $ordered = [Collections.Generic.List[string]]::new()
    $emitted = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    while ($pending.Count) {
        $next = $null
        foreach ($id in $pending) {
            if (@($Dependencies[$id] | Where-Object { -not $emitted.Contains($_) }).Count -eq 0) { $next=$id; break }
        }
        if ($null -eq $next) { throw "Cyclic database group prerequisites: $($pending -join ',')" }
        $ordered.Add($next)
        [void]$emitted.Add($next)
        [void]$pending.Remove($next)
    }
    # Buffer the complete order: a rejected graph must never emit a usable partial schedule.
    $ordered.ToArray()
}

function Get-DbGroupExecutionOrder {
    param($Registry, [string[]]$Selected, [switch]$Reverse)
    $all = @($Registry.groups.id)
    $known = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $all) {
        if ([string]::IsNullOrWhiteSpace($id) -or -not $known.Add($id)) { throw 'Empty or duplicate group registry' }
    }
    if (-not $all.Count) { throw 'Empty group registry' }
    $durationHints=Get-DbGroupDurationHints -Registry $Registry
    $dependencies = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($definition in $Registry.groups) {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $edges = [Collections.Generic.List[string]]::new()
        foreach ($prerequisite in $definition.prerequisites) {
            if ([string]::IsNullOrWhiteSpace($prerequisite) -or -not $known.Contains($prerequisite) -or
                $prerequisite -ceq $definition.id -or -not $seen.Add($prerequisite)) {
                throw "Unknown, self or duplicate group prerequisite: $($definition.id)"
            }
            $edges.Add($prerequisite)
        }
        $dependencies[$definition.id] = $edges.ToArray()
    }
    # Validate the whole registry even when an exact selection excludes a malformed cycle.
    Get-DbGroupTopologicalOrder -Priority $all -Dependencies $dependencies | Out-Null
    $chosen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $Selected) {
        if (-not $known.Contains($id) -or -not $chosen.Add($id)) { throw 'Unknown or duplicate selected database group' }
    }
    if (-not $chosen.Count) { throw 'No database groups selected for execution' }
    foreach ($id in $Selected) {
        foreach ($prerequisite in $dependencies[$id]) {
            if (-not $chosen.Contains($prerequisite)) { throw "Selected group $id requires same-source consumer evidence from $prerequisite" }
        }
    }
    $priority = @($all | Where-Object { $chosen.Contains($_) })
    if ($Reverse) { [array]::Reverse($priority) }
    # Reverse changes only the existing tie/fallback order, never longest-first.
    if($durationHints.Count){
        $priority=@($priority|Sort-Object -Stable -Property @{Expression={if($durationHints.ContainsKey($_)){$durationHints[$_]}else{0}};Descending=$true})
    }
    Get-DbGroupTopologicalOrder -Priority $priority -Dependencies $dependencies
}

function Resolve-DbGroups {
    param($Registry, [string]$Selection, [string]$ChangedContracts)
    $hasSelection = $PSBoundParameters.ContainsKey('Selection')
    $hasImpact = $PSBoundParameters.ContainsKey('ChangedContracts')
    if ($hasSelection -and $hasImpact) { throw 'Choose exact groups OR changed contracts, not both' }
    $all = @($Registry.groups.id)
    if (-not $all.Count -or @($all | Sort-Object -Unique).Count -ne $all.Count) { throw 'Empty or duplicate group registry' }
    Assert-RootPhaseRegistration -Registry $Registry
    $selected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $reasons = @()
    if ($hasSelection -or $hasImpact) {
        $value = if ($hasSelection) { $Selection } else { $ChangedContracts }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($name in $value.Split(',')) {
            if ([string]::IsNullOrWhiteSpace($name) -or $name -cne $name.Trim() -or -not $seen.Add($name)) { throw 'Empty, padded or duplicate selection' }
            $targets = @($name)
            $reason = 'explicit exact group; caller must assess affected contracts'
            if ($hasImpact) {
                if ($Registry.aliases.PSObject.Properties.Name -ccontains $name) {
                    $targets = @($Registry.aliases.$name); $reason = 'shared or uncertain change: conservatively expand to all'
                } elseif ($Registry.impact.PSObject.Properties.Name -ccontains $name) {
                    $targets = @($Registry.impact.$name.select); $reason = $Registry.impact.$name.reason
                } elseif ($all -ccontains $name) {
                    $targets = $all; $reason = 'registered group without a complete change-impact row: conservatively expand to all'
                } else { throw "Unknown changed contract: $name" }
            }
            foreach ($target in $targets) {
                if ($all -cnotcontains $target) { throw "Unknown database group or contract: $target" }
                [void]$selected.Add($target)
            }
            $reasons += @{requested=$name;selected=$targets;reason=$reason}
        }
    } else {
        foreach ($name in $all) { [void]$selected.Add($name) }
        $reasons = @(@{requested='default';selected=$all;reason='no selection options: mandatory whole verification'})
    }
    if (-not $selected.Count) { throw 'No database groups selected' }
    foreach ($definition in @($Registry.groups | Where-Object { $selected.Contains($_.id) })) {
        foreach ($prerequisite in $definition.prerequisites) {
            if (-not $selected.Contains($prerequisite)) { throw "Selected group $($definition.id) requires same-source consumer evidence from $prerequisite" }
        }
    }
    [pscustomobject]@{mode=$(if($hasSelection -or $hasImpact){'selected'}else{'full'});
        certificationEligible=(-not ($hasSelection -or $hasImpact));
        selected=@($all | Where-Object { $selected.Contains($_) });
        excluded=@($all | Where-Object { -not $selected.Contains($_) });reasons=$reasons;
        rootPhases=@{selected=@($Registry.rootPhases | Where-Object { $_.prerequisites | Where-Object { $selected.Contains($_) } } | ForEach-Object id);
            excluded=@($Registry.rootPhases | Where-Object { -not @($_.prerequisites | Where-Object { $selected.Contains($_) }).Count } | ForEach-Object id)}}
}

# Root phases consume actual same-run artifacts; they are not JVM/IT groups and
# never contribute a fictitious Java test or DB to the backend group registry.
function Assert-RootPhaseRegistration {
    param($Registry)
    if($Registry.schemaVersion -eq 1 -and @($Registry.rootPhases).Count -ne 1){throw 'Mandatory container reproduction root phase missing or duplicated'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($phase in $Registry.rootPhases) {
        if(-not $seen.Add($phase.id) -or $phase.id -cne 'container-reproduction' -or
            $phase.kind -cne 'root-container-contract' -or $phase.expectedDatabaseCount -ne 1 -or
            @($phase.prerequisites).Count -ne 1 -or $phase.prerequisites[0] -cne 'package-runtime' -or
            $phase.browser.requirements -cne 'required-browser-container-reproduction.json' -or $phase.browser.project) {
            throw 'Unknown, duplicate or invalid root verification phase/producer'
        }
        foreach($prerequisite in $phase.prerequisites) {
            if($prerequisite -cnotin $Registry.groups.id){throw 'Unknown root phase artifact producer'}
        }
        if(-not @($phase.consumes).Count -or -not @($phase.provides).Count){throw 'Empty root phase contract registration'}
        foreach($contract in $phase.consumes) {
            $providers=@($Registry.groups | Where-Object { $contract -cin $_.provides })
            if(-not $providers.Count){throw "Unknown root phase consumed contract: $contract"}
            foreach($provider in $providers) {
                $impact=$Registry.impact.PSObject.Properties[$provider.id]
                # Missing impact rows already expand to every DB group.
                if($impact -and 'package-runtime' -cnotin $impact.Value.select){throw "Change impact omits root phase consumer of $($provider.id)"}
            }
        }
        $impact=$Registry.impact.PSObject.Properties[$phase.id]
        if(-not $impact -or @($impact.Value.select).Count -ne 1 -or $impact.Value.select[0] -cne 'package-runtime') {
            throw 'Root phase impact must select its same-run artifact producer'
        }
    }
}

function Assert-DbGroupDatabases {
    param([int]$ExpectedCount, [string]$Primary, [string[]]$Created)
    if ($ExpectedCount -lt 0 -or $Created.Count -ne $ExpectedCount) { throw 'Unexpected database creation count' }
    $unique = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $Created) {
        if ($name -cnotmatch '^d2_it_[0-9a-f]{32}$' -or -not $unique.Add($name)) { throw 'Invalid or duplicate created database' }
    }
    if ($ExpectedCount -gt 0 -and -not $unique.Contains($Primary)) { throw 'Primary database creation proof missing' }
    [pscustomobject]@{primary=$(if($ExpectedCount){$Primary}else{$null});created=@($Created);auxiliary=@($Created | Where-Object { $_ -cne $Primary })}
}

function Assert-DbGroupRegistration {
    param($Registry, [string]$Root = (Split-Path $PSScriptRoot -Parent))
    Assert-DbGroupScheduling -Registry $Registry
    Get-DbGroupExecutionOrder -Registry $Registry -Selected @($Registry.groups.id) | Out-Null
    $registered = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $legacy = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $classes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($group in $Registry.groups) {
        if ($group.browser -and $group.id -ceq 'release-recovery') {
            if ($group.browser.project -or $group.browser.requirements -cne 'required-browser-release.json' -or
                $group.classes.Count -ne 1 -or $group.expectedDatabaseCount -ne 5) {
                throw 'Invalid actual release recovery lifecycle registration'
            }
        } elseif ($group.browser -and $group.id -ceq 'package-runtime') {
            if ($group.browser.project -or $group.browser.requirements -cne 'required-browser-package.json' -or
                $group.classes.Count -ne 1 -or $group.expectedDatabaseCount -ne 1) {
                throw 'Invalid actual package browser lifecycle registration'
            }
        } elseif ($group.browser) {
            if ($group.browser.project -cnotin @('desktop','mobile') -or $group.classes.Count -ne 1 -or
                $group.expectedDatabaseCount -ne 1 -or $group.id -cne ('site-maintenance-'+$group.browser.project) -or
                $group.browser.requirements -cne 'required-browser-site-maintenance.json') {
                throw "Invalid isolated browser lifecycle registration: $($group.id)"
            }
        }
        if ($group.expectedDatabaseCount -isnot [long] -and $group.expectedDatabaseCount -isnot [int]) { throw "Missing database count: $($group.id)" }
        if (($group.id -ceq 'phone-provider' -and $group.expectedDatabaseCount -ne 0) -or ($group.id -cne 'phone-provider' -and $group.expectedDatabaseCount -lt 1)) { throw "Invalid database count: $($group.id)" }
        if (-not $group.tests.Count -or -not $group.classes.Count) { throw "Empty group: $($group.id)" }
        foreach ($class in $group.classes) {
            if (-not $classes.Add($class)) { throw "Duplicate class ownership: $class" }
            $path = Join-Path $Root ('backend/src/test/java/' + $class.Replace('.','/') + '.java')
            if (-not (Test-Path -LiteralPath $path)) { throw "Registered class missing: $class" }
            $source = Get-Content -LiteralPath $path -Raw
            $methods = [regex]::Matches($source, '@(?:Test\b|org\.junit\.jupiter\.params\.ParameterizedTest\b)[\s\S]*?\bvoid\s+(\w+)\s*\(')
            foreach ($method in $methods) {
                $id = $class + '#' + $method.Groups[1].Value
                if (@($group.tests | Where-Object new -CEQ $id).Count -ne 1) { throw "Unregistered/duplicate executable method: $id" }
            }
            foreach ($test in @($group.tests | Where-Object { $_.new.StartsWith($class+'#',[StringComparison]::Ordinal) })) {
                if ($methods.Groups.Value -cnotcontains $test.new.Split('#')[1]) { throw "Registered method missing: $($test.new)" }
            }
        }
        foreach ($test in $group.tests) {
            if (-not $registered.Add($test.new) -or -not $legacy.Add($test.old)) { throw 'Duplicate new or previous test mapping' }
        }
    }
    foreach ($file in Get-ChildItem "$Root/backend/src/test/java" -Filter '*IT.java' -Recurse) {
        $source = Get-Content $file.FullName -Raw
        $package = [regex]::Match($source,'package\s+([^;]+);').Groups[1].Value
        if (-not $classes.Contains($package+'.'+$file.BaseName)) { throw "Unregistered integration class: $($file.Name)" }
    }
    $required = Get-Content "$Root/verification/required-tests.json" -Raw | ConvertFrom-Json
    foreach ($id in $required.tests) {
        if ($id -match 'IT#' -and -not $registered.Contains($id)) { throw "Mandatory integration test is unregistered: $id" }
    }
    foreach ($id in $registered) {
        if ($required.tests -cnotcontains $id) { throw "Executable integration test is not mandatory: $id" }
    }
    $baseline = Get-Content "$Root/verification/db-test-baseline.json" -Raw | ConvertFrom-Json
    foreach ($id in @($baseline.methods.id) + @($baseline.phone)) {
        if (-not $legacy.Contains($id)) { throw "Original mandatory test lost: $id" }
    }
    $fixture = Get-Content "$Root/backend/src/test/java/com/example/marketplace/dbtest/PostgresContractFixture.java" -Raw
    if ($fixture -match '@(?:Test\b|org\.junit\.jupiter\.params\.ParameterizedTest\b)') { throw 'Shared fixture must not contain executable tests' }
    foreach ($test in $Registry.groups.tests) {
        if ($test.invocations -lt 1) { throw "Missing expected invocation count: $($test.new)" }
    }
    foreach ($provider in $Registry.groups) {
        if (-not $provider.provides.Count) { throw "Missing provided contracts: $($provider.id)" }
        $impact = Resolve-DbGroups -Registry $Registry -ChangedContracts $provider.id
        foreach ($consumer in $Registry.groups) {
            $shared = @($consumer.consumes | Where-Object { $_ -cin $provider.provides })
            if ($shared.Count -and $consumer.id -cnotin $impact.selected) {
                throw "Change impact omits consumer $($consumer.id) of $($provider.id): $($shared -join ',')"
            }
        }
    }
}
