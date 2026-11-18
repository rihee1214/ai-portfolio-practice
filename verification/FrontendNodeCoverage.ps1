# Native Node JUnit uses direct testsuites/testcase nodes and a file attribute;
# keep it separate from the Vitest TEST-*.xml inventory and common Java gate.
function Assert-FrontendNodeCoverage([string]$Report, [string]$Root) {
    try {
        if (-not (Test-Path -LiteralPath $Report -PathType Leaf)) { throw 'Absent report' }
        $raw = Get-Content -LiteralPath $Report -Raw -Encoding utf8
        if ([string]::IsNullOrWhiteSpace($raw)) { throw 'Empty report' }
        $xml = [xml]::new()
        $xml.XmlResolver = $null
        $xml.LoadXml($raw)
        $cases = @($xml.SelectNodes('/testsuites//testcase'))
        if ($xml.DocumentElement.Name -cne 'testsuites' -or -not $cases.Count -or
            @($cases | Where-Object { [string]::IsNullOrWhiteSpace($_.GetAttribute('name')) }).Count) { throw 'Invalid native Node report' }
    } catch { throw "Missing or malformed frontend Node report: $Report" }
    if (@($xml.SelectNodes('//failure|//error|//skipped')).Count -or
        @($xml.SelectNodes('//testsuite|/testsuites') | Where-Object {
            ($_.HasAttribute('failures') -and [int]$_.GetAttribute('failures') -ne 0) -or
            ($_.HasAttribute('errors') -and [int]$_.GetAttribute('errors') -ne 0) -or
            ($_.HasAttribute('skipped') -and [int]$_.GetAttribute('skipped') -ne 0)
        }).Count) { throw 'Failed, errored or skipped frontend Node assertion' }
    $requirements = @(Get-Content -LiteralPath "$PSScriptRoot/required-frontend-node.json" -Raw -Encoding utf8 | ConvertFrom-Json)
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $coverage = @(
        foreach ($requirement in $requirements) {
            if (-not $requirement.id -or -not $ids.Add($requirement.id) -or
                -not $requirement.file -or -not @($requirement.tests).Count) { throw 'Invalid frontend Node constraint mapping' }
            $file = [IO.Path]::GetFullPath((Join-Path $Root $requirement.file))
            foreach ($name in $requirement.tests) {
                if (-not $name -or -not $expected.Add($file+'#'+$name)) { throw 'Invalid frontend Node assertion mapping' }
                $found = @($cases | Where-Object { $_.GetAttribute('name') -ceq $name })
                if ($found.Count -ne 1 -or -not [IO.Path]::IsPathFullyQualified($found[0].GetAttribute('file')) -or
                    -not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($found[0].GetAttribute('file')), $file)) {
                    throw "Missing or duplicate required frontend Node assertion: $($requirement.file)#$name"
                }
            }
            [pscustomobject]@{id=$requirement.id;file=$requirement.file;tests=$requirement.tests;status='passed'}
        }
    )
    if (-not $coverage.Count) { throw 'No frontend Node constraint mappings' }
    @{status='passed';tests=$cases.Count;requiredTests=$expected.Count;coverage=$coverage;report=[IO.Path]::GetFullPath($Report)}
}
