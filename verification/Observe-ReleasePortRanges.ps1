param([Parameter(Mandatory)][string]$EvidenceDirectory,[Parameter(Mandatory)][string]$ObservationId,[Parameter(Mandatory)][long]$DeadlineEpochMs)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=[IO.Path]::GetFullPath($EvidenceDirectory)
$allowed=(Join-Path $root '.local-tools')+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)){throw 'Fresh local port observation evidence directory required'}
New-Item -ItemType Directory -Path $output|Out-Null
try{
 . "$PSScriptRoot/PowerShellRuntime.ps1"
 $runtime=Assert-VerificationPowerShellRuntime -EvidencePath "$output/powershell.json"
 $remaining=$DeadlineEpochMs-[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 if($remaining -le 0 -or $remaining -gt 60000){throw 'Finite shared port observation preparation budget required'}
 $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($runtime|ConvertTo-Json -Compress)))
 & (Join-Path $root '.local-tools/node-v24.22.0-win-x64/node.exe') "$PSScriptRoot/ReleasePortObservation.cjs" $output $ObservationId ([string]$DeadlineEpochMs) $encoded
 if($LASTEXITCODE -ne 0){throw 'Current port observation failed; inspect retained native evidence'}
}catch{
 [ordered]@{status='failed';observationId=$ObservationId;error=$_.ToString();at=[DateTimeOffset]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content "$output/wrapper-failure.json" -Encoding utf8
 throw
}
