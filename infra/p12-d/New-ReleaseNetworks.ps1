param([Parameter(Mandatory)][string]$Project,[Parameter(Mandatory)][string]$RunId,
      [Parameter(Mandatory)][string]$Evidence,[Parameter(Mandatory)][ValidateSet('compose','host-jar')][string]$Execution)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../p12-c/ProxyNetwork.ps1"
$null = Assert-VerificationPowerShellRuntime
if($Project -notmatch '^p12d-[a-z0-9-]{8,70}$' -or $RunId -notmatch '^[0-9a-f-]{36}$'){throw 'Explicit owned network identity required'}
$result=@{}
foreach($role in @('data','edge')) {
    $name=$Project+'_'+$role
    for($attempt=1;$attempt -le 3;$attempt++) {
        $ids=@((Invoke-ProxyDocker @('network','ls','--format','{{.ID}}')).Split("`n",[StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object {$_.Trim()})
        if(-not $ids.Count){throw 'Docker network inventory missing'}
        $networks=@((Invoke-ProxyDocker (@('network','inspect')+$ids)) | ConvertFrom-Json)
        if($networks.Count -ne $ids.Count){throw 'Incomplete network inventory'}
        if(@($networks | Where-Object Name -EQ $name).Count){throw 'Existing network rejected'}
        Import-VerificationNetworkModule
        $routes=@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object DestinationPrefix,NextHop,InterfaceAlias)
        if(-not $routes.Count){throw 'Host route inventory missing'}
        $excluded=@($networks | ForEach-Object {$_.IPAM.Config | ForEach-Object {if($_.Subnet -and $_.Subnet -notmatch ':'){$_.Subnet}}})
        $excluded+=@($routes | Where-Object DestinationPrefix -NE '0.0.0.0/0' | ForEach-Object DestinationPrefix)
        $subnet=Select-ProxySubnet -Excluded $excluded
        @{networks=$networks;routes=$routes;selected=$subnet;role=$role;attempt=$attempt} | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 "$Evidence/network-$role-$attempt.json"
        $internal=($role -eq 'data')
        $arguments=@('network','create','--subnet',$subnet,'--label',"marketplace.p12d.run=$RunId",'--label',"com.docker.compose.project=$Project")
        if($internal){$arguments+='--internal'}
        $arguments+=$name
        try {
            $id=Invoke-ProxyDocker $arguments
            $result[$role]=@{id=$id;name=$name;subnet=$subnet;internal=$internal}
            break
        } catch {
            $_.Exception.Message | Set-Content -Encoding UTF8 "$Evidence/network-$role-$attempt-failed.txt"
            if($_.Exception.Message -notmatch 'overlap' -or $attempt -eq 3){throw}
        }
    }
}
$result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 "$Evidence/network-ownership.json"
