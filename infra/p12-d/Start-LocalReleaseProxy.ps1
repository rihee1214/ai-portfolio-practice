param([Parameter(Mandatory)][string]$Evidence,[Parameter(Mandatory)][int]$ApiPort,
      [Parameter(Mandatory)][int]$HttpsPort,[Parameter(Mandatory)][string]$Dist,
      [Parameter(Mandatory)][string]$SecretsPath,[Parameter(Mandatory)][string]$RunId,
      [Parameter(Mandatory)][string]$WorkspaceRoot)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 7){throw 'PowerShell 7 required'}
. "$PSScriptRoot/../p12-c/ProxyNetwork.ps1"
$workspace=[IO.Path]::GetFullPath($WorkspaceRoot)
if(-not [IO.Path]::IsPathFullyQualified($WorkspaceRoot) -or -not(Test-Path -LiteralPath "$workspace/AGENTS.md" -PathType Leaf)){throw 'Explicit workspace root required'}
function Assert-OwnedPath([string]$Value) {
    $absolute=[IO.Path]::GetFullPath($Value)
    if(-not $absolute.StartsWith("$workspace\.local-tools\",[StringComparison]::OrdinalIgnoreCase)){throw 'Workspace local-tools path required'}
    for($p=$absolute;$p;$p=[IO.Path]::GetDirectoryName($p)){
        if((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Linked path rejected'}
    }
    $absolute
}
$folder=Assert-OwnedPath $Evidence;$distRoot=Assert-OwnedPath $Dist;$secretRoot=Assert-OwnedPath $SecretsPath
if(Test-Path -LiteralPath $folder){throw 'Existing proxy evidence rejected'}
if($RunId -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $ApiPort -lt 20000 -or $ApiPort -gt 65535 -or $HttpsPort -lt 20000 -or $HttpsPort -gt 65535 -or $ApiPort -eq $HttpsPort){throw 'Explicit isolated proxy identity required'}
if(-not(Test-Path -LiteralPath "$distRoot/index.html" -PathType Leaf)){throw 'Dist index missing'}
Get-ChildItem -LiteralPath $distRoot -Recurse -Force | ForEach-Object {if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked dist entry rejected'}}
foreach($file in @('tls.crt','tls.key','reviewers')){Assert-OwnedPath "$secretRoot/$file" | Out-Null;if(-not(Test-Path -LiteralPath "$secretRoot/$file" -PathType Leaf)){throw 'TLS/invitation input missing'}}
New-Item -ItemType Directory -Path "$folder/config" | Out-Null
foreach($name in @('nginx.conf','package-proxy.conf')){
    $value=[IO.File]::ReadAllText("$PSScriptRoot/../p12-c/$name.template").Replace('__HOST__','localhost').Replace('__UPSTREAM__',"host.docker.internal:$ApiPort").Replace('__PORT__',[string]$HttpsPort)
    [IO.File]::WriteAllText("$folder/config/$name",$value)
}
$image='nginx@sha256:5b44a5ab8ab467854f2bf7b835a32f850f32eb414b749fbf7ed506b139cd8d6b'
Invoke-ProxyDocker @('image','inspect',$image,'--format','{{json .}}') | Set-Content "$folder/image.json"
$name='p12d-extra-'+[guid]::NewGuid().ToString('N')
@{name=$name;runId=$RunId;phase='prepared'} | ConvertTo-Json | Set-Content "$folder/proxy-plan.json"
$network=New-IsolatedProxyNetwork -Name $name -Evidence $folder
$arguments=@('run','--pull=never','--detach','--name',$name,'--label',"marketplace.p12d.run=$RunId",'--label','marketplace.scope=p12-d','--user','101:101','--read-only','--network',$name,'--tmpfs','/tmp:uid=101,gid=101,mode=0700,size=33554432','--memory','96m','--cpus','0.5','--publish',"127.0.0.1:${HttpsPort}:8443",'--mount',"type=bind,source=$distRoot,target=/usr/share/nginx/html,readonly",'--mount',"type=bind,source=$folder/config/nginx.conf,target=/etc/nginx/nginx.conf,readonly",'--mount',"type=bind,source=$folder/config/package-proxy.conf,target=/etc/nginx/package-proxy.conf,readonly",'--mount',"type=bind,source=$secretRoot,target=/run/secrets,readonly",'--entrypoint','nginx',$image,'-g','daemon off;')
$id=Invoke-ProxyDocker $arguments
@{container=$id;name=$name;runId=$RunId;network=$network;httpsPort=$HttpsPort;apiPort=$ApiPort;dist=$distRoot;databaseMount=$false;mediaMount=$false} | ConvertTo-Json -Depth 10 | Set-Content "$folder/proxy-owner.json"
$id
