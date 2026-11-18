function Invoke-PreflightLoggedCommand([string]$Program,[string[]]$Arguments,[string]$Log) {
    # A silent successful native producer still owns a consumable log. Preserve old logs.
    $stream=[IO.File]::Open($Log,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $stream.Dispose()
    & $Program @Arguments 2>&1 | Set-Content -Encoding utf8 -LiteralPath $Log
    $LASTEXITCODE
}

function Invoke-VerificationPreflight([string]$Root,[string]$Evidence,$Selection,$Settings) {
    if (Test-Path -LiteralPath $Evidence) { throw 'Fresh preflight evidence directory required' }
    New-Item -ItemType Directory -Path $Evidence | Out-Null
    $previousRuntimeEvidence=$env:VERIFY_POWERSHELL_EVIDENCE
    $env:VERIFY_POWERSHELL_EVIDENCE=Join-Path $Evidence 'powershell-processes'
    try {
    $runtime = Assert-VerificationPowerShellRuntime -EvidencePath "$Evidence/powershell.json"
    $request = @{selection=$Selection;runtime=$runtime;settings=$Settings}
    $request | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 "$Evidence/request.json"
    $files = @(& git -c "safe.directory=$($Root.Replace('\','/'))" ls-files --cached --others --exclude-standard -- backend frontend infra verification verify.ps1)
    if ($LASTEXITCODE -ne 0) { throw 'Preflight source inventory failed' }
    foreach ($file in @($files | Sort-Object -Unique | Where-Object { $_ -like '*.ps1' -and (Test-Path -LiteralPath $_ -PathType Leaf) })) {
        $parseErrors=$null; $tokens=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $Root $file),[ref]$tokens,[ref]$parseErrors)
        if ($parseErrors.Count) { $parseErrors | Out-String | Set-Content -Encoding utf8 "$Evidence/powershell-parse-errors.txt"; throw "PowerShell syntax failed: $file" }
    }
    'passed: all current source PowerShell files parsed' | Set-Content -Encoding utf8 "$Evidence/powershell-parse.txt"
    $node=Join-Path $Root '.local-tools/node-v24.22.0-win-x64/node.exe'
    . "$Root/verification/ContainerReproduction.ps1"
    $guardFiles=@(Get-ContainerReproductionGuardFiles -Root $Root)
    $guardExit=Invoke-PreflightLoggedCommand -Program $node -Arguments (@('--test','--test-reporter=junit',"--test-reporter-destination=$Evidence/container-reproduction-guard-tests.xml")+$guardFiles) -Log "$Evidence/container-reproduction-guard-tests.txt"
    if ($guardExit -ne 0) { throw 'Preflight registered Node guards failed' }
    Assert-ContainerReproductionGuardTests -Report "$Evidence/container-reproduction-guard-tests.xml" | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$Evidence/container-reproduction-guard-coverage.json"
    $preflightExit=Invoke-PreflightLoggedCommand -Program $node -Arguments @("$Root/verification/Preflight.cjs",'--run',"$Evidence/request.json") -Log "$Evidence/preflight.txt"
    if ($preflightExit -ne 0) { throw "Cheap preflight failed; retained evidence: $Evidence" }
    } finally { $env:VERIFY_POWERSHELL_EVIDENCE=$previousRuntimeEvidence }
}

function Assert-VerificationPreflight([string]$Root,[string]$Evidence,$Selection,$Settings) {
    $request=Get-Content -Encoding utf8 -LiteralPath "$Evidence/request.json" -Raw | ConvertFrom-Json
    if (-not $request.runtime.accepted -or $request.runtime.version -cne '7.6.9' -or -not [string]::Equals([IO.Path]::GetFullPath($request.runtime.executable),[IO.Path]::GetFullPath((Get-VerificationPowerShellPath)),[StringComparison]::OrdinalIgnoreCase)) { throw 'Preflight parent runtime identity invalid' }
    $encodedSelection=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((@{selection=$Selection;settings=$Settings}|ConvertTo-Json -Depth 12 -Compress)))
    & "$Root/.local-tools/node-v24.22.0-win-x64/node.exe" "$Root/verification/Preflight.cjs" --assert "$Evidence/request.json" $encodedSelection
    if ($LASTEXITCODE -ne 0) { throw 'Passed same-source preflight required before execution' }
    . "$Root/verification/ContainerReproduction.ps1"
    Assert-ContainerReproductionGuardTests -Report "$Evidence/container-reproduction-guard-tests.xml" | Out-Null
}
