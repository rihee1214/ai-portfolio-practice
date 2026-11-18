const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const {validateConcurrency,validateExecutionRegistry,readyGroups,privateEnvironment,runTasks}=require('./DbGroupExecution.cjs');
const def=(id,options={})=>({id,classes:['Fake'],tests:[{new:'Fake#case',invocations:1}],expectedDatabaseCount:1,prerequisites:[],execution:{exclusive:false,resources:[],reason:'Private fixture'},...options});
const registry=groups=>({groups,executionResources:{schemaVersion:1,maxConcurrency:2,resources:['shared']}});
test('group scheduler rejects invalid concurrency and missing unknown duplicate execution classification before launch',()=>{
 for(const value of [0,3,1.5,'1.5','2x',null,undefined])assert.throws(()=>validateConcurrency(value));
 assert.equal(validateConcurrency(1),1);assert.equal(validateConcurrency('2'),2);
 const good=registry([def('a')]);assert.doesNotThrow(()=>validateExecutionRegistry(good,['a']));
 for(const execution of [undefined,{exclusive:false,resources:[],reason:''},{exclusive:false,resources:['unknown'],reason:'x'},{exclusive:false,resources:['shared','shared'],reason:'x'},{exclusive:'false',resources:[],reason:'x'}])assert.throws(()=>validateExecutionRegistry(registry([def('a',{execution})]),['a']));
 assert.throws(()=>validateExecutionRegistry(registry([def('a',{prerequisites:['missing']})]),['a']));
});
test('group scheduler admits ready independent work without crossing dependency exclusive or named resource claims',()=>{
 const a=def('a'),b=def('b',{prerequisites:['a']}),c=def('c'),exclusive=def('x',{execution:{exclusive:true,resources:[],reason:'Faults'}}),locked=def('l',{execution:{exclusive:false,resources:['shared'],reason:'Shared port'}}),locked2=def('m',{execution:{exclusive:false,resources:['shared'],reason:'Shared port'}});
 const groups=[a,b,c,exclusive,locked,locked2],states={a:'running',b:'pending',c:'pending',x:'pending',l:'pending',m:'pending'};
 assert.deepEqual(readyGroups(groups,states,[a],2).map(x=>x.id),['c']);
 assert.deepEqual(readyGroups(groups,{...states,a:'passed',c:'passed'},[],2).map(x=>x.id),['b']);
 assert.deepEqual(readyGroups([locked,locked2],{l:'running',m:'pending'},[locked],2),[]);
 assert.deepEqual(readyGroups([exclusive,c],{x:'running',c:'pending'},[exclusive],2),[]);
 assert.deepEqual(readyGroups([b,c],{a:'failed',b:'pending',c:'pending'},[],2).map(x=>x.id),['c']);
});
test('group scheduler constructs private public sanitized environment without mutating parent values',()=>{
 const folder=path.resolve(__dirname,'../.local-tools/scheduler-env-'+crypto.randomUUID()),parent={FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:1234',GCLOUD_PROJECT:'demo-same',TOSS_SECRET:'not-written-to-request',KEEP:'retained'};
 const normal=privateEnvironment(parent,def('ordinary'),folder,'d2_it_'+'a'.repeat(32)),publicEnv=privateEnvironment(parent,def('public-demo-runtime'),folder+'-public','d2_it_'+'b'.repeat(32));
 assert.equal(normal.FIREBASE_AUTH_EMULATOR_HOST,parent.FIREBASE_AUTH_EMULATOR_HOST);assert.equal(publicEnv.FIREBASE_AUTH_EMULATOR_HOST,undefined);assert.equal(publicEnv.GCLOUD_PROJECT,undefined);assert.equal(publicEnv.TOSS_SECRET,undefined);assert.equal(parent.GCLOUD_PROJECT,'demo-same');assert.notEqual(normal.A3_EVIDENCE,publicEnv.A3_EVIDENCE);assert.notEqual(normal.D2_DB_NAME,publicEnv.D2_DB_NAME);assert.notEqual(normal.TEMP,publicEnv.TEMP);
});
function actualFixture(groups){
 const root=path.resolve(__dirname,'..'),output=path.join(root,'.local-tools/scheduler-actual-'+crypto.randomUUID());fs.mkdirSync(output);const script=path.join(output,'dummy-worker.ps1'),leaf=path.join(output,'leaf.cjs');fs.writeFileSync(leaf,'setInterval(()=>{},1000)');
 fs.writeFileSync(script,`param([string]$Request)
$ErrorActionPreference='Stop'
$r=Get-Content -Encoding utf8 -LiteralPath $Request -Raw|ConvertFrom-Json
. "$($r.root)/verification/PowerShellRuntime.ps1"
$null=Assert-VerificationPowerShellRuntime
$id=$r.definition.id;$folder=$r.output;$started=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();$port=$listener.LocalEndpoint.Port
@{id=$id;pid=$PID;database=$env:D2_DB_NAME;media="$folder/media";evidence=$env:A3_EVIDENCE;temp=$env:TEMP;build=$env:D2_GROUP_BUILD;port=$port;project=$env:GCLOUD_PROJECT;hasToss=[bool]$env:TOSS_SECRET;started=$started}|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/observation.json"
if($id -eq 'long'){$info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=Join-Path $r.root '.local-tools/node-v24.22.0-win-x64/node.exe';$info.ArgumentList.Add($env:PERF_LEAF_FILE);$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$child=[Diagnostics.Process]::Start($info);[IO.File]::WriteAllText("$folder/leaf.pid",[string]$child.Id);Write-Output 'partial UTF8 한글';while($true){Start-Sleep -Milliseconds 100}}
if($env:PERF_BARRIER){[IO.File]::WriteAllText((Join-Path $env:PERF_BARRIER ($id+'.ready')),'ready');$end=[DateTimeOffset]::UtcNow.AddSeconds(5);while(@(Get-ChildItem -LiteralPath $env:PERF_BARRIER -Filter '*.ready').Count -lt 2){if([DateTimeOffset]::UtcNow -gt $end){throw 'Second worker did not become ready'};Start-Sleep -Milliseconds 20}}
Start-Sleep -Milliseconds 150
$failed=$id -eq 'fail';$badProof=$id -eq 'bad-proof';@(@{classname='Fake';name='case()';status=$(if($failed -or $badProof){'failed'}else{'passed'})})|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/java-cases.json"
@{id=$id;status=$(if($failed){'failed'}else{'passed'});evidence=$folder;tests=1;database=$env:D2_DB_NAME;requestSha256=(Get-FileHash -LiteralPath $Request).Hash.ToLowerInvariant();started=$started;finished=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()}|ConvertTo-Json|Set-Content -Encoding utf8 "$folder/result.json"
$listener.Stop();if($failed){exit 7}
`,'utf8');
 return {root,output,script,leaf,request:{registry:registry(groups),selected:groups.map(g=>g.id),priority:groups.map(g=>g.id),root,output,build:path.join(output,'build'),offline:true,endpoint:{port:15433},concurrency:2}};
}
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
test('group scheduler runs two actual owned PowerShell workers with separate environment temp evidence database and listener identities',async()=>{
 const f=actualFixture([def('ordinary'),def('public-demo-runtime')]),barrier=path.join(f.output,'barrier');fs.mkdirSync(barrier);const parentProject=process.env.GCLOUD_PROJECT;
 const result=await runTasks(f.request,{workerScript:f.script,extraEnvironment:{PERF_BARRIER:barrier,FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:1234',GCLOUD_PROJECT:'demo-private',TOSS_SECRET:'PRIVATE_SECRET'}});
 assert.equal(result.status,'passed');assert.equal(result.maximumActive,2);const a=read(path.join(f.output,'groups/ordinary/observation.json')),b=read(path.join(f.output,'groups/public-demo-runtime/observation.json'));
 for(const key of ['pid','database','media','temp','build','port','evidence'])assert.notEqual(a[key],b[key],key+' must be private');
 assert.equal(a.project,'demo-private');assert.equal(a.hasToss,true);assert.equal(b.project,null);assert.equal(b.hasToss,false);assert.equal(process.env.GCLOUD_PROJECT,parentProject);
 const ar=read(path.join(f.output,'groups/ordinary/result.json')),br=read(path.join(f.output,'groups/public-demo-runtime/result.json'));assert.ok(ar.started<br.finished&&br.started<ar.finished,'Actual child execution intervals overlap');
 for(const id of ['ordinary','public-demo-runtime'])assert.ok(!fs.readFileSync(path.join(f.output,'groups',id,'worker-request.json'),'utf8').includes('PRIVATE_SECRET'),'Secrets never enter worker request');
});
test('group scheduler failed producer blocks dependent and retains independently validated worker results',async()=>{
 const f=actualFixture([def('fail'),def('dependent',{prerequisites:['fail']}),def('bad-proof'),def('dependent-proof',{prerequisites:['bad-proof']}),def('independent')]);
 const result=await runTasks(f.request,{workerScript:f.script});assert.equal(result.status,'failed');assert.equal(result.states.fail,'failed');assert.equal(result.states.dependent,'blocked');assert.equal(result.states['bad-proof'],'failed');assert.equal(result.states['dependent-proof'],'blocked');assert.equal(result.states.independent,'passed');assert.ok(!result.events.some(e=>e.event==='start'&&['dependent','dependent-proof'].includes(e.id)));assert.ok(fs.existsSync(path.join(f.output,'groups/fail/worker-stderr.txt')));assert.ok(fs.existsSync(path.join(f.output,'groups/dependent/result.json')));
});
const alive=pid=>{try{process.kill(pid,0);return true}catch{return false}};
async function waitFor(check){const until=Date.now()+10000;while(!check()){if(Date.now()>until)throw Error('Finite guard preparation/cleanup wait expired');await new Promise(resolve=>setTimeout(resolve,25))}}
test('group scheduler cancellation stops only owned PowerShell descendants retains partial UTF8 and leaves unrelated sentinel alive',async()=>{
 const f=actualFixture([def('long'),def('not-started',{prerequisites:['long']})]),controller=new AbortController(),sentinel=cp.spawn(process.execPath,[f.leaf],{windowsHide:true,stdio:'ignore'});let leafPid;
 try{
  const pending=runTasks(f.request,{workerScript:f.script,signal:controller.signal,extraEnvironment:{PERF_LEAF_FILE:f.leaf}});
  await waitFor(()=>fs.existsSync(path.join(f.output,'groups/long/leaf.pid')));leafPid=Number(fs.readFileSync(path.join(f.output,'groups/long/leaf.pid'),'utf8'));controller.abort();const result=await pending;
  await waitFor(()=>!alive(leafPid));assert.equal(result.status,'failed');assert.equal(result.states.long,'cancelled');assert.equal(result.states['not-started'],'cancelled');assert.ok(alive(sentinel.pid));assert.ok(result.events.some(e=>e.event==='terminated-owned-tree'));assert.match(fs.readFileSync(path.join(f.output,'groups/long/worker-stdout.txt'),'utf8'),/partial UTF8 한글/);assert.ok(!result.events.some(e=>e.event==='start'&&e.id==='not-started'));
 }finally{sentinel.kill();if(leafPid&&alive(leafPid))cp.spawnSync('taskkill',['/PID',String(leafPid),'/T','/F'],{windowsHide:true,timeout:5000})}
});
test('group scheduler infrastructure failure records every mandatory unexecuted group and preserves existing evidence',async()=>{
 const f=actualFixture([def('first'),def('second')]);fs.mkdirSync(path.join(f.output,'groups/first'),{recursive:true});const sentinel=path.join(f.output,'groups/first/sentinel.txt');fs.writeFileSync(sentinel,'preserve');
 await assert.rejects(()=>runTasks(f.request,{workerScript:f.script}),/exist/i);const result=read(path.join(f.output,'scheduler-result.json'));assert.equal(result.status,'failed');assert.deepEqual(result.states,{first:'cancelled',second:'cancelled'});assert.equal(result.groups.length,2);assert.equal(result.ownedPids.length,0);assert.ok(result.groups.every(g=>g&&g.status==='cancelled'));assert.equal(fs.readFileSync(sentinel,'utf8'),'preserve');
});
