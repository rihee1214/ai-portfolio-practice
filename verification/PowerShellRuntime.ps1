# Exact per-process runtime contract; no PATH, installation or user/global settings.
function Get-VerificationPowerShellPath {
    'C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
}
function Assert-VerificationPowerShellRuntime {
    param([string]$EvidencePath)
    $observed = [ordered]@{
        executable = (Get-Process -Id $PID).Path
        version = $PSVersionTable.PSVersion.ToString()
        edition = $PSVersionTable.PSEdition
        pid = $PID
        caller = @((Get-PSCallStack).ScriptName | Where-Object { $_ } | Select-Object -Unique)
        observedAt = [DateTimeOffset]::UtcNow.ToString('o')
        compatibilitySessions = @(Get-PSSession | Where-Object Name -eq 'WinPSCompatSession').Count
    }
    $required = [IO.Path]::GetFullPath((Get-VerificationPowerShellPath))
    $observed.accepted = $observed.version -ceq '7.6.9' -and $observed.edition -ceq 'Core' -and
        [string]::Equals([IO.Path]::GetFullPath($observed.executable), $required, [StringComparison]::OrdinalIgnoreCase) -and
        $observed.compatibilitySessions -eq 0
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $global:OutputEncoding = [Console]::OutputEncoding
    if ($env:VERIFY_POWERSHELL_EVIDENCE) {
        [void][IO.Directory]::CreateDirectory($env:VERIFY_POWERSHELL_EVIDENCE)
        $record = Join-Path $env:VERIFY_POWERSHELL_EVIDENCE ("$PID-" + [guid]::NewGuid().ToString('N') + '.json')
        [IO.File]::WriteAllText($record, ($observed | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    }
    if ($EvidencePath) {
        if (Test-Path -LiteralPath $EvidencePath) { throw 'Runtime evidence already exists; preserve prior evidence' }
        [IO.File]::WriteAllText($EvidencePath, ($observed | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    }
    if (-not $observed.accepted) { throw "Exact PowerShell 7.6.9 stable executable required; observed $($observed.version) at $($observed.executable); Windows compatibility sessions forbidden" }
    [pscustomobject]$observed
}
function Import-VerificationNetworkModule {
    $null = Assert-VerificationPowerShellRuntime
    # Explicit native loading prevents implicit Windows PowerShell compatibility startup.
    Import-Module NetTCPIP -SkipEditionCheck -Global -ErrorAction Stop
    if ((Get-Command Get-NetRoute -ErrorAction Stop).ModuleName -cne 'NetTCPIP' -or
        @(Get-PSSession | Where-Object Name -eq 'WinPSCompatSession').Count) {
        throw 'Native NetTCPIP is required; Windows compatibility sessions forbidden'
    }
}
