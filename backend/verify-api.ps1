# Requires PowerShell 7. Run after .\mvnw.cmd verify.
param([int]$Port = 18081)

$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Use a port between 1024 and 65535.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$jar = Join-Path $PSScriptRoot 'target/marketplace-api-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the executable JAR first.' }
$baseUrl = "http://127.0.0.1:$Port"
$results = [System.Collections.Generic.List[object]]::new()
$scenarios = @(
    @{ name = 'healthy'; status = 'UP'; httpStatus = 200; arguments = @() },
    # Force an unhealthy disk-space check without filling or changing the disk.
    @{ name = 'unhealthy'; status = 'DOWN'; httpStatus = 503; arguments = @('--management.health.diskspace.threshold=9223372036854775807B') },
    @{ name = 'recovered'; status = 'UP'; httpStatus = 200; arguments = @() }
)

for ($run = 1; $run -le $scenarios.Count; $run++) {
    $scenario = $scenarios[$run - 1]
    # Refuse an occupied port instead of checking or stopping someone else's API.
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try { $probe.Start() } finally { $probe.Stop() }
    $outLog = Join-Path $PSScriptRoot "target/smoke-$run.stdout.log"
    $errLog = Join-Path $PSScriptRoot "target/smoke-$run.stderr.log"
    $javaArguments = @('-jar', "`"$jar`"", '--server.address=127.0.0.1', "--server.port=$Port") + $scenario.arguments
    $process = Start-Process -FilePath $java -ArgumentList $javaArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    try {
        $ready = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) { throw "API exited; inspect $errLog and $outLog" }
            $log = Get-Content -LiteralPath $outLog -Raw -ErrorAction SilentlyContinue
            if ($log -match 'Started MarketplaceApplication') { $ready = $true; break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw "API startup timed out; inspect $outLog" }
        $checks = @(
            @{ path = '/actuator/health'; method = 'GET'; expected = $scenario.httpStatus; accept = '*/*' },
            @{ path = '/actuator/health'; method = 'GET'; expected = $scenario.httpStatus; accept = 'application/json' },
            @{ path = '/api/status'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/api/not-found'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/actuator'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/actuator/info'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/actuator/env'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/actuator/metrics'; method = 'GET'; expected = 404; accept = 'application/json' },
            @{ path = '/actuator/health'; method = 'POST'; expected = 405; accept = 'application/json' }
        )
        foreach ($check in $checks) {
            $response = Invoke-WebRequest "$baseUrl$($check.path)" -Method $check.method -Headers @{ Accept = $check.accept } -TimeoutSec 5 -SkipHttpErrorCheck
            if ($response.StatusCode -ne $check.expected) { throw "$($check.method) $($check.path) expected $($check.expected), got $($response.StatusCode)" }
            if ($check.path -eq '/actuator/health' -and $check.method -eq 'GET') {
                $contentType = [string]$response.Headers['Content-Type']
                if ($contentType -notmatch '^application/(json|vnd\.spring-boot\.actuator\.v3\+json)(;|$)') { throw "Expected health JSON Content-Type, got $contentType" }
                if ($check.accept -eq 'application/json' -and $contentType -notmatch '^application/json(;|$)') { throw 'JSON content negotiation failed.' }
                # PowerShell can return bytes for Actuator's vendor JSON media type.
                $json = if ($response.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
                $body = $json | ConvertFrom-Json
                if ($body.status -cne $scenario.status) { throw "Expected health $($scenario.status), got $($body.status)" }
                if (@($body.PSObject.Properties).Count -ne 1) { throw 'Health must expose only status, without details or components.' }
            }
            $results.Add([pscustomobject]@{ run = $run; scenario = $scenario.name; method = $check.method; path = $check.path; accept = $check.accept; status = [int]$response.StatusCode; result = 'PASS' })
        }
    } finally {
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id; $process.WaitForExit() }
        $process.Dispose()
    }
}

$results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target/smoke-results.json') -Encoding utf8
$results | Format-Table
