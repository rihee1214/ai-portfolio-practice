function Get-ContainerReproductionGuardFiles([string]$Root) {
    $required=Get-Content -Encoding utf8 "$PSScriptRoot/required-container-reproduction-guards.json" -Raw | ConvertFrom-Json
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if(-not @($required.files).Count){throw 'Explicit Node guard files required'}
    foreach($file in $required.files) {
        if([IO.Path]::IsPathRooted($file) -or $file -match '(^|[\\/])\.\.([\\/]|$)' -or -not $seen.Add($file) -or -not(Test-Path -LiteralPath (Join-Path $Root $file) -PathType Leaf)){throw 'Invalid duplicate or missing registered Node guard file'}
        Join-Path $Root $file
    }
}

function Assert-ContainerReproductionGuardTests([string]$Report) {
    [xml]$xml=Get-Content -LiteralPath $Report -Raw
    $required=Get-Content "$PSScriptRoot/required-container-reproduction-guards.json" -Raw | ConvertFrom-Json
    $cases=@($xml.SelectNodes('//testcase'))
    if($cases.Count -ne $required.tests.Count -or @($xml.SelectNodes('//failure|//error|//skipped')).Count -or
        @($xml.SelectNodes('//testsuite') | Where-Object { [int]$_.failures -gt 0 -or [int]$_.errors -gt 0 -or [int]$_.skipped -gt 0 }).Count) {
        throw 'Missing, failed or skipped container reproduction guards'
    }
    foreach($name in $required.tests) {
        if(@($cases | Where-Object name -CEQ $name).Count -ne 1){throw "Missing or duplicate container reproduction guard: $name"}
    }
    @{status='passed';tests=$cases.Count;required=$required.tests}
}

function Assert-ContainerReproductionBrowserEvidence($Browser,$Deadline) {
    if($Browser.errors.Count -or $Browser.stats.expected -ne 2 -or $Browser.stats.skipped -or $Browser.stats.unexpected -or $Browser.stats.flaky){throw 'Container reproduction requires exact two non-retried passing browser cases'}
    $cases=@(Read-BrowserCases $Browser.suites)
    $requirements=Get-Content "$PSScriptRoot/required-browser-container-reproduction.json" -Raw | ConvertFrom-Json
    $coverage=@(Assert-BrowserGroupCoverage @{id='container-reproduction';browser=@{}} $cases $requirements)
    Assert-BrowserDeadlineEvidence $Deadline $cases
    @{cases=$cases;coverage=$coverage}
}

function Invoke-ContainerReproduction {
    param([string]$Root,[string]$Evidence,[string]$SourceHead)
    $node=Join-Path $Root '.local-tools/node-v24.22.0-win-x64/node.exe'
    $package=Join-Path $Evidence 'db/groups/package-runtime'
    # Producer is in this exact root run; never default to a retained D/private path.
    Read-VerifiedPackageBundle -Folder $package | Out-Null
    $request=@{evidence=(Join-Path $Evidence 'container-reproduction');jar=(Join-Path $Evidence 'db/build/marketplace-api-0.0.1-SNAPSHOT.jar');
        dist=(Join-Path $package 'browser-app');jarProvenance=(Join-Path $package 'package-proof.json');distProvenance=(Join-Path $package 'package-dist.json');
        jdk=(Join-Path $Root '.local-tools/jdk-21.0.12.1+1');node=$node;browsers=(Join-Path $Root '.local-tools/playwright');
        sourceRoot=$Root;sourceHead=$SourceHead;sourceInputs=(Join-Path $Evidence 'inputs.json');sourceInputsSha256=(Get-FileHash (Join-Path $Evidence 'inputs.json')).Hash}
    foreach($file in @($request.jar,$request.jarProvenance,$request.distProvenance,$node,(Join-Path $request.jdk 'bin/java.exe'),(Join-Path $request.dist 'index.html'))) {
        if(-not (Test-Path -LiteralPath $file -PathType Leaf)){throw "Explicit container reproduction prerequisite missing: $file"}
    }
    if(-not (Test-Path -LiteralPath $request.browsers -PathType Container)){throw 'Explicit container reproduction browser cache missing'}
    $requestPath=Join-Path $Evidence 'container-reproduction-request.json'
    if(Test-Path -LiteralPath $requestPath){throw 'Fresh root phase request required'}
    $request | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 $requestPath
    & $node "$Root/infra/p12-e/reproduce.cjs" $requestPath 2>&1 | Set-Content "$Evidence/container-reproduction.txt"
    if($LASTEXITCODE -ne 0){throw 'Fresh container reproduction failed; original phase evidence retained'}
    & $node "$PSScriptRoot/ContainerReproduction.cjs" $requestPath 2>&1 | Set-Content "$Evidence/container-reproduction-proof.json"
    if($LASTEXITCODE -ne 0){throw 'Container reproduction source/artifact/resource proof rejected'}
    $proof=Get-Content "$Evidence/container-reproduction-proof.json" -Raw | ConvertFrom-Json
    $browser=Get-Content "$($request.evidence)/browser-report.json" -Raw | ConvertFrom-Json
    $browserProof=Assert-ContainerReproductionBrowserEvidence $browser (Get-Content "$($request.evidence)/browser-deadlines.json" -Raw | ConvertFrom-Json)
    $browserProof.coverage | ConvertTo-Json -Depth 6 | Set-Content "$Evidence/browser-coverage-container-reproduction.json"
    $browserProof.cases | ConvertTo-Json -Depth 6 | Set-Content "$Evidence/container-reproduction/browser-cases.json"
    @{proof=$proof;cases=$browserProof.cases}
}
