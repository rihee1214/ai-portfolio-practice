'use strict';
// Test-contract scheduler only: two separately owned processes, no method parallelism or total deadline.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process'),assert=require('node:assert/strict');
const runtime=require('./PowerShellRuntime.cjs');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'')),sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function validateConcurrency(value){assert.ok(value===1||value===2||value==='1'||value==='2','Group concurrency must be the integer1 or2');return Number(value)}
function validateExecutionRegistry(registry,selected){
 const resources=registry.executionResources;assert.equal(resources?.schemaVersion,1,'Execution resource schema required');assert.equal(resources.maxConcurrency,2,'Execution cap must remain2');assert.ok(Array.isArray(resources.resources)&&new Set(resources.resources).size===resources.resources.length,'Explicit distinct execution resources required');
 const ids=new Set();for(const group of registry.groups){assert.ok(group.id&&!ids.has(group.id),'Duplicate execution group');ids.add(group.id);const e=group.execution;assert.ok(e&&typeof e.exclusive==='boolean'&&Array.isArray(e.resources)&&typeof e.reason==='string'&&e.reason.trim(),'Explicit execution classification required');assert.equal(new Set(e.resources).size,e.resources.length,'Duplicate execution claim');for(const key of e.resources)assert.ok(resources.resources.includes(key),'Unknown execution resource: '+key)}
 const visiting=new Set(),done=new Set();function visit(id){assert.ok(ids.has(id),'Unknown execution prerequisite');if(done.has(id))return;assert.ok(!visiting.has(id),'Cyclic execution prerequisites');visiting.add(id);const g=registry.groups.find(g=>g.id===id),edges=g.prerequisites||[];assert.equal(new Set(edges).size,edges.length,'Duplicate execution prerequisite');for(const p of edges){assert.notEqual(p,id,'Self execution prerequisite');visit(p)}visiting.delete(id);done.add(id)}for(const id of ids)visit(id);
 assert.ok(Array.isArray(selected)&&selected.length&&new Set(selected).size===selected.length,'Distinct selected execution groups required');for(const id of selected){assert.ok(ids.has(id),'Unknown selected execution group');for(const p of registry.groups.find(g=>g.id===id).prerequisites||[])assert.ok(selected.includes(p),'Missing selected prerequisite')}
}
function readyGroups(groups,states,active,maximum){
 const cap=validateConcurrency(maximum);if(active.length>=cap||active.some(g=>g.execution.exclusive))return [];
 const admitted=[];for(const group of groups){if(states[group.id]!=='pending'||!(group.prerequisites||[]).every(id=>states[id]==='passed'))continue;
  if(group.execution.exclusive){if(!active.length&&!admitted.length)admitted.push(group);break}
  if([...active,...admitted].some(g=>g.execution.resources.some(key=>group.execution.resources.includes(key))))continue;
  admitted.push(group);if(active.length+admitted.length===cap)break;
 }return admitted;
}
function privateEnvironment(parent,group,folder,database){
 const env={...parent};if(group.id==='public-demo-runtime')for(const key of Object.keys(env))if(/^(TOSS_|FIREBASE_|FIRESTORE_|GCLOUD_)/.test(key))delete env[key];
 Object.assign(env,{D2_DB_NAME:database,D2_GROUP_BUILD:path.join(folder,'build'),A3_EVIDENCE:folder,A3_RUN_BROWSER:group.id==='browser-api'||group.browser?'true':'false',P12_B_BROWSER_PROJECT:group.browser?.project||'',TEMP:path.join(folder,'tmp'),TMP:path.join(folder,'tmp')});return env;
}
function assertWorkerProof(group,result,folder,requestPath){
 assert.equal(result.status,'passed','Worker contract failed');assert.equal(result.id,group.id,'Worker identity');assert.equal(path.resolve(result.evidence),path.resolve(folder),'Worker evidence identity');assert.equal(result.requestSha256,sha(requestPath),'Worker request identity');
 const cases=[read(path.join(folder,'java-cases.json'))].flat();assert.ok(cases.length,'Worker executed cases required');
 for(const c of cases){assert.equal(c.status,'passed','Failed or skipped worker case');assert.ok(group.tests.some(t=>t.new===c.classname+'#'+c.name.replace(/\(.*/,'')),'Unexpected worker case')}
 for(const t of group.tests)assert.equal(cases.filter(c=>c.classname+'#'+c.name.replace(/\(.*/,'')===t.new).length,t.invocations,'Missing parameter invocation: '+t.new);
 const request=read(requestPath);if(request.compiledManifestSha256)assert.equal(result.commonBuildBinding?.manifestSha256,request.compiledManifestSha256,'Worker common compiled manifest identity');
 assert.equal(result.tests,cases.length,'Worker executed count mismatch');return result;
}
const WORKER_JOB_PREFIX='Local'+String.fromCharCode(92)+'marketplace-verify-';
const quote=value=>"'"+String(value).replaceAll("'","''")+"'";
const ended=task=>task.closed||task.child.exitCode!==null||task.child.signalCode!==null||!task.child.pid;
async function cleanupWorkerJob(task){
 const started=Date.now(),stem=path.join(task.folder,'job-cleanup-'+crypto.randomUUID());
 const script="$ErrorActionPreference='Stop'; . "+quote(path.join(task.root,'verification/WorkerJob.ps1'))+'; Stop-VerificationWorkerJob -Name '+quote(task.jobName)+' | ConvertTo-Json -Depth 6';
 const result=await new Promise(resolve=>{
  let child,timer,drain,finished=false,stdout=[],stderr=[],bytes=0,reason;
  function finish(code,error){if(finished)return;finished=true;clearTimeout(timer);clearTimeout(drain);child?.stdout?.destroy();child?.stderr?.destroy();child?.unref();resolve({code,error:error||reason,stdout:Buffer.concat(stdout).toString('utf8'),stderr:Buffer.concat(stderr).toString('utf8'),helperPid:child?.pid,helperExited:!child?.pid||child.exitCode!==null||child.signalCode!==null})}
  function stop(message){if(reason)return;reason=message;try{child.kill('SIGKILL')}catch(error){reason+='; owned helper handle termination failed: '+error.message}drain=setTimeout(()=>finish(child.exitCode,reason+'; finite helper observation expired'),1000)}
  try{child=cp.spawn(runtime.executable,runtime.commandArguments(script),{cwd:task.root,env:process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});if(child.pid)task.cleanupHelperPids.push(child.pid);timer=setTimeout(()=>stop('Finite job cleanup helper deadline expired'),7000);child.stdout.on('data',data=>{bytes+=data.length;if(bytes>1024*1024)stop('Job cleanup helper output limit exceeded');else stdout.push(data)});child.stderr.on('data',data=>{if(Buffer.concat(stderr).length<65536)stderr.push(data)});child.once('error',error=>finish(null,error.message));child.once('close',code=>finish(code));}catch(error){finish(null,error.message)}
 });
 fs.writeFileSync(stem+'.txt',result.stdout+result.stderr,{flag:'wx'});let value;
 try{assert.equal(result.code,0);assert.ok(!result.error&&result.helperExited);value=JSON.parse(result.stdout);assert.equal(value.jobName,task.jobName);assert.equal(value.status,'cleaned');assert.equal(value.processIdsAfter.length,0)}catch(error){value={status:'unresolved',jobName:task.jobName,error:result.error||result.stderr||error.message}}
 const proof={...value,helperPid:result.helperPid,helperExited:result.helperExited,elapsedMs:Date.now()-started};fs.writeFileSync(stem+'.json',JSON.stringify(proof,null,2),{flag:'wx'});return proof;
}
async function runExecution(request,{workerScript=path.join(request.root,'backend/verify-db-group.ps1'),signal,extraEnvironment={}}={}){
 assert.ok(request.compiledManifest&&request.compiledManifestSha256,'Same-run compiled manifest required before group execution');
 return runTasks({...request,registry:read(path.join(request.root,'verification/db-groups.json'))},{workerScript,signal,extraEnvironment});
}
async function runTasks(request,{workerScript,signal,extraEnvironment={},cleanupJob=cleanupWorkerJob,killParent=task=>task.child.kill('SIGKILL'),beforeCleanup,jobNameFor=()=> WORKER_JOB_PREFIX+crypto.randomUUID().replaceAll('-',''),cleanupLimitMs=10000,attachmentLimitMs=10000}={}){
 for(const value of [cleanupLimitMs,attachmentLimitMs])assert.ok(Number.isInteger(value)&&value>0&&value<=10000,'Finite infrastructure/cleanup limit must not exceed10000ms');
 const {registry,selected,root,output,build,offline,endpoint}=request,maximum=validateConcurrency(request.concurrency);validateExecutionRegistry(registry,selected);
 const priority=request.priority||selected;assert.equal(priority.length,selected.length);assert.deepEqual(priority.toSorted(),selected.toSorted());
 const groups=priority.map(id=>registry.groups.find(g=>g.id===id)),states=Object.fromEntries(selected.map(id=>[id,'pending'])),active=new Map(),results=new Map(),events=[],ownedPids=[],unresolvedOwnership=[];let fatal=null,maximumActive=0,cancelling;
 const eventFile=path.join(output,'scheduler-events.ndjson'),started=Date.now();assert.ok(!fs.existsSync(eventFile),'Fresh scheduler evidence required');fs.writeFileSync(eventFile,'');
 function event(value){const row={at:new Date().toISOString(),active:active.size,...value};events.push(row);fs.appendFileSync(eventFile,JSON.stringify(row)+String.fromCharCode(10));const progress={at:row.at,event:row.event,status:fatal?'cancelling':'running',concurrency:maximum,maximumActive,running:[...active.values()].map(task=>({id:task.group.id,pid:task.child.pid,stage:task.admitted?'workload':'attachment'})),completed:Object.values(states).filter(s=>!['pending','running'].includes(s)).length,total:groups.length};fs.writeFileSync(path.join(output,'scheduler-progress.tmp'),JSON.stringify(progress));fs.renameSync(path.join(output,'scheduler-progress.tmp'),path.join(output,'scheduler-progress.json'))}
 function attachment(task){const file=path.join(task.folder,'job-ready.json');if(!fs.existsSync(file))return null;const value=read(file);assert.equal(value.jobName,task.jobName,'Worker job name identity');assert.equal(value.pid,task.child.pid,'Worker job process identity');assert.equal(value.requestSha256,sha(task.requestPath),'Worker job request identity');assert.equal(value.killOnJobClose,true);assert.equal(value.breakawayAllowed,false);assert.deepEqual([value.processIds].flat(),[task.child.pid],'No workload may start before job attachment');return value}
 async function observeExit(task,deadline){while(!ended(task)&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,20));return ended(task)}
 async function boundedCleanup(task){
  const start=Date.now(),deadline=start+cleanupLimitMs;let timer;
  const operation=(async()=>{await beforeCleanup?.(task);if(task.attachment){const result=await cleanupJob(task);if(result?.status==='cleaned'){const exited=await observeExit(task,Math.min(deadline,Date.now()+1000));return exited?result:{status:'unresolved',jobName:task.jobName,error:'Worker exit not observed after job termination'}}return {...(result||{}),status:'unresolved',jobName:task.jobName}}
   if(!ended(task)){try{task.parentHandleTerminationRequested=!!killParent(task)}catch(error){return {status:'unresolved',jobName:task.jobName,error:error.message}}await observeExit(task,Math.min(deadline,Date.now()+1000))}return ended(task)?{status:'cleaned',jobName:task.jobName,workloadNotAdmitted:true}:{status:'unresolved',jobName:task.jobName,error:'Unattached owned worker termination not observed'};
  })().catch(error=>({status:'unresolved',jobName:task.jobName,error:error.message}));
  let result;try{result=await Promise.race([operation,new Promise(resolve=>{timer=setTimeout(()=>resolve({status:'unresolved',jobName:task.jobName,error:'Finite cleanup deadline expired'}),cleanupLimitMs)})])}finally{clearTimeout(timer)}
  if(result.status!=='cleaned'&&!ended(task)){try{task.parentHandleTerminationRequested=!!killParent(task)}catch(error){result.parentHandleTerminationError=error.message}}
  return {...result,elapsedMs:Date.now()-start,workerExited:ended(task),workerPid:task.child.pid,cleanupHelperPids:[...task.cleanupHelperPids]};
 }
 function endTask(task,{code=task.child.exitCode,error}={}){
  if(task.ending)return task.ending;
  clearTimeout(task.attachmentTimer);clearInterval(task.attachmentPoll);
  task.ending=(async()=>{
   try{task.attachment=task.attachment||attachment(task)}catch(value){error=error||value}
   let final,cleanup;
   try{final=read(path.join(task.folder,'job-final.json'));assert.equal(final.jobName,task.jobName);assert.equal(final.pid,task.child.pid);assert.equal(final.requestSha256,sha(task.requestPath));assert.equal(final.status,'passed');assert.equal([final.descendantIds].flat().length,0)}catch(value){final=null}
   if(code===0&&!error&&!task.cancelled&&task.admitted&&task.attachment&&final){cleanup={status:'cleaned',jobName:task.jobName,method:'kernel-kill-on-last-owner-close',descendantIdsBeforeExit:[],workerExited:true}}
   else cleanup=await boundedCleanup(task);
   task.cleanup=cleanup;
   if(cleanup.status!=='cleaned'){
    const unresolved={id:task.group.id,jobName:task.jobName,workerPid:task.child.pid,resources:task.group.execution.resources,exclusive:task.group.execution.exclusive,cleanup};unresolvedOwnership.push(unresolved);fatal=fatal||'Owned worker cleanup remains unresolved';event({event:'cleanup-unresolved',id:task.group.id,jobName:task.jobName,error:cleanup.error});
   }else if(code!==0||task.cancelled||error)event({event:'terminated-owned-tree',id:task.group.id,pid:task.child.pid,jobName:task.jobName,method:'kernel-job-membership',cleanup});
   let result;try{result=read(path.join(task.folder,'result.json'));if(code!==0||error||task.cancelled||!task.admitted||!task.attachment||!final||cleanup.status!=='cleaned')throw Error(error?.message||cleanup.error||'Worker process failed/cancelled or ownership was not certified');assertWorkerProof(task.group,result,task.folder,task.requestPath);states[task.group.id]='passed'}catch(value){states[task.group.id]=task.cancelled?'cancelled':'failed';result={...(result||{}),id:task.group.id,status:states[task.group.id],error:value.message,evidence:task.folder,exitCode:code,requestSha256:sha(task.requestPath),jobName:task.jobName,cleanup,finished:new Date().toISOString()};fs.writeFileSync(path.join(task.folder,'scheduler-failure.json'),JSON.stringify(result,null,2))}
   if(states[task.group.id]!=='passed'&&task.group.execution.exclusive)fatal=fatal||'Exclusive worker failed; external resource cleanup is not certified';
   results.set(task.group.id,result);active.delete(task.group.id);if(!ended(task))task.child.unref();event({event:'terminal',id:task.group.id,pid:task.child.pid,status:states[task.group.id],exitCode:code,cleanupStatus:cleanup.status});task.resolveCompletion();
   if(fatal)void cancel(fatal);
  })();return task.ending;
 }
 async function cancel(reason){if(!fatal){fatal=reason;event({event:'cancellation',reason})}for(const task of active.values())task.cancelled=true;if(!cancelling)cancelling=Promise.allSettled([...active.values()].map(task=>endTask(task,{error:new Error(reason)})));return cancelling}
 const onAbort=()=>{void cancel('Parent cancellation requested')};signal?.addEventListener('abort',onAbort,{once:true});
 function start(group){
  if(request.compiledManifest){const proof=require('./CompiledBuild.cjs').verify(root,build,request.compiledManifest,request.compiledManifestSha256,{environment:{...process.env,...extraEnvironment}});event({event:'compiled-verified',id:group.id,manifestSha256:proof.manifestSha256,files:proof.files,elapsedMs:proof.elapsedMs})}
  const folder=path.join(output,'groups',group.id);fs.mkdirSync(folder,{recursive:false});fs.mkdirSync(path.join(folder,'tmp'));const database='d2_it_'+crypto.randomUUID().replaceAll('-',''),jobName=jobNameFor(group);assert.ok(jobName.startsWith(WORKER_JOB_PREFIX)&&/^[0-9a-f]{32}$/.test(jobName.slice(WORKER_JOB_PREFIX.length)),'Fresh worker UUID job name required');
  const workerRequest={root,output:folder,build,definition:group,offline,endpoint,jobName};if(request.compiledManifest){workerRequest.compiledManifest=request.compiledManifest;workerRequest.compiledManifestSha256=request.compiledManifestSha256}
  const requestPath=path.join(folder,'worker-request.json');fs.writeFileSync(requestPath,JSON.stringify(workerRequest,null,2),{flag:'wx'});
  const environment=privateEnvironment({...process.env,...extraEnvironment},group,folder,database);environment.D2_DB_URL='jdbc:postgresql://127.0.0.1:'+endpoint.port+'/'+database;
  const stdout=fs.openSync(path.join(folder,'worker-stdout.txt'),'wx'),stderr=fs.openSync(path.join(folder,'worker-stderr.txt'),'wx');let child;try{child=cp.spawn(runtime.executable,runtime.checkedArguments(['-File',path.join(root,'verification/Invoke-DbGroupWorker.ps1'),'-Request',requestPath,'-WorkerScript',workerScript]),{cwd:root,env:environment,windowsHide:true,stdio:['ignore',stdout,stderr]})}finally{fs.closeSync(stdout);fs.closeSync(stderr)}
  const task={root,group,child,folder,requestPath,jobName,cancelled:false,admitted:false,attachment:null,closed:false,exitCode:null,cleanupHelperPids:[]};task.completion=new Promise(resolve=>{task.resolveCompletion=resolve});states[group.id]='running';active.set(group.id,task);maximumActive=Math.max(maximumActive,active.size);if(child.pid)ownedPids.push(child.pid);event({event:'start',id:group.id,pid:child.pid,database,jobName,exclusive:group.execution.exclusive,resources:group.execution.resources});
  task.attachmentPoll=setInterval(()=>{try{const proof=attachment(task);if(proof&&!task.attachment){task.attachment=proof;clearTimeout(task.attachmentTimer);clearInterval(task.attachmentPoll);event({event:'attached',id:group.id,pid:child.pid,jobName,attachmentSeconds:proof.attachmentSeconds});if(!fatal&&!task.cancelled&&!task.ending){fs.writeFileSync(path.join(folder,'job-admit.json'),JSON.stringify({requestSha256:proof.requestSha256}),{flag:'wx'});task.admitted=true;event({event:'admitted',id:group.id,pid:child.pid,jobName})}}}catch(error){void cancel(error.message)}},25);
  task.attachmentTimer=setTimeout(()=>{void cancel('Finite worker job attachment deadline expired')},attachmentLimitMs);
  child.once('error',error=>{task.closed=true;void endTask(task,{code:null,error})});child.once('close',code=>{task.closed=true;task.exitCode=code;void endTask(task,{code})});
 }
 try{
  fs.mkdirSync(path.join(output,'groups'),{recursive:true});if(signal?.aborted)await cancel('Parent cancellation requested');
  while(Object.values(states).some(s=>s==='pending'||s==='running')){
   for(const group of groups)if(states[group.id]==='pending'&&(fatal||(group.prerequisites||[]).some(id=>['failed','blocked','cancelled'].includes(states[id])))){states[group.id]=fatal?'cancelled':'blocked';const result={id:group.id,status:states[group.id],error:fatal||'Required producer did not pass validation',evidence:path.join(output,'groups',group.id),started:null,finished:new Date().toISOString()};fs.mkdirSync(result.evidence,{recursive:true});fs.writeFileSync(path.join(result.evidence,'result.json'),JSON.stringify(result,null,2));results.set(group.id,result);event({event:'not-executed',id:group.id,status:result.status,reason:result.error})}
   if(!fatal)for(const group of readyGroups(groups,states,[...active.values()].map(task=>task.group),maximum))start(group);
   if(active.size)await Promise.race([...active.values()].map(task=>task.completion));else if(Object.values(states).includes('pending'))throw Error('No ready execution group');
  }
 }catch(error){await cancel(error.message);await Promise.allSettled([...active.values()].map(task=>task.completion));throw error}
 finally{signal?.removeEventListener('abort',onAbort);for(const group of groups)if(['pending','running'].includes(states[group.id])){states[group.id]='cancelled';const folder=path.join(output,'groups',group.id),result={id:group.id,status:'cancelled',error:fatal||'Scheduler did not reach validated terminal completion',evidence:folder,started:null,finished:new Date().toISOString()};fs.mkdirSync(folder,{recursive:true});fs.writeFileSync(path.join(folder,'scheduler-not-executed.json'),JSON.stringify(result,null,2));results.set(group.id,result)}const report={status:groups.every(group=>states[group.id]==='passed')&&!unresolvedOwnership.length?'passed':'failed',concurrency:maximum,maximumActive,admissionStopped:!!fatal,unresolvedOwnership,unreleasedClaims:unresolvedOwnership.map(value=>({id:value.id,jobName:value.jobName,resources:value.resources,exclusive:value.exclusive})),started:new Date(started).toISOString(),finished:new Date().toISOString(),elapsedSeconds:(Date.now()-started)/1000,states,ownedPids,groups:groups.map(group=>results.get(group.id)),events};fs.writeFileSync(path.join(output,'scheduler-result.json'),JSON.stringify(report,null,2))}
 return read(path.join(output,'scheduler-result.json'));
}
module.exports={validateConcurrency,validateExecutionRegistry,readyGroups,privateEnvironment,assertWorkerProof,runExecution,runTasks,cleanupWorkerJob};
if(require.main===module){const controller=new AbortController();process.once('SIGINT',()=>controller.abort());process.once('SIGTERM',()=>controller.abort());runExecution(read(process.argv[2]),{signal:controller.signal}).then(result=>{if(result.status!=='passed')process.exitCode=1}).catch(error=>{console.error(error.message);process.exitCode=1})}
