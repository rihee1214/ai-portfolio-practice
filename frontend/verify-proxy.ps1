# Requires PowerShell 7 and a running Vite server plus Spring Boot API.
param([string]$BaseUrl = 'http://127.0.0.1:5174', [ValidateSet('UP','DOWN')][string]$ExpectedHealth = 'UP')
$ErrorActionPreference = 'Stop'
$results = [System.Collections.Generic.List[object]]::new()
$healthCode = if ($ExpectedHealth -eq 'UP') { 200 } else { 503 }
$checks = @(
    @{ path='/actuator/health'; method='GET'; expected=$healthCode },
    @{ path='/actuator/health?probe=1'; method='GET'; expected=$healthCode },
    @{ path='/actuator/health'; method='POST'; expected=405 },
    @{ path='/api'; method='GET'; expected=404 },
    @{ path='/api/status'; method='GET'; expected=404 },
    @{ path='/api/not-found'; method='GET'; expected=404 },
    @{ path='/actuator'; method='GET'; expected=404 },
    @{ path='/actuator/env'; method='GET'; expected=404 },
    @{ path='/actuator/metrics'; method='GET'; expected=404 },
    @{ path='/actuator/health/'; method='GET'; expected=404 },
    @{ path='/actuator/health/liveness'; method='GET'; expected=404 }
)
foreach ($check in $checks) {
    $response = Invoke-WebRequest "$BaseUrl$($check.path)" -Method $check.method -Headers @{Accept='application/json'} -SkipHttpErrorCheck -TimeoutSec 15
    if ($response.StatusCode -ne $check.expected) { throw "$($check.path): expected $($check.expected), got $($response.StatusCode)" }
    $body = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    if ($body -match '<div id="root"') { throw "SPA fallback at $($check.path)" }
    if ($check.path -like '/actuator/health*' -and $check.method -eq 'GET' -and $check.expected -ne 404) {
        $json = $body | ConvertFrom-Json
        if ($json.status -cne $ExpectedHealth -or @($json.PSObject.Properties).Count -ne 1) { throw 'Health must contain only the expected status.' }
    }
    $results.Add([pscustomobject]@{path=$check.path;method=$check.method;status=[int]$response.StatusCode;body=$body;pass=$true})
}
$results | ConvertTo-Json -Depth 5
