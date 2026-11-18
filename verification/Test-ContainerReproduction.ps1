$ErrorActionPreference='Stop'
. "$PSScriptRoot/DbGroups.ps1"
. "$PSScriptRoot/Gate.ps1"
. "$PSScriptRoot/ContainerReproduction.ps1"
function Reject-Container([string]$Name,[scriptblock]$Action) { $rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};if(-not $rejected){throw "False AC16 PASS: $Name"};"PASS reject $Name" }
$registry=Get-DbGroupRegistry
$all=Resolve-DbGroups -Registry $registry
if($all.rootPhases.selected -cnotcontains 'container-reproduction'){throw 'Unselected full omitted mandatory AC16 phase'}
$selected=Resolve-DbGroups -Registry $registry -Selection 'package-runtime'
if($selected.rootPhases.selected -cnotcontains 'container-reproduction' -or $selected.certificationEligible){throw 'Package selection must include AC16 and remain selected'}
$excluded=Resolve-DbGroups -Registry $registry -Selection 'inventory'
if($excluded.rootPhases.selected.Count -ne 0 -or $excluded.rootPhases.excluded -cnotcontains 'container-reproduction'){throw 'Unrelated exact selection must disclose excluded AC16'}
$changed=Resolve-DbGroups -Registry $registry -ChangedContracts 'container-reproduction'
if($changed.selected -cnotcontains 'package-runtime' -or $changed.rootPhases.selected -cnotcontains 'container-reproduction'){throw 'AC16 changed contract omitted fresh producer'}
foreach($mutation in @('unknown producer','duplicate phase','missing mandatory phase','unknown root phase','unknown consumed contract','omitted consumer impact')) {
 $bad=$registry|ConvertTo-Json -Depth 20|ConvertFrom-Json
 switch($mutation){
  'unknown producer' {$bad.rootPhases[0].prerequisites=@('missing')}
  'duplicate phase' {$bad.rootPhases+= $bad.rootPhases[0]}
  'missing mandatory phase' {$bad.rootPhases=@()}
  'unknown root phase' {$bad.rootPhases[0].id='unregistered'}
  'unknown consumed contract' {$bad.rootPhases[0].consumes=@('not.provided')}
  'omitted consumer impact' {$bad.impact.identity.select=@($bad.impact.identity.select|Where-Object {$_ -cne 'package-runtime'})}
 }
 Reject-Container $mutation {Resolve-DbGroups -Registry $bad}
}
$required=Get-Content "$PSScriptRoot/required-browser-container-reproduction.json" -Raw|ConvertFrom-Json
$cases=@($required.tests|ForEach-Object {$project,$name=$_.Split('#',2);[pscustomobject]@{classname=$project;name=$name;status='passed'}})
$group=@{id='container-reproduction';browser=@{requirements='required-browser-container-reproduction.json'}}
Assert-BrowserGroupCoverage $group $cases $required|Out-Null
if($cases.Count -ne 2){throw 'AC16 exact desktop/mobile registration requires two cases'}
Reject-Container 'missing mobile browser' {Assert-BrowserGroupCoverage $group @($cases[0]) $required}
Reject-Container 'duplicate desktop browser' {Assert-BrowserGroupCoverage $group @($cases[0],$cases[0]) $required}
$cases[1].status='skipped';Reject-Container 'skipped mobile browser' {Assert-BrowserGroupCoverage $group $cases $required};$cases[1].status='passed'
$deadline=@{status='passed';problems=@();scopes=2;tests=@($cases|ForEach-Object {$file,$title=$_.name.Split(':',2);@{project=$_.classname;file=$file;title=$title;status='passed';operations=1}})}
Assert-BrowserDeadlineEvidence $deadline $cases
$deadline.tests[1].operations=0;Reject-Container 'mobile without business deadline' {Assert-BrowserDeadlineEvidence $deadline $cases}
$deadline.tests[1].operations=1
$browser=@{errors=@();stats=@{expected=2;skipped=0;unexpected=0;flaky=0};suites=@(@{specs=@($cases|ForEach-Object {$file,$title=$_.name.Split(':',2);@{file=$file;title=$title;tests=@(@{projectName=$_.classname;status='expected';expectedStatus='passed';results=@(@{status='passed'})})}})})}
Assert-ContainerReproductionBrowserEvidence $browser $deadline | Out-Null
foreach($field in @('skipped','unexpected','flaky')) {$browser.stats[$field]=1;Reject-Container "report $field cannot pass" {Assert-ContainerReproductionBrowserEvidence $browser $deadline};$browser.stats[$field]=0}
$browser.stats.expected=1;Reject-Container 'one actual browser instead of two' {Assert-ContainerReproductionBrowserEvidence $browser $deadline};$browser.stats.expected=2
$browser.suites[0].specs[0].tests[0].results+=@{status='passed'};Reject-Container 'successful retry cannot pass' {Assert-ContainerReproductionBrowserEvidence $browser $deadline}
'PASS AC16 phase registration selection exact browser inventory and deadline guards'
