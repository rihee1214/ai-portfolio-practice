$script:ProxyPreparationClock = [Diagnostics.Stopwatch]::StartNew()
. "$PSScriptRoot/../../verification/PowerShellRuntime.ps1"
function Invoke-ProxyDocker {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $remaining = 60000 - $script:ProxyPreparationClock.ElapsedMilliseconds
    if ($remaining -le 0) { throw 'Proxy preparation absolute deadline exceeded' }
    $start = [Diagnostics.ProcessStartInfo]::new((Get-Command docker -ErrorAction Stop).Source)
    $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$start
    try {
        [void]$process.Start()
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit([int]$remaining)) {
            $process.Kill($true); [void]$process.WaitForExit(1000)
            throw 'Owned Docker preparation command exceeded remaining absolute deadline'
        }
        $out=$stdout.GetAwaiter().GetResult(); $err=$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Owned Docker $($Arguments[0]) failed: $err" }
        $out.Trim()
    } finally { $process.Dispose() }
}
function ConvertTo-ProxyRange {
    param([Parameter(Mandatory)][string]$Cidr)
    if ($Cidr -notmatch '^(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})$') { throw 'Invalid IPv4 CIDR inventory' }
    $address=[Net.IPAddress]::Parse($Matches[1]); $prefix=[int]$Matches[2]
    if ($address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $prefix -gt 32) { throw 'Invalid IPv4 prefix inventory' }
    $bytes=$address.GetAddressBytes()
    [uint64]$value=[uint64]$bytes[0]*16777216+[uint64]$bytes[1]*65536+[uint64]$bytes[2]*256+$bytes[3]
    [uint64]$size=[Math]::Pow(2,32-$prefix)
    [uint64]$first=$value-($value%$size)
    @{first=$first;last=($first+$size-1);cidr=$Cidr}
}
function ConvertFrom-ProxyAddress {
    param([uint64]$Value)
    @(([Math]::Floor($Value/16777216)),([Math]::Floor($Value/65536)%256),([Math]::Floor($Value/256)%256),($Value%256)) -join '.'
}
function Select-ProxySubnet {
    param([string[]]$Excluded=@())
    $ranges=@($Excluded | Sort-Object -Unique | ForEach-Object { ConvertTo-ProxyRange $_ } | Sort-Object first)
    # Interval jumps bound the search by inventory size, including broad VPN routes.
    foreach ($block in @('10.240.0.0/12','10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')) {
        $private=ConvertTo-ProxyRange $block; [uint64]$candidate=$private.first
        for ($step=0; $step -le $ranges.Count; $step++) {
            if ($candidate+15 -gt $private.last) { break }
            $collision=@($ranges | Where-Object { $candidate -le $_.last -and ($candidate+15) -ge $_.first } | Select-Object -First 1)
            if (-not $collision.Count) { return (ConvertFrom-ProxyAddress $candidate)+'/28' }
            $candidate=[uint64]([Math]::Floor(($collision[0].last+16)/16)*16)
        }
    }
    throw 'No nonoverlapping private proxy subnet available'
}
function New-IsolatedProxyNetwork {
    param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)][string]$Evidence)
    for ($attempt=1; $attempt -le 3; $attempt++) {
        $ids=@((Invoke-ProxyDocker @('network','ls','--format','{{.ID}}')).Split("`n",[StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object { $_.Trim() })
        if (-not $ids.Count) { throw 'Docker network inventory is missing' }
        $networks=@((Invoke-ProxyDocker (@('network','inspect')+$ids)) | ConvertFrom-Json)
        if ($networks.Count -ne $ids.Count) { throw 'Incomplete Docker network inventory' }
        Import-VerificationNetworkModule
        $routes=@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object DestinationPrefix,NextHop,InterfaceAlias)
        if (-not $routes.Count) { throw 'Host route inventory is missing' }
        $excluded=@($networks | ForEach-Object { $_.IPAM.Config | ForEach-Object { if ($_.Subnet -and $_.Subnet -notmatch ':') { $_.Subnet } } })
        $excluded+=@($routes | Where-Object DestinationPrefix -NE '0.0.0.0/0' | ForEach-Object DestinationPrefix)
        $subnet=Select-ProxySubnet -Excluded $excluded
        @{networks=$networks;hostRoutes=$routes;excluded=$excluded;selected=$subnet;attempt=$attempt;defaultRouteExcluded=$true} |
            ConvertTo-Json -Depth 15 | Set-Content "$Evidence/network-inventory-$attempt.json"
        try {
            $id=Invoke-ProxyDocker @('network','create','--subnet',$subnet,'--label','marketplace.scope=p12-c',$Name)
            $allocation=@{id=$id;name=$Name;subnet=$subnet;attempt=$attempt;hostRouteAndDockerOverlap=$false}
            $allocation | ConvertTo-Json | Set-Content "$Evidence/network-allocation.json"
            return $allocation
        } catch {
            $_.Exception.Message | Set-Content "$Evidence/network-create-failure-$attempt.txt"
            if ($_.Exception.Message -notmatch 'overlap' -or $attempt -eq 3) { throw }
        }
    }
}
