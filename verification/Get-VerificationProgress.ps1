param([Parameter(Mandatory)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$folder=[IO.Path]::GetFullPath($EvidenceDirectory)
if(-not(Test-Path -LiteralPath $folder -PathType Container)){
 $preflightResult=Join-Path ($folder+'-preflight') 'result.json'
 if(Test-Path -LiteralPath $preflightResult){$value=Get-Content -Encoding utf8 -LiteralPath $preflightResult -Raw|ConvertFrom-Json;[pscustomobject]@{phase='preflight';status=$value.status;error=$value.error;evidence=($folder+'-preflight')};return}
 [pscustomobject]@{phase='preflight';status='waiting';evidence=$folder};return
}
# Bounded reads of current result and registered group outputs; never scan raw logs recursively.
$final=Join-Path $folder 'result.json'
if(Test-Path -LiteralPath $final){$result=Get-Content -Encoding utf8 -LiteralPath $final -Raw|ConvertFrom-Json;[pscustomobject]@{phase='root';status=$result.status;error=$result.error;finished=$result.finished;evidence=$folder};return}
$groups=Join-Path $folder 'db/groups'
$schedulerProgress=Join-Path $folder 'db/scheduler-progress.json'
if(Test-Path -LiteralPath $schedulerProgress){
 try{$progress=Get-Content -Encoding utf8 -LiteralPath $schedulerProgress -Raw|ConvertFrom-Json}catch{[pscustomobject]@{phase='database-scheduler';status='updating';evidence=$folder};return}
 if($progress.completed -lt $progress.total){[pscustomobject]@{phase='database-scheduler';status=$progress.status;running=$progress.running;completed=$progress.completed;total=$progress.total;maximumActive=$progress.maximumActive;latestEvent=$progress.event;evidence=$folder};return}
}
$completed=@();$active=$null;$failure=$null
if(Test-Path -LiteralPath $groups){foreach($group in Get-ChildItem -LiteralPath $groups -Directory){
 $file=Join-Path $group.FullName 'result.json'
 if(Test-Path -LiteralPath $file){$value=Get-Content -Encoding utf8 -LiteralPath $file -Raw|ConvertFrom-Json;$completed+=@{id=$group.Name;status=$value.status};if($value.status -ne 'passed'){$failure=@{id=$group.Name;error=$value.error}}}
 else{$active=$group.Name}
}}
$events=Join-Path $folder 'container-reproduction/browser-progress.ndjson'
$event=$null
if(Test-Path -LiteralPath $events){$last=Get-Content -Encoding utf8 -LiteralPath $events -Tail 1;if($last){$event=$last|ConvertFrom-Json}}
[pscustomobject]@{phase=if($event){'container-reproduction'}elseif($active){$active}elseif($completed.Count){'root-followup'}else{'common-build-or-preservation'};status=if($failure){'failure-observed'}else{'running'};completed=$completed;failure=$failure;latestEvent=$event;evidence=$folder}
