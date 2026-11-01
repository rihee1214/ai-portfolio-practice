param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath((Join-Path $workspace $OutputPath))
if (-not $output.StartsWith(($workspace + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must remain inside workspace' }
if (Test-Path -LiteralPath $output) { throw 'Existing evidence must not be overwritten' }
$expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
$conflicts = [Collections.Generic.List[object]]::new()
$manifestRecords = [Collections.Generic.List[object]]::new()
function Add-Manifest([string]$ManifestPath, [string]$DefaultRoot) {
    $absolute = if ([IO.Path]::IsPathRooted($ManifestPath)) { [IO.Path]::GetFullPath($ManifestPath) } else { [IO.Path]::GetFullPath((Join-Path $workspace $ManifestPath)) }
    $doc = Get-Content -LiteralPath $absolute -Raw | ConvertFrom-Json
    $rowRoot = $DefaultRoot
    if ($doc -is [array]) { $rows = @($doc) }
    elseif ($doc.path -and $doc.sha256) { $rows = @($doc) }
    elseif ($doc.files) {
        $rows = @($doc.files)
        if ($doc.root) {
            $rowRoot = if ([IO.Path]::IsPathRooted($doc.root)) { $doc.root } else { Join-Path $workspace $doc.root }
        }
    } else { throw "Unsupported preservation manifest: $absolute" }
    foreach ($row in $rows) {
        if (-not $row.path -or -not $row.sha256 -or $row.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid manifest entry: $absolute" }
        $path = if ([IO.Path]::IsPathRooted($row.path)) {
            [IO.Path]::GetFullPath($row.path)
        } elseif ($row.path.Replace('\', '/').StartsWith('.local-tools/', [StringComparison]::OrdinalIgnoreCase)) {
            # These manifest entries are workspace-relative; root is descriptive metadata.
            [IO.Path]::GetFullPath((Join-Path $workspace $row.path))
        } else {
            [IO.Path]::GetFullPath((Join-Path $rowRoot $row.path))
        }
        $sha = $row.sha256.ToUpperInvariant()
        if ($expected.ContainsKey($path) -and $expected[$path] -ne $sha) { $conflicts.Add([pscustomobject]@{path=$path;first=$expected[$path];other=$sha;manifest=$absolute}) }
        else { $expected[$path] = $sha }
    }
    $manifestRecords.Add([pscustomobject]@{path=$absolute;sha256=(Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash;entries=$rows.Count})
}
$baselinePath = Join-Path $PSScriptRoot 'implementation-preflight-20261030/B-commit-failure-preservation.json'
$baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
foreach ($manifest in $baseline.manifests) { Add-Manifest $manifest $workspace }
$baselineUnique = $expected.Count
if ($baselineUnique -ne $baseline.files) { throw "Baseline unique count mismatch: $baselineUnique vs $($baseline.files)" }
foreach ($manifest in Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter '*manifest*.json' | Sort-Object Name) { Add-Manifest $manifest.FullName $workspace }
foreach ($manifest in Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse -Filter 'artifact-manifest.json' | Sort-Object FullName) { Add-Manifest $manifest.FullName $manifest.DirectoryName }
$mismatch = [Collections.Generic.List[object]]::new()
$started = (Get-Date).ToString('o')
foreach ($entry in $expected.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Key -PathType Leaf)) { $mismatch.Add([pscustomobject]@{path=$entry.Key;reason='missing'}); continue }
    $actual = (Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash
    if ($actual -ne $entry.Value) { $mismatch.Add([pscustomobject]@{path=$entry.Key;reason='hash';expected=$entry.Value;actual=$actual}) }
}
$result = [ordered]@{started=$started;finished=(Get-Date).ToString('o');baselineReference=$baselinePath;baselineUnique=$baselineUnique;files=$expected.Count;manifests=$manifestRecords.ToArray();conflicts=$conflicts.ToArray();mismatch=$mismatch.ToArray();scope='Read-only preserved evidence hashes; not runtime or full requirement verification'}
$result | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $output
[pscustomobject]@{files=$expected.Count;baseline=$baselineUnique;manifests=$manifestRecords.Count;conflicts=$conflicts.Count;mismatch=$mismatch.Count;output=$output} | ConvertTo-Json
if ($conflicts.Count -or $mismatch.Count) { throw 'Preserved evidence audit failed; inspect retained output' }
