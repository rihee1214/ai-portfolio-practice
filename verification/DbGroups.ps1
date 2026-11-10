function Get-DbGroupRegistry {
    Get-Content "$PSScriptRoot/db-groups.json" -Raw | ConvertFrom-Json
}

function Resolve-DbGroups {
    param($Registry, [string]$Selection, [string]$ChangedContracts)
    $hasSelection = $PSBoundParameters.ContainsKey('Selection')
    $hasImpact = $PSBoundParameters.ContainsKey('ChangedContracts')
    if ($hasSelection -and $hasImpact) { throw 'Choose exact groups OR changed contracts, not both' }
    $all = @($Registry.groups.id)
    if (-not $all.Count -or @($all | Sort-Object -Unique).Count -ne $all.Count) { throw 'Empty or duplicate group registry' }
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
    [pscustomobject]@{mode=$(if($hasSelection -or $hasImpact){'selected'}else{'full'});
        certificationEligible=(-not ($hasSelection -or $hasImpact));
        selected=@($all | Where-Object { $selected.Contains($_) });
        excluded=@($all | Where-Object { -not $selected.Contains($_) });reasons=$reasons}
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
    $registered = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $legacy = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $classes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($group in $Registry.groups) {
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
