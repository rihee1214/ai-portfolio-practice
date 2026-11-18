param([Parameter(Mandatory)][string]$Request)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$root/verification/PowerShellRuntime.ps1"
$null=Assert-VerificationPowerShellRuntime
. "$root/verification/DatabaseTarget.ps1"
. "$root/verification/DbGroups.ps1"
. "$root/verification/Gate.ps1"
. "$root/verification/CompiledClasspath.ps1"
$requestPath=[IO.Path]::GetFullPath($Request)
$requestData=Get-Content -Encoding utf8 -LiteralPath $requestPath -Raw|ConvertFrom-Json
$output=[IO.Path]::GetFullPath($requestData.output)
$allowed=[IO.Path]::GetFullPath((Join-Path $root '.local-tools'))+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetDirectoryName($requestPath) -ine $output -or (Test-Path -LiteralPath "$output/result.json")){throw 'Fresh owned worker request/evidence required'}
$registry=Get-DbGroupRegistry
Assert-DbGroupRegistration -Registry $registry -Root $root
$definition=@($registry.groups|Where-Object id -CEQ $requestData.definition.id)
if($definition.Count -ne 1 -or [IO.Path]::GetFileName($output) -cne $definition[0].id){throw 'Registered owned worker group required'}
$definition=$definition[0];$id=$definition.id
$endpoint=Resolve-TestPostgresEndpoint -Port $requestData.endpoint.port -Instance $(if($requestData.endpoint.port -eq 15433){''}else{$requestData.endpoint.instance}) -Container $requestData.endpoint.container
if($env:A3_EVIDENCE -ine $output -or $env:D2_DB_NAME -cnotmatch '^d2_it_[0-9a-f]{32}$' -or $env:D2_DB_URL -cne "jdbc:postgresql://127.0.0.1:$($endpoint.port)/$env:D2_DB_NAME"){throw 'Private worker environment identity mismatch'}
$dbOutput=Split-Path (Split-Path $output -Parent) -Parent
$commonBuild=[IO.Path]::GetFullPath((Join-Path $dbOutput 'build'))
if([IO.Path]::GetFullPath($requestData.build) -ine $commonBuild){throw 'Same-run common build required'}
$build=Join-Path $output 'build'
$secrets=@{database=$env:D2_DB_PASSWORD;administrator=$env:D2_ADMIN_PASSWORD}
foreach($secret in $secrets.Values){if(-not $secret){throw 'Worker secret must be supplied only through its environment'}}
$clock=[Diagnostics.Stopwatch]::StartNew()
$group=@{id=$id;status='failed';database=$env:D2_DB_NAME;media="$output/media";evidence=$output;started=(Get-Date).ToString('o');classes=$definition.classes;requestSha256=(Get-FileHash -LiteralPath $requestPath).Hash.ToLowerInvariant();workerPid=$PID;build=$build;javaTmp="$output/java-tmp"}
try {
    $bindingClock=[Diagnostics.Stopwatch]::StartNew()
    New-Item -ItemType Directory -Path $build,"$output/java-tmp"|Out-Null
    if([IO.Path]::GetFullPath($requestData.compiledManifest) -ine (Join-Path $dbOutput 'common-build-manifest.json')){throw 'Same-run compiled manifest required'}
    $node=Join-Path $root '.local-tools/node-v24.22.0-win-x64/node.exe'
    $bindingText=& $node "$root/verification/CompiledBuild.cjs" bind $root $commonBuild $requestData.compiledManifest $requestData.compiledManifestSha256
    if($LASTEXITCODE -ne 0){throw 'Common compiled worker binding failed'}
    $binding=$bindingText|ConvertFrom-Json
    $bindingText|Set-Content -Encoding utf8 "$output/common-build-binding.json"
    $group.commonBuildBinding=$binding;$group.compiledBindingSeconds=$bindingClock.Elapsed.TotalSeconds;$group.buildCopySeconds=0
    $reports=Join-Path $output 'failsafe-reports'
    $arguments=@('-B','-ntp','-Pdb-it','-Dmaven.repo.local=../.local-tools/m2/repository',"-Dmarketplace.build.directory=$build","-Djava.io.tmpdir=$output/java-tmp","-Dmarketplace.failsafe.classesDirectory=$($binding.classesDirectory)","-Dmarketplace.failsafe.testClassesDirectory=$($binding.testClassesDirectory)","-Dit.test=$($definition.classes -join ',')","-Dmarketplace.failsafe.reports=$reports",'failsafe:integration-test','failsafe:verify')
    if($requestData.offline){$arguments=@('-o')+$arguments}
    Push-Location $PSScriptRoot
    try{& ./mvnw.cmd @arguments 2>&1|ForEach-Object{$line=[string]$_;foreach($secret in $secrets.Values){$line=$line.Replace($secret,'[REDACTED]')};$line}|Set-Content -Encoding utf8 "$output/maven.txt";$group.exitCode=$LASTEXITCODE}finally{Pop-Location}
    if($group.exitCode -ne 0){throw 'Group JVM failed'}
    $group.actualCompiledClasspath=Read-VerifiedCompiledClasspath -ReportDirectory $reports -ClassesDirectory $binding.classesDirectory -TestClassesDirectory $binding.testClassesDirectory -PrivateBuild $build
    $created=@(Select-String -LiteralPath "$output/maven.txt" -Pattern '^D2_CREATED_DATABASE='|ForEach-Object{$_.Line.Substring(20)})
    $group.databaseProof=Assert-DbGroupDatabases -ExpectedCount $definition.expectedDatabaseCount -Primary $env:D2_DB_NAME -Created $created
    if($id -cne 'phone-provider'){
        $identity=Get-Content -Encoding utf8 "$output/execution-identity.json" -Raw|ConvertFrom-Json
        $expectedMedia=if($id -ceq 'release-recovery'){(Get-Content -Encoding utf8 "$output/release-lifecycle.json" -Raw|ConvertFrom-Json).sourceMedia}else{"$output\media"}
        if($identity.database -cne $env:D2_DB_NAME -or $identity.media -cne $expectedMedia){throw 'Group scope identity mismatch'}
        $group.media=$expectedMedia;$group.pid=$identity.pid;$group.httpPort=$identity.httpPort
    }else{$group.database=$null;$group.media=$null}
    $cases=@(Read-JUnitCases $reports)
    $expected=@($definition.tests.new)
    Assert-Coverage $cases @([pscustomobject]@{id=$id;tests=$expected})|ConvertTo-Json -Depth 5|Set-Content -Encoding utf8 "$output/coverage.json"
    foreach($case in $cases){$actual=$case.classname+'#'+($case.name -replace '\(.*$','');if($expected -cnotcontains $actual){throw "Unexpected executed test: $actual"}}
    foreach($test in $definition.tests){$actualCases=@($cases|Where-Object{($_.classname+'#'+($_.name -replace '\(.*$','')) -ceq $test.new});if($actualCases.Count -ne $test.invocations){throw "Missing or duplicate parameter invocation: $($test.new)"}}
    if($id -ceq 'release-recovery'){$group.browserApplication=Assert-ReleaseRecoveryEvidence -Folder $output -Group $definition}
    elseif($id -ceq 'browser-api' -or $definition.browser){
        $group.browserApplication=if($id -ceq 'package-runtime'){Read-VerifiedPackageBundle -Folder $output}else{Read-VerifiedBrowserBundle -Folder $output}
        $browser=Get-Content -Encoding utf8 "$output/browser-report.json" -Raw|ConvertFrom-Json
        if($browser.errors.Count -or $browser.stats.skipped -or $browser.stats.unexpected -or $browser.stats.flaky){throw 'Browser group failed or retried'}
        $browserCases=@(Read-BrowserCases $browser.suites)
        Assert-BrowserDeadlineEvidence (Get-Content -Encoding utf8 "$output/browser-deadlines.json" -Raw|ConvertFrom-Json) $browserCases
        $requirements=if($definition.browser){$definition.browser.requirements}else{'required-browser.json'}
        Assert-BrowserGroupCoverage $definition $browserCases (Get-Content -Encoding utf8 "$root/verification/$requirements" -Raw|ConvertFrom-Json)|ConvertTo-Json -Depth 6|Set-Content -Encoding utf8 "$output/browser-coverage.json"
        if($definition.browser -and $id -cne 'package-runtime'){$proof=Get-Content -Encoding utf8 "$output/site-maintenance-lifecycle.json" -Raw|ConvertFrom-Json;$group.browserIsolation=Assert-MaintenanceBrowserLifecycle $group $proof @()}
    }
    $cases|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 "$output/java-cases.json"
    $group.tests=$cases.Count;$group.status='passed'
}catch{$group.error=$_.Exception.Message}
finally{$group.seconds=$clock.Elapsed.TotalSeconds;$group.finished=(Get-Date).ToString('o');$group|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8 "$output/result.json"}
if($group.status -cne 'passed'){Write-Error $group.error -ErrorAction Continue;exit 1}
"PASS isolated group $id"
