param([Parameter(Mandatory)][string]$Evidence, [Parameter(Mandatory)][int]$ApiPort,
      [Parameter(Mandatory)][int]$HttpsPort, [Parameter(Mandatory)][string]$GatePassword,
      [Parameter(Mandatory)][string]$Dist)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/ProxyNetwork.ps1"
$folder = [IO.Path]::GetFullPath($Evidence)
$root = [IO.Path]::GetFullPath("$PSScriptRoot/../..")
if (-not $folder.StartsWith("$root\.local-tools\", [StringComparison]::OrdinalIgnoreCase)) { throw 'Fresh owned evidence required' }
New-Item -ItemType Directory -Path "$folder/proxy-config","$folder/proxy-secrets" | Out-Null
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $san = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName('localhost'); $san.AddIpAddress([Net.IPAddress]::Loopback)
    $request.CertificateExtensions.Add($san.Build())
    $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddDays(2))
    [IO.File]::WriteAllText("$folder/proxy-secrets/tls.crt",$certificate.ExportCertificatePem())
    [IO.File]::WriteAllText("$folder/proxy-secrets/tls.key",$rsa.ExportPkcs8PrivateKeyPem())
} finally { $rsa.Dispose() }
$hash = [Convert]::ToBase64String([Security.Cryptography.SHA1]::HashData([Text.Encoding]::UTF8.GetBytes($GatePassword)))
[IO.File]::WriteAllText("$folder/proxy-secrets/reviewers", "reviewer:{SHA}$hash`n")
foreach ($name in @('nginx.conf','package-proxy.conf')) {
    $value = [IO.File]::ReadAllText("$PSScriptRoot/$name.template").Replace('__HOST__','localhost').Replace('__UPSTREAM__',"host.docker.internal:$ApiPort").Replace('__PORT__',[string]$HttpsPort)
    [IO.File]::WriteAllText("$folder/proxy-config/$name",$value)
}
$image = 'nginx@sha256:5b44a5ab8ab467854f2bf7b835a32f850f32eb414b749fbf7ed506b139cd8d6b'
$imageProof = Invoke-ProxyDocker @('image','inspect',$image,'--format','{{json .}}')
$imageProof | Set-Content "$folder/nginx-image.json"
$name = 'p12c-' + [guid]::NewGuid().ToString('N')
# Persist ownership before Docker can create a container, including failed/interrupted preparation.
@{name=$name;label='marketplace.scope=p12-c';created=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content "$folder/proxy-plan.json"
$network = New-IsolatedProxyNetwork -Name $name -Evidence $folder
$arguments = @('run','--pull=never','--detach','--name',$name,'--label','marketplace.scope=p12-c','--user','101:101','--read-only',
    '--network',$name,
    '--tmpfs','/tmp:uid=101,gid=101,mode=0700,size=33554432','--memory','96m','--cpus','0.5',
    '--publish',"127.0.0.1:${HttpsPort}:8443",'--mount',"type=bind,source=$Dist,target=/usr/share/nginx/html,readonly",
    '--mount',"type=bind,source=$folder/proxy-config/nginx.conf,target=/etc/nginx/nginx.conf,readonly",
    '--mount',"type=bind,source=$folder/proxy-config/package-proxy.conf,target=/etc/nginx/package-proxy.conf,readonly",
    '--mount',"type=bind,source=$folder/proxy-secrets,target=/run/secrets,readonly",'--entrypoint','nginx',$image,'-g','daemon off;')
$id = Invoke-ProxyDocker $arguments
@{container=$id;name=$name;network=$network.id;subnet=$network.subnet;image=$image;httpsPort=$HttpsPort;apiPort=$ApiPort;dist=$Dist;databaseMount=$false;mediaMount=$false} | ConvertTo-Json | Set-Content "$folder/proxy-owner.json"
$id
