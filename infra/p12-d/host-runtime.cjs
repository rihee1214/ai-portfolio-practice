'use strict';
// Owned loopback JAR adapter for the accepted local-full/emulator validation mode.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawn}=require('node:child_process');
const http=require('node:http');
const {runOwned,terminateTree}=require('./process-runner.cjs');
const payloads=require('./payload.cjs');
function assert(x,message){if(!x)throw new Error(message);}
function recordPath(op,role){return path.join(op.root,'processes',role+'.json');}
async function command(file,args,input,timeout=10000){
  try{const result=await runOwned(file,args,{input,timeout,maxBuffer:1024*1024});if(result.code!==0){const error=new Error('Owned infrastructure subprocess failed');error.result=result;throw error;}return result.out.trim();}
  catch(error){error.detail=error.result?.err || '';throw error;}
}
async function identity(pid,powershellPath,timeout=10000){
  assert(Number.isInteger(pid)&&pid>0,'Invalid owned PID');
  const script=`$p=Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}'; if($p){@{pid=[int]$p.ProcessId;commandLine=$p.CommandLine;created=$p.CreationDate.ToUniversalTime().ToString('o')}|ConvertTo-Json -Compress}`;
  const text=await command(powershellPath,['-NoProfile','-NonInteractive','-Command',script],undefined,timeout);return text?JSON.parse(text):null;
}
function windowsArguments(line){
  const args=[];let i=0;
  while(i<line.length){while(/\s/.test(line[i] || '') && i<line.length)i++;if(i===line.length)break;
    let value='',quoted=false;
    while(i<line.length && (quoted || !/\s/.test(line[i]))){
      if(line[i]==='\\'){
        let count=0;while(line[i]==='\\'){count++;i++;}
        if(line[i]==='"'){value+='\\'.repeat(Math.floor(count/2));if(count%2){value+='"';i++;}else{quoted=!quoted;i++;}}
        else value+='\\'.repeat(count);
      }else if(line[i]==='"'){quoted=!quoted;i++;}else value+=line[i++];
    }
    assert(!quoted,'Unbalanced owned command line');args.push(value);
  }
  return args;
}
const canonicalFile=file=>path.resolve(file).replaceAll('/','\\').toLowerCase();
function verifySavedRecord(op,role,saved){
  assert(saved.runId===op.r.runId && saved.role===role,'Owned process run/role mismatch');
  assert(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(saved.releaseId || ''),'Owned release identity missing');
  const expected=path.join(op.root,'releases',saved.releaseId,'marketplace.jar');
  assert(canonicalFile(saved.jar)===canonicalFile(expected),'Owned JAR path mismatch');
  assert(crypto.createHash('sha256').update(fs.readFileSync(expected)).digest('hex')===saved.jarSha256,'Owned JAR bytes changed');
  assert(typeof saved.created==='string' && /^\d{4}-\d{2}-\d{2}T/.test(saved.created) && saved.status!=='identity-pending','Owned process identity is not verified');
}
function verifyRecord(op,role,saved,actual){
  verifySavedRecord(op,role,saved);
  if(!actual)return;
  assert(actual.created===saved.created,'Owned PID creation identity changed');
  const args=windowsArguments(actual.commandLine || ''),index=args.indexOf('-jar');
  assert(index===1 && args.lastIndexOf('-jar')===index && args[index+1] && canonicalFile(args[index+1])===canonicalFile(saved.jar),'Owned PID exact JAR argument changed');
  assert(canonicalFile(args[0])===canonicalFile(op.m.runtime.javaPath),'Owned JVM executable changed');
  if(saved.arguments)assert(JSON.stringify(args.slice(1))===JSON.stringify(saved.arguments),'Owned JVM invocation changed');
}
function terminationReceipt(op,role,saved){
  verifySavedRecord(op,role,saved);assert(saved.status==='stopped' && saved.termination,'Verified terminal incarnation receipt required');
  const folder=path.join(op.root,'processes','termination'),file=saved.termination.path,relative=path.relative(folder,path.resolve(file || ''));
  assert(path.isAbsolute(file || '') && relative && !relative.startsWith('..') && !path.isAbsolute(relative),'Terminal receipt outside owned process directory');
  for(let current=file;;current=path.dirname(current)){if(fs.existsSync(current))assert(!fs.lstatSync(current).isSymbolicLink(),'Linked terminal receipt rejected');if(current===path.dirname(current))break;}
  const bytes=fs.readFileSync(file);assert(crypto.createHash('sha256').update(bytes).digest('hex')===saved.termination.sha256,'Terminal receipt hash mismatch');
  const receipt=JSON.parse(bytes);
  for(const key of ['runId','role','pid','created','jar','jarSha256'])assert(receipt[key]===saved[key],'Terminal receipt incarnation mismatch');
  assert(JSON.stringify(receipt.arguments)===JSON.stringify(saved.arguments),'Terminal receipt invocation mismatch');
  assert(receipt.observedAbsent===true && receipt.afterIdentity===null && typeof receipt.observedAt==='string' && typeof receipt.commandId==='string','Terminal absence observation missing');
  return receipt;
}
function roleState(op,role,saved,actual){
  if(saved.status==='stopped'){
    const receipt=terminationReceipt(op,role,saved);
    // A successfully stopped incarnation cannot become live again. The numeric
    // PID may now name an unrelated process; it is never selected for a signal.
    return {...saved,running:false,terminationVerified:true,stoppedByCommandId:receipt.commandId,...(actual?{observedPidReplacement:{pid:actual.pid,created:actual.created}}:{})};
  }
  verifyRecord(op,role,saved,actual);return {...saved,running:!!actual};
}
function persistStopped(op,role,saved,beforeIdentity){
  const current=JSON.parse(fs.readFileSync(recordPath(op,role),'utf8'));
  for(const key of ['runId','role','pid','created','jar','jarSha256'])assert(current[key]===saved[key],'Owned role changed during termination');
  assert(current.status!=='stopped','Owned terminal record already published');
  const folder=path.join(op.root,'processes','termination');fs.mkdirSync(folder,{recursive:true});
  const receipt={version:1,commandId:op.r.commandId,runId:saved.runId,role,pid:saved.pid,created:saved.created,jar:saved.jar,jarSha256:saved.jarSha256,arguments:saved.arguments,observedAbsent:true,afterIdentity:null,observedAt:new Date().toISOString(),beforeIdentity,adapter:{path:__filename,sha256:crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex')}};
  const file=path.join(folder,crypto.randomUUID()+'.json'),bytes=Buffer.from(JSON.stringify(receipt,null,2));fs.writeFileSync(file,bytes,{flag:'wx'});
  const stopped={...saved,status:'stopped',termination:{path:file,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),observedAt:receipt.observedAt}};
  const temporary=recordPath(op,role)+'.'+crypto.randomUUID()+'.tmp';fs.writeFileSync(temporary,JSON.stringify(stopped,null,2),{flag:'wx'});fs.renameSync(temporary,recordPath(op,role));
  return receipt;
}
async function inspect(op){const roles={};for(const role of ['api','worker']){
  if(!fs.existsSync(recordPath(op,role)))continue;
  const saved=JSON.parse(fs.readFileSync(recordPath(op,role),'utf8'));
  const actual=saved.status==='stopped'?null:await identity(saved.pid,op.m.runtime.powershellPath);
  roles[role]=roleState(op,role,saved,actual);
}return roles;}
function roleProfiles(runtime,role){
  assert(['api','worker'].includes(role),'Unknown owned host role');
  assert(runtime.localMailbox===undefined || typeof runtime.localMailbox==='boolean','Local mailbox flag must be an explicit boolean');
  if(runtime.localMailbox===true)assert(runtime.mode==='local-full' && runtime.execution==='host-jar','Local mailbox is restricted to the local host API');
  return role==='worker'?'db,worker':runtime.localMailbox===true?'db,local-mailbox':'db';
}
async function start(op,role){
  const profiles=roleProfiles(op.m.runtime,role);
  const directory=path.join(op.root,'processes');fs.mkdirSync(directory,{recursive:true});
  const previous=(await inspect(op))[role];assert(!previous?.running,'Owned role already running');
  const jar=path.join(op.releaseDir(),'marketplace.jar');
  const env={...op.env};for(const key of Object.keys(env))if(/^(TOSS_|FIREBASE_|GCLOUD_|SPRING_|MARKETPLACE_|DB_|MEDIA_)/.test(key))delete env[key];
  Object.assign(env,{DB_URL:`jdbc:postgresql://127.0.0.1:${op.t.postgresPort}/${op.t.database}`,DB_USERNAME:op.env.PACKAGE_DB_USER,DB_PASSWORD:op.env.PACKAGE_DB_PASSWORD,MARKETPLACE_RUNTIME_MODE:'local-full',...op.m.runtime.applicationEnvironment});
  if(role==='api')env.MEDIA_ROOT=op.t.mediaPath;
  const log=path.join(directory,`${op.r.commandId}-${role}.log`),fd=fs.openSync(log,'wx');
  const args=['-jar',jar,'--spring.profiles.active='+profiles,'--server.address=127.0.0.1','--server.port='+op.t.apiPort,'--server.forward-headers-strategy=framework'];
  let child;
  try{child=spawn(op.m.runtime.javaPath,args,{cwd:op.root,env,windowsHide:true,detached:true,stdio:['ignore',fd,fd]});await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});}
  finally{fs.closeSync(fd);}
  const pid=child.pid;child.unref();assert(pid,'Owned JAR did not start');
  const pending={version:2,pid,runId:op.r.runId,releaseId:op.m.releaseId,jar,jarSha256:crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),profiles,arguments:args,log,role,status:'identity-pending'};
  fs.writeFileSync(path.join(directory,`${op.r.commandId}-${role}-spawn.json`),JSON.stringify(pending,null,2));
  let data;
  try{const actual=await identity(pid,op.m.runtime.powershellPath);assert(actual,'Owned JAR identity missing');data={...pending,...actual,status:'verified'};verifyRecord(op,role,data,actual);}
  catch(error){try{await terminateTree(child);}catch(cleanup){error.cleanupFailure=cleanup.message;}throw error;}
  fs.writeFileSync(recordPath(op,role),JSON.stringify(data,null,2));op.event({phase:'start-host-'+role,state:'progress',observation:'owned-role-spawned',pid,releaseId:op.m.releaseId});
  if(role==='worker'){
    const until=Date.now()+30000;let ready=false;
    while(Date.now()<until){assert(await identity(pid,op.m.runtime.powershellPath),'Owned worker exited');if(/Started MarketplaceApplication/.test(fs.readFileSync(log,'utf8'))){ready=true;break;}await new Promise(r=>setTimeout(r,100));}
    assert(ready,'Owned worker readiness failed');
  }
}
async function stop(op){
  const deadline=Math.min(Date.now()+10000,op.cleanupDeadlineEpochMs || Infinity),attempts=[];
  const remaining=maximum=>{const value=Math.min(maximum,deadline-Date.now()-3000);assert(value>0,'Shared owned-host cleanup deadline exhausted');return value;};
  op.event({phase:'stop-owned-host-roles',state:'started',deadline:new Date(deadline).toISOString()});
  await Promise.all(['api','worker'].map(async role=>{
    const attempt={role,status:'started'};attempts.push(attempt);
    try{
      if(!fs.existsSync(recordPath(op,role))){attempt.status='absent';return;}
      const saved=JSON.parse(fs.readFileSync(recordPath(op,role),'utf8'));attempt.pid=saved.pid;
      if(saved.status==='stopped'){const receipt=terminationReceipt(op,role,saved);attempt.status='already-stopped';attempt.stoppedByCommandId=receipt.commandId;attempt.termination=saved.termination;return;}
      const actual=await identity(saved.pid,op.m.runtime.powershellPath,remaining(3000));verifyRecord(op,role,saved,actual);
      if(!actual){persistStopped(op,role,saved,null);attempt.status='already-stopped';return;}
      attempt.verified={pid:actual.pid,created:actual.created,jar:saved.jar,jarSha256:saved.jarSha256};
      // Kill only the tree whose PID/creation/executable/exact JAR were just
      // observed. A mismatch fails this role but does not skip the other role.
      await command('taskkill.exe',['/PID',String(saved.pid),'/T','/F'],undefined,remaining(2000));
      const after=await identity(saved.pid,op.m.runtime.powershellPath,remaining(3000));
      assert(!after,'Owned process termination not observed');persistStopped(op,role,saved,actual);attempt.status='stopped';
    }catch(error){attempt.status='failed';attempt.error=error.message;}
  }));
  op.event({phase:'stop-owned-host-roles',state:attempts.some(a=>a.status==='failed')?'failed':'completed',attempts});
  const failed=attempts.filter(a=>a.status==='failed');if(failed.length){const error=new Error('Owned host cleanup failed: '+failed.map(a=>a.role+': '+a.error).join('; '));error.attempts=attempts;throw error;}
  return attempts;
}
async function health(op,remaining=3000){return new Promise(resolve=>{
  const request=http.get({hostname:'127.0.0.1',port:op.t.apiPort,path:'/actuator/health',timeout:remaining},response=>{
    let out='';response.on('data',b=>{out+=b;if(out.length>8192)request.destroy();});response.on('end',()=>resolve({code:response.statusCode===200?0:1,out}));
  });request.on('timeout',()=>request.destroy());request.on('error',()=>resolve({code:1,out:''}));
});}
async function stopOwnedRequest(requestPath){
  const request=JSON.parse(fs.readFileSync(requestPath,'utf8').replace(/^\uFEFF/,''));
  assert(path.isAbsolute(request.workspaceRoot || '') && canonicalFile(request.workspaceRoot)===canonicalFile(process.cwd()),'Cleanup workspace identity mismatch');
  assert(Number.isFinite(request.cleanupDeadlineEpochMs) && request.cleanupDeadlineEpochMs>Date.now(),'Existing shared cleanup deadline required');
  payloads.validatePayload(request.controllerPayload);
  const actual=payloads.fileEvidence(request.controllerPayload,'infra/p12-d/host-runtime.cjs');assert(canonicalFile(actual.path)===canonicalFile(__filename),'Cleanup must execute the retained host adapter');
  const dependencies=['infra/p12-d/host-runtime.cjs','infra/p12-d/process-runner.cjs','infra/p12-d/payload.cjs'].map(relative=>payloads.fileEvidence(request.controllerPayload,relative));
  for(const dependency of dependencies)assert(require.cache[dependency.path],'Cleanup dependency did not load from retained payload');
  const local=path.join(request.workspaceRoot,'.local-tools');
  function ownedPath(value){assert(path.isAbsolute(value || ''),'Absolute owned cleanup path required');const relative=path.relative(local,path.resolve(value));assert(relative && !relative.startsWith('..') && !path.isAbsolute(relative),'Cleanup path outside workspace');for(let current=path.resolve(value);;current=path.dirname(current)){if(fs.existsSync(current))assert(!fs.lstatSync(current).isSymbolicLink(),'Linked cleanup path rejected');if(current===path.dirname(current))break;}}
  ownedPath(request.target.root);ownedPath(request.evidenceDirectory);ownedPath(request.controllerPayload.root);ownedPath(request.manifestPath);
  assert(!fs.existsSync(request.evidenceDirectory),'Existing cleanup evidence rejected');
  const ownership=JSON.parse(fs.readFileSync(path.join(request.target.root,'ownership.json'),'utf8'));
  const stable=value=>JSON.stringify(value,(_,item)=>item&&typeof item==='object'&&!Array.isArray(item)?Object.fromEntries(Object.entries(item).sort(([a],[b])=>a.localeCompare(b))):item);
  assert(ownership.runId===request.runId && stable(ownership.target)===stable(request.target),'Cleanup target ownership mismatch');
  const manifest=JSON.parse(fs.readFileSync(request.manifestPath,'utf8'));assert(manifest.runtime.execution==='host-jar','Host cleanup requires local host-JAR mode');
  fs.mkdirSync(request.evidenceDirectory);const op={root:request.target.root,r:request,m:manifest,cleanupDeadlineEpochMs:request.cleanupDeadlineEpochMs,event:value=>fs.appendFileSync(path.join(request.evidenceDirectory,'phases.jsonl'),JSON.stringify({at:new Date().toISOString(),...value})+'\n')};
  let attempts;
  try{attempts=await stop(op);const result={status:'passed',runId:request.runId,target:request.target,adapter:actual,dependencies,deadlineEpochMs:request.cleanupDeadlineEpochMs,attempts};fs.writeFileSync(path.join(request.evidenceDirectory,'result.json'),JSON.stringify(result,null,2));return result;}
  catch(error){fs.writeFileSync(path.join(request.evidenceDirectory,'result.json'),JSON.stringify({status:'failed',runId:request.runId,adapter:actual,dependencies,deadlineEpochMs:request.cleanupDeadlineEpochMs,error:error.message,attempts:error.attempts || attempts || []},null,2));throw error;}
}
module.exports={start,stop,inspect,health,identity,command,windowsArguments,verifyRecord,roleState,terminationReceipt,stopOwnedRequest,roleProfiles};
if(require.main===module){if(process.argv[2]!=='--stop-owned'){process.stderr.write('Internal --stop-owned request required\n');process.exitCode=1;}else stopOwnedRequest(process.argv[3]).then(()=>process.stdout.write('Owned host roles stopped and observed\n')).catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});}
