$ErrorActionPreference='Stop'
. "$PSScriptRoot/../infra/p12-c/ProxyNetwork.ps1"
if ((Select-ProxySubnet) -cne '10.240.0.0/28') { throw 'Empty inventory subnet differs' }
if ((Select-ProxySubnet @('10.240.0.0/28')) -cne '10.240.0.16/28') { throw 'Occupied interval was reused' }
if ((Select-ProxySubnet @('10.0.0.0/8','172.16.0.0/12')) -cne '192.168.0.0/28') { throw 'Broad host routes were ignored' }
$rejected=$false
try { Select-ProxySubnet @('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16') | Out-Null } catch { $rejected=$true }
if (-not $rejected) { throw 'Exhausted private space was accepted' }
$rejected=$false
try { Select-ProxySubnet @('10.240.0.1/33') | Out-Null } catch { $rejected=$true }
if (-not $rejected) { throw 'Malformed network inventory was accepted' }
'PASS proxy subnet allocator: deterministic allocation, interval overlap, broad routes, exhaustion and malformed inventory'
