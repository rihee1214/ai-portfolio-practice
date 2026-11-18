'use strict';
// Implementation regression support only: real GUID jobs/processes, no database.
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const runtime=require('./PowerShellRuntime.cjs');
const {runOwned}=require('../infra/p12-d/process-runner.cjs');
const execution=require('./DbGroupExecution.cjs');
const root=path.resolve(__dirname,'..');
const evidence=path.join(root,'.local-tools','worker-job-lifecycle-'+crypto.randomUUID());fs.mkdirSync(evidence);
const quote=value=>"'"+String(value).replaceAll("'","''")+"'";
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const maybeRead=file=>{try{return read(file)}catch{return null}};
const write=(file,value)=>fs.writeFileSync(file,JSON.stringify(value,null,2),{flag:'wx'});
async function waitFor(check,maximum=10000){const deadline=Date.now()+maximum;while(true){const value=check();if(value)return value;if(Date.now()>=deadline)throw Error('Finite fixture preparation/observation expired');await new Promise(resolve=>setTimeout(resolve,20))}}
async function powershell(script){const result=await runOwned(runtime.executable,runtime.commandArguments(script),{cwd:root,timeout:10000,maxBuffer:1024*1024});assert.equal(result.code,0,result.err);return result.out.trim()}
async function identities(ids){return JSON.parse(await powershell(`$filter=${quote(ids.map(id=>'ProcessId='+Number(id)).join(' OR '))}; @(Get-CimInstance Win32_Process -Filter $filter | ForEach-Object { @{pid=[int]$_.ProcessId;created=$_.CreationDate.ToUniversalTime().ToString('o');commandLine=$_.CommandLine} }) | ConvertTo-Json -AsArray -Compress`))}
async function jobSnapshot(name){return JSON.parse(await powershell('. '+quote(path.join(root,'verification/WorkerJob.ps1'))+'; [MarketplaceVerification.WorkerJob]::Snapshot('+quote(name)+')|ConvertTo-Json -Compress'))}
function group(id,fixtureMode='normal',resources=[]){return {id,fixtureMode,classes:['Fake'],tests:[{new:'Fake#case',invocations:1}],expectedDatabaseCount:1,prerequisites:[],execution:{exclusive:false,resources,reason:'Owned lifecycle regression fixture'}}}
function fixture(label,groups){
 const folder=path.join(evidence,label+'-'+crypto.randomUUID()),output=path.join(folder,'output');fs.mkdirSync(output,{recursive:true});
 const worker=path.join(folder,'worker.ps1'),leaf=path.join(folder,'leaf.cjs'),grand=path.join(folder,'grand.cjs');
 fs.writeFileSync(grand,"require('fs').writeFileSync(process.argv[2],JSON.stringify({pid:process.pid}));setInterval(()=>{},1000)");
 fs.writeFileSync(leaf,"const fs=require('fs'),cp=require('child_process');const c=cp.spawn(process.execPath,[process.argv[2],process.argv[3]],{windowsHide:true,stdio:'ignore'});fs.writeFileSync(process.argv[4],JSON.stringify({child:process.pid,grandchild:c.pid}));setInterval(()=>{},1000)");
 fs.writeFileSync(worker,`param([string]$Request)
$ErrorActionPreference='Stop'
$r=Get-Content -LiteralPath $Request -Encoding utf8 -Raw|ConvertFrom-Json
$folder=$r.output;$id=$r.definition.id
@{id=$id;pid=$PID;jobName=$r.jobName}|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/workload-entered.json"
Write-Output 'owned workload UTF8 한글'
if($r.definition.fixtureMode -eq 'orphan'){
 $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$env:PERF_JOB_NODE;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
 foreach($arg in @($env:PERF_JOB_LEAF,$env:PERF_JOB_GRAND,"$folder/grandchild-ready.json","$folder/nested-pids.json")){$info.ArgumentList.Add($arg)}
 $child=[Diagnostics.Process]::Start($info)
 $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10)
 while(-not(Test-Path -LiteralPath "$folder/allow-exit")){if([DateTimeOffset]::UtcNow -ge $deadline){throw 'Finite orphan fixture gate preparation expired'};Start-Sleep -Milliseconds 20}
}
if($r.definition.fixtureMode -eq 'hold'){while($true){Start-Sleep -Milliseconds 50}}
@(@{classname='Fake';name='case()';status='passed'})|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/java-cases.json"
@{id=$id;status='passed';tests=1;evidence=$folder;requestSha256=(Get-FileHash -LiteralPath $Request).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/result.json"
if($r.definition.fixtureMode -eq 'orphan'){exit 7}
`,'utf8');
 const registry={groups,executionResources:{schemaVersion:1,maxConcurrency:2,resources:['fixture-ownership']}};
 const request={root,output,build:path.join(output,'build'),registry,selected:groups.map(g=>g.id),priority:groups.map(g=>g.id),offline:true,endpoint:{port:15433},concurrency:1};
 write(path.join(folder,'fixture.json'),{label,request,worker,leaf,grand,scope:'No DB; actual checked PowerShell/native job/Node process fixtures only'});
 return {folder,output,worker,leaf,grand,request,env:{PERF_JOB_NODE:process.execPath,PERF_JOB_LEAF:leaf,PERF_JOB_GRAND:grand}};
}
function holder(f,jobName,kind){
 const ready=path.join(f.folder,kind+'-holder-ready.json'),release=path.join(f.folder,kind+'-holder-release');
 const script=`. ${quote(path.join(root,'verification/WorkerJob.ps1'))}; $handle=$null; try { ${kind==='owner'?'[MarketplaceVerification.WorkerJob]::AttachSelf('+quote(jobName)+')':'$handle=[MarketplaceVerification.WorkerJob]::OpenForQuery('+quote(jobName)+'); if($null -eq $handle){throw "Owned job not available for fixture query holder"}'}; @{pid=$PID;jobName=${quote(jobName)}}|ConvertTo-Json|Set-Content -Encoding utf8 ${quote(ready)}; $clock=[Diagnostics.Stopwatch]::StartNew(); while(-not(Test-Path -LiteralPath ${quote(release)})){if($clock.ElapsedMilliseconds -ge 10000){throw 'Finite test holder preparation expired'};Start-Sleep -Milliseconds 20} } finally {if($null -ne $handle){$handle.Dispose()}}`;
 const stdout=fs.openSync(path.join(f.folder,kind+'-holder-stdout.log'),'wx'),stderr=fs.openSync(path.join(f.folder,kind+'-holder-stderr.log'),'wx');
 const child=cp.spawn(runtime.executable,runtime.commandArguments(script),{cwd:root,windowsHide:true,stdio:['ignore',stdout,stderr]});fs.closeSync(stdout);fs.closeSync(stderr);
 const done=new Promise(resolve=>child.once('close',code=>resolve(code)));
 return {child,ready,release,done};
}
async function releaseHolder(value){if(!value)return;if(!fs.existsSync(value.release))fs.writeFileSync(value.release,'release');if(value.child.exitCode===null){await waitFor(()=>value.child.exitCode!==null);assert.equal(await value.done,0,'Fixture holder exit')}else assert.equal(value.child.exitCode,0,'Holder must not be killed by worker cleanup')}
async function recoverTask(task,folder){if(!task)return;const result=await execution.cleanupWorkerJob(task);write(path.join(folder,'fixture-owned-job-recovery-'+crypto.randomUUID()+'.json'),result);assert.equal(result.status,'cleaned','Controlled negative fixture cleanup must be observed')}
async function finiteCleanupResult(pending,maximum=5000){let timer;try{return await Promise.race([pending,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Failed cleanup did not reach finite terminal')),maximum)})])}finally{clearTimeout(timer)}}

test('worker kernel job normal exit reaches validated terminal with empty ownership',async()=>{
 const f=fixture('normal',[group('normal')]);
 const result=await execution.runTasks(f.request,{workerScript:f.worker,extraEnvironment:f.env});
 assert.equal(result.status,'passed');assert.equal(result.states.normal,'passed');
 const final=read(path.join(f.output,'groups/normal/job-final.json'));assert.equal(final.status,'passed');assert.deepEqual(final.descendantIds,[]);
 assert.ok(!(result.unresolvedOwnership||[]).length);assert.ok(!(result.unreleasedClaims||[]).length);
 write(path.join(f.folder,'assertions.json'),{normalValidated:true,final,result:result.status});
});

test('worker kernel job nonzero parent exit cleans held nested descendants and preserves unrelated sentinel',async()=>{
 const failed=group('orphan','orphan'),dependent=group('dependent');dependent.prerequisites=['orphan'];
 const f=fixture('orphan',[failed,dependent]);
 const sentinel=cp.spawn(process.execPath,[f.grand,path.join(f.folder,'sentinel-ready.json')],{windowsHide:true,stdio:'ignore'});
 let queryHolder,captured,before;
 try{
  await waitFor(()=>maybeRead(path.join(f.folder,'sentinel-ready.json')));
  const pending=execution.runTasks(f.request,{workerScript:f.worker,extraEnvironment:f.env,beforeCleanup:async task=>{
   if(task.group.id!=='orphan')return;captured=task;
   const nested=read(path.join(task.folder,'nested-pids.json'));before=await identities([task.child.pid,nested.child,nested.grandchild,sentinel.pid]);
   assert.ok(!before.some(row=>row.pid===task.child.pid),'Worker parent must already be absent');
   for(const pid of [nested.child,nested.grandchild,sentinel.pid])assert.ok(before.some(row=>row.pid===pid),'Held descendant or unrelated sentinel missing before cleanup');
   write(path.join(f.folder,'before-native-cleanup.json'),{nested,observed:before,jobName:task.jobName});
  }});
  const workerFolder=path.join(f.output,'groups/orphan');const ready=await waitFor(()=>maybeRead(path.join(workerFolder,'job-ready.json')));
  await waitFor(()=>maybeRead(path.join(workerFolder,'nested-pids.json'))&&maybeRead(path.join(workerFolder,'grandchild-ready.json')));
  queryHolder=holder(f,ready.jobName,'query');await waitFor(()=>maybeRead(queryHolder.ready));fs.writeFileSync(path.join(workerFolder,'allow-exit'),'exit');
  const result=await pending;assert.equal(result.status,'failed');assert.equal(result.states.orphan,'failed');assert.equal(result.states.dependent,'blocked');assert.ok(captured&&before);
  const nested=read(path.join(workerFolder,'nested-pids.json')),after=await identities([nested.child,nested.grandchild,sentinel.pid]);
  for(const pid of [nested.child,nested.grandchild])assert.ok(!after.some(row=>row.pid===pid&&row.created===before.find(value=>value.pid===pid).created),'Original owned descendant survived');
  assert.ok(after.some(row=>row.pid===sentinel.pid&&row.created===before.find(value=>value.pid===sentinel.pid).created),'Unrelated sentinel incarnation must survive');
  assert.ok(!result.events.some(event=>event.event==='start'&&event.id==='dependent'));assert.match(fs.readFileSync(path.join(workerFolder,'worker-stdout.txt'),'utf8'),/UTF8 한글/);
  write(path.join(f.folder,'after-native-cleanup.json'),{observed:after,schedulerStatus:result.status});
 }finally{await recoverTask(captured,f.folder);await releaseHolder(queryHolder);sentinel.kill()}
});

test('worker kernel job attachment collision prevents native workload admission',async()=>{
 const f=fixture('attach-collision',[group('collision'),group('after')]),name='Local'+String.fromCharCode(92)+'marketplace-verify-'+crypto.randomUUID().replaceAll('-','');
 const owner=holder(f,name,'owner');
 try{
  const ready=await waitFor(()=>maybeRead(owner.ready));const before=await identities([ready.pid]);
  const membershipBefore=await jobSnapshot(name);assert.equal(membershipBefore.Exists,true);assert.ok(membershipBefore.ProcessIds.includes(ready.pid));
  const result=await execution.runTasks(f.request,{workerScript:f.worker,extraEnvironment:f.env,jobNameFor:()=>name});
  assert.equal(result.status,'failed');assert.ok(!fs.existsSync(path.join(f.output,'groups/collision/workload-entered.json')),'Native workload ran before attachment acceptance');
  assert.ok(!result.events.some(event=>event.event==='admitted'&&['collision','after'].includes(event.id)),'Colliding wrappers must never admit native workload');
  assert.ok(!fs.existsSync(path.join(f.output,'groups/after/workload-entered.json')),'Independent colliding wrapper also ran native workload');
  const after=await identities([ready.pid]);assert.ok(after.some(row=>row.pid===ready.pid&&row.created===before[0].created),'Collision owner must not be terminated');
  const membershipAfter=await jobSnapshot(name);assert.equal(membershipAfter.Exists,true);assert.ok(membershipAfter.ProcessIds.includes(ready.pid),'Foreign collision job and owner must survive worker cleanup');
  write(path.join(f.folder,'attachment-assertions.json'),{name,ownerBefore:before,ownerAfter:after,membershipBefore,membershipAfter,resultStatus:result.status});
 }finally{await releaseHolder(owner)}
});

for(const kind of ['rejection','stall'])test(`worker cleanup ${kind} reaches finite failed terminal and keeps claims without new admission`,async()=>{
 const f=fixture('cleanup-'+kind,[group('hold','hold',['fixture-ownership']),group('after','normal',['fixture-ownership'])]),controller=new AbortController();let captured;
 try{
  const pending=execution.runTasks(f.request,{workerScript:f.worker,signal:controller.signal,extraEnvironment:f.env,cleanupLimitMs:300,
   cleanupJob:task=>{captured=task;if(kind==='rejection')return Promise.reject(Error('Injected owned cleanup rejection'));return new Promise(()=>{})},killParent:()=>false});
  await waitFor(()=>maybeRead(path.join(f.output,'groups/hold/workload-entered.json')));const cleanupStarted=Date.now();controller.abort();
  const result=await finiteCleanupResult(pending);const elapsedMs=Date.now()-cleanupStarted;
  assert.equal(result.status,'failed');assert.equal(result.admissionStopped,true);assert.ok(result.unresolvedOwnership.length>0);assert.ok(result.unreleasedClaims.length>0);
  assert.ok(!result.events.some(event=>event.event==='start'&&event.id==='after'));assert.ok(!fs.existsSync(path.join(f.output,'groups/after/workload-entered.json')));
  assert.ok(captured,'Cleanup injection was not exercised');write(path.join(f.folder,'finite-terminal-assertions.json'),{kind,elapsedMs,status:result.status,unresolvedOwnership:result.unresolvedOwnership,unreleasedClaims:result.unreleasedClaims,scope:'Timing starts at cleanup request, not a whole verification cap'});
 }finally{await recoverTask(captured,f.folder)}
});
