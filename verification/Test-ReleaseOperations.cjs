'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {validateRequest,validateManifest,inventory,assertInventory,safePath,stable,Operation,main,snapshotSha256,privateRowInventory}=require('../infra/p12-d/release-operations.cjs');
const {runOwned,alive}=require('../infra/p12-d/process-runner.cjs');
const payloads=require('../infra/p12-d/payload.cjs');
const workspace=path.resolve(__dirname,'..'),local=path.join(workspace,'.local-tools');
const evidence=path.join(local,'p12-d-ops-'+crypto.randomUUID());fs.mkdirSync(evidence);
const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
const runId=crypto.randomUUID(),commandId=crypto.randomUUID();
const root=path.join(evidence,'stack');
const target={root,project:'p12d-'+runId.replaceAll('-',''),container:'p12d-postgres-'+runId.replaceAll('-',''),database:'d2_it_'+runId.replaceAll('-',''),httpsPort:35432,postgresPort:35433,mediaPath:path.join(root,'media')};
const fixture=path.join(evidence,'inputs');fs.mkdirSync(fixture);fs.mkdirSync(path.join(fixture,'dist'));fs.writeFileSync(path.join(fixture,'dist/index.html'),'<!doctype html>');fs.mkdirSync(path.join(fixture,'dist/assets'));fs.writeFileSync(path.join(fixture,'dist/assets/retained.js'),'old asset');
for(const name of ['jar','nginx','proxy','migration','environment'])fs.writeFileSync(path.join(fixture,name),'fixture-'+name);
const secrets=path.join(fixture,'secrets');fs.mkdirSync(secrets);for(const name of ['tls.crt','tls.key','reviewers'])fs.writeFileSync(path.join(secrets,name),'synthetic-'+name);
const file=name=>({path:path.join(fixture,name),sha256:sha(fs.readFileSync(path.join(fixture,name)))});
const manifest={releaseId:'R0',source:{head:'a'.repeat(40),inputsSha256:'b'.repeat(64)},artifacts:{image:'sha256:'+'c'.repeat(64),jar:file('jar'),nginxConfig:file('nginx'),proxyConfig:file('proxy'),migrations:[{version:'132',...file('migration')}],dist:{path:path.join(fixture,'dist'),files:inventory(path.join(fixture,'dist'))}},runtime:{mode:'public-demo',execution:'compose',access:'invited-only',origin:'https://localhost',phoneProvider:'disabled',applicationEnvironment:{},secretsPath:secrets,environmentFile:path.join(fixture,'environment')},compatibility:{schemaVersion:'132',apiReleaseIds:['R0','R1'],uiReleaseIds:['R0','R1']},readiness:{sql:'SELECT 1',expected:'1'}};
const payloadDefinition={sourceRoot:workspace,sourceHead:'a'.repeat(40),sourceInputsSha256:'b'.repeat(64)};
const controllerPayload=payloads.createPayload({...payloadDefinition,outputRoot:path.join(evidence,'controller'),kind:'p12-d-operations',sourceReleaseId:'R1'});
manifest.artifacts.operationalPayload=payloads.createPayload({...payloadDefinition,outputRoot:path.join(evidence,'historical-runtime'),kind:'p12-c-runtime',sourceReleaseId:'R0'});
const manifestPath=path.join(fixture,'manifest.json');fs.writeFileSync(manifestPath,JSON.stringify(manifest));
manifest.runtime.powershellPath=process.env.P12_D_PWSH || 'C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe';
fs.writeFileSync(manifestPath,JSON.stringify(manifest));
const request=()=>({action:'Prepare',commandId,runId,workspaceRoot:workspace,controllerPayload,expectedReleaseId:'NONE',manifestPath,target:{...target},evidenceDirectory:path.join(evidence,'command')});
test('D01 explicit target prevents default server and old resource selection',()=>{
  assert.equal(validateRequest(request()).target.database,target.database);
  for(const patch of [{database:'marketplace'},{database:'d2_test'},{project:'default'},{container:'postgres'},{postgresPort:15433},{httpsPort:443},{root:path.join(workspace,'backend')},{mediaPath:path.join(local,'old-media')}])assert.throws(()=>validateRequest({...request(),target:{...target,...patch}}));
  assert.throws(()=>validateRequest({...request(),expectedReleaseId:undefined}));
  assert.throws(()=>validateRequest({...request(),runId:'not-a-uuid'}));
});
test('D01 path traversal and linked inventory cannot escape owned root',()=>{
  assert.throws(()=>safePath(path.join(workspace,'README.md'),local,true));
  assert.throws(()=>safePath(path.join(local,'..','README.md'),local,true));
  const linked=path.join(fixture,'linked');fs.symlinkSync(path.join(fixture,'dist'),linked,'junction');
  assert.throws(()=>safePath(path.join(linked,'index.html'),fixture,true));
  assert.throws(()=>inventory(fixture));
});
test('D01 immutable bytes and mode/provider environment are verified before Docker',()=>{
  assert.equal(validateManifest(manifest).releaseId,'R0');
  const mutate=fn=>{const m=JSON.parse(JSON.stringify(manifest));fn(m);return m;};
  assert.throws(()=>validateManifest(mutate(m=>m.artifacts.jar.sha256='0'.repeat(64))));
  assert.throws(()=>validateManifest(mutate(m=>m.artifacts.image='app:latest')));
  assert.throws(()=>validateManifest(mutate(m=>m.artifacts.dist.files=[])));
  assert.throws(()=>validateManifest(mutate(m=>m.runtime.applicationEnvironment.FIREBASE_AUTH_EMULATOR_HOST='127.0.0.1:1234')));
  assert.throws(()=>validateManifest(mutate(m=>m.runtime.phoneProvider='local-test')));
  assert.throws(()=>validateManifest(mutate(m=>m.readiness.sql='UPDATE purchase_order SET state=1')));
});
test('D04 complete inventory rejects missing and modified retained lazy assets',()=>{
  const original=inventory(path.join(fixture,'dist'));
  fs.writeFileSync(path.join(fixture,'dist/assets/retained.js'),'changed');assert.throws(()=>assertInventory(path.join(fixture,'dist'),original));
  fs.writeFileSync(path.join(fixture,'dist/assets/retained.js'),'old asset');assertInventory(path.join(fixture,'dist'),original);
  assert.throws(()=>assertInventory(path.join(fixture,'dist'),original.filter(x=>x.path!=='assets/retained.js')));
});
test('D04 open transactions prohibit a checkpoint before dump or copy',async()=>{
  class OwnedOpenTransaction extends Operation {
    async assertOwned(){this.state={activeReleaseId:'R0'};}
    async compose(){}
    async serviceIds(){return [];}
    async sql(){return '1';}
    async snapshot(){throw new Error('Snapshot must not run');}
  }
  const ev=path.join(evidence,'open-transaction');fs.mkdirSync(ev);
  const op=new OwnedOpenTransaction({...request(),action:'Checkpoint',evidenceDirectory:ev},manifest);
  await assert.rejects(op.checkpoint(),/Open database connections/);
  assert.equal(fs.existsSync(path.join(ev,'checkpoint.json')),false);
  assert.equal(fs.existsSync(path.join(ev,'database.dump.partial')),false);
});
test('D05 partial checkpoint and same target are rejected before resource creation',async()=>{
  const cp=path.join(evidence,'incomplete-checkpoint.json');fs.writeFileSync(cp,JSON.stringify({version:1,complete:false}));
  class NoCreation extends Operation {async createTarget(){throw new Error('Must not create resources');}}
  const op=new NoCreation({...request(),action:'Restore',checkpointPath:cp},manifest);
  await assert.rejects(op.restore(),/Incomplete checkpoint/);
  fs.writeFileSync(cp,JSON.stringify({version:1,complete:true,runId,target}));
  await assert.rejects(op.restore(),/separate target/);
});
test('D01 command lock refuses concurrent project mutation and retains holder',async()=>{
  const lockDir=path.join(local,'p12-d-operation-locks');fs.mkdirSync(lockDir,{recursive:true});
  const lock=path.join(lockDir,target.project+'.lock');fs.writeFileSync(lock,'owned-lock',{flag:'wx'});
  const req=path.join(evidence,'locked-request.json');fs.writeFileSync(req,JSON.stringify(request()));
  await assert.rejects(main(req),/operation lock held/);assert.equal(fs.readFileSync(lock,'utf8'),'owned-lock');
  fs.unlinkSync(lock);
});
test('D01 completed command replay verifies input and every retained output byte',async()=>{
  const t={...target,root:path.join(evidence,'replay-stack')};t.mediaPath=path.join(t.root,'media');fs.mkdirSync(t.root);fs.mkdirSync(path.join(t.root,'commands'));
  const r={...request(),target:t};const req=path.join(evidence,'replay-request.json');fs.writeFileSync(req,JSON.stringify(r));
  const result=path.join(evidence,'replay-result.json'),artifact=path.join(evidence,'replay-artifact');fs.writeFileSync(result,JSON.stringify({status:'passed'}));fs.writeFileSync(artifact,'complete-dump');
  fs.writeFileSync(path.join(t.root,'commands',commandId+'.json'),JSON.stringify({fingerprint:sha(stable({...r,evidenceDirectory:undefined})),manifestSha:sha(stable(manifest)),resultPath:result,resultSha256:sha(fs.readFileSync(result)),artifacts:[{path:artifact,sha256:sha(fs.readFileSync(artifact))}]}));
  assert.equal((await main(req)).status,'passed');
  fs.writeFileSync(artifact,'tampered-dump');await assert.rejects(main(req),/output artifact changed/);
  fs.writeFileSync(req,JSON.stringify({...r,expectedReleaseId:'R1'}));await assert.rejects(main(req),/different input/);
});
test('D09 phase timeout kills owned descendants and preserves partial output',async()=>{
  const pids=path.join(evidence,'owned-stall.json');
  const program=`const fs=require('fs'),{spawn}=require('child_process');const c=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:['ignore','inherit','inherit']});fs.writeFileSync(${JSON.stringify(pids)},JSON.stringify({pid:process.pid,descendant:c.pid}));console.log('partial-output-before-timeout');setInterval(()=>{},1000);`;
  const start=Date.now();let failure;
  try{await runOwned(process.execPath,['-e',program],{timeout:500});}catch(error){failure=error;}
  assert.ok(failure);assert.match(failure.result.out,/partial-output-before-timeout/);assert.ok(Date.now()-start<5000);
  const owned=JSON.parse(fs.readFileSync(pids,'utf8'));
  // Windows can retain a killed child's process object in the spawning Node
  // process. Query the OS from a separate process, as the outer watchdog does.
  const identity=require('../infra/p12-d/host-runtime.cjs').identity;
  const parentObserved=await identity(owned.pid,manifest.runtime.powershellPath);
  const descendantObserved=await identity(owned.descendant,manifest.runtime.powershellPath);
  fs.writeFileSync(path.join(evidence,'owned-termination-observation.json'),JSON.stringify({owned,parentObserved,descendantObserved,nodeKillZero:{parent:alive(owned.pid),descendant:alive(owned.descendant)}}));
  assert.equal(parentObserved,null);assert.equal(descendantObserved,null);
});
test('D09 command exit retains stdout stderr and failure without waiting indefinitely',async()=>{
  const start=Date.now();const result=await runOwned(process.execPath,['-e',"process.stdout.write('recorded-output');process.stderr.write('recorded-failure');process.exitCode=7"],{timeout:1000});
  assert.equal(result.code,7);assert.equal(result.out,'recorded-output');assert.equal(result.err,'recorded-failure');assert.ok(Date.now()-start<3000);
});
test('D01 retained operational closure rejects missing modified and undeclared files',async()=>{
  const retained=payloads.createPayload({...payloadDefinition,outputRoot:path.join(evidence,'tamper'),kind:'p12-d-operations',sourceReleaseId:'R1'});
  assert.throws(()=>payloads.validatePayload({...retained,files:retained.files.filter(f=>!f.path.endsWith('Invalidate-RestoredCredentials.sql'))}),/closure incomplete/);
  const sql=path.join(retained.root,'infra/p12-d/Invalidate-RestoredCredentials.sql');fs.chmodSync(sql,0o644);fs.writeFileSync(sql,'-- altered administrative SQL');
  assert.throws(()=>payloads.validatePayload(retained),/hash mismatch/);
  const denied=path.join(evidence,'tampered-controller-request.json'),deniedRequest={...request(),controllerPayload:retained,evidenceDirectory:path.join(evidence,'must-not-execute')};fs.writeFileSync(denied,JSON.stringify(deniedRequest));
  const blocked=await runOwned(process.execPath,[path.join(workspace,'infra/p12-d/release-operations.cjs'),denied],{cwd:workspace,timeout:3000});
  assert.notEqual(blocked.code,0);assert.match(blocked.err,/payload hash mismatch/);assert.equal(fs.existsSync(deniedRequest.evidenceDirectory),false);assert.equal(fs.existsSync(deniedRequest.target.root),false);
  const extra=payloads.createPayload({...payloadDefinition,outputRoot:path.join(evidence,'extra-file'),kind:'p12-d-operations',sourceReleaseId:'R1'});fs.writeFileSync(path.join(extra.root,'unlisted.cjs'),'undeclared');
  assert.throws(()=>payloads.validatePayload(extra),/hash mismatch/);
});
test('D05 retained controller consumes its pinned restore SQL after mutable source changes',()=>{
  const source=path.join(evidence,'mutable-source');fs.mkdirSync(source);
  for(const relative of payloads.controllerFiles){const to=path.join(source,relative);fs.mkdirSync(path.dirname(to),{recursive:true});fs.copyFileSync(path.join(workspace,relative),to);}
  const retained=payloads.createPayload({...payloadDefinition,sourceRoot:source,outputRoot:path.join(evidence,'actual-consumption'),kind:'p12-d-operations',sourceReleaseId:'R1'});
  fs.writeFileSync(path.join(source,'infra/p12-d/Invalidate-RestoredCredentials.sql'),'-- mutable SQL must not be loaded');
  const oldWorkspace=process.env.P12_D_WORKSPACE_ROOT;process.env.P12_D_WORKSPACE_ROOT=workspace;
  let RetainedOperation;try{RetainedOperation=require(path.join(retained.root,retained.entrypoint)).Operation;}finally{if(oldWorkspace===undefined)delete process.env.P12_D_WORKSPACE_ROOT;else process.env.P12_D_WORKSPACE_ROOT=oldWorkspace;}
  const ev=path.join(evidence,'sql-consumption');fs.mkdirSync(ev);
  const op=new RetainedOperation({...request(),controllerPayload:retained,evidenceDirectory:ev},manifest);op.recordExecution();const loaded=op.credentialSql();
  assert.equal(op.releaseFile('infra/p12-c/compose.yaml'),path.join(manifest.artifacts.operationalPayload.root,'infra/p12-c/compose.yaml'));
  assert.ok(manifest.artifacts.operationalPayload.files.every(f=>!f.path.startsWith('infra/p12-d/')));
  assert.match(loaded.text,/UPDATE guest_access_session SET revoked_at/);assert.doesNotMatch(loaded.text,/mutable SQL/);
  assert.equal(loaded.path,path.join(retained.root,'infra/p12-d/Invalidate-RestoredCredentials.sql'));
  assert.equal(loaded.sha256,retained.files.find(f=>f.path.endsWith('Invalidate-RestoredCredentials.sql')).sha256);
  const executed=JSON.parse(fs.readFileSync(path.join(ev,'execution-payload.json'),'utf8'));
  assert.equal(executed.controller.path,path.join(retained.root,retained.entrypoint));assert.equal(executed.release.kind,'p12-c-runtime');
  assert.ok(executed.consumed.some(f=>f.relativePath==='infra/p12-d/Invalidate-RestoredCredentials.sql' && f.sourceReleaseId==='R1'));
  assert.ok(executed.consumed.filter(f=>f.role==='common-controller').every(f=>f.path.startsWith(retained.root)));
  assert.ok(executed.consumed.some(f=>f.role==='release-runtime' && f.sourceReleaseId==='R0' && f.path.startsWith(manifest.artifacts.operationalPayload.root)));
});
test('D09 detached host roles release launcher IO and cleanup guards identity under one deadline',async()=>{
  const host=require('../infra/p12-d/host-runtime.cjs'),jdk=path.join(workspace,'.local-tools/jdk-21.0.12.1+1/bin');
  const owned=path.join(evidence,'owned-host-roles'),classes=path.join(owned,'classes'),release=path.join(owned,'releases/R0');fs.mkdirSync(classes,{recursive:true});fs.mkdirSync(release,{recursive:true});
  const source=path.join(owned,'HostLifecycleProbe.java');
  fs.writeFileSync(source,String.raw`public class HostLifecycleProbe {
 public static void main(String[] args)throws Exception {
  String role=java.util.Arrays.stream(args).anyMatch(a->a.equals("--spring.profiles.active=db,worker"))?"worker":"api";
  String javaExecutable=ProcessHandle.current().info().command().orElseThrow();
  String jar=java.nio.file.Path.of(HostLifecycleProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  Process child=new ProcessBuilder(javaExecutable,"-cp",jar,"HostLifecycleProbe$Leaf").inheritIO().start();
  java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("HOST_LEAF_ROOT"),role+".json"),Long.toString(child.pid()));
  System.out.println("Started MarketplaceApplication");while(true)Thread.sleep(1000);
 }
 public static class Leaf {public static void main(String[] args)throws Exception{while(true)Thread.sleep(1000);}}
}`);
  for(const [exe,args]of [['javac.exe',['-d',classes,source]],['jar.exe',['--create','--file',path.join(release,'marketplace.jar'),'--main-class','HostLifecycleProbe','-C',classes,'.']]]){
    const result=await runOwned(path.join(jdk,exe),args,{timeout:10000});assert.equal(result.code,0,result.err);
  }
  const ownedRun=crypto.randomUUID(),spec={root:owned,r:{runId:ownedRun,commandId:crypto.randomUUID()},m:{releaseId:'R0',runtime:{javaPath:path.join(jdk,'java.exe'),powershellPath:manifest.runtime.powershellPath,applicationEnvironment:{}}},t:{root:owned,mediaPath:path.join(owned,'media'),postgresPort:39971,database:'d2_it_'+ownedRun.replaceAll('-',''),apiPort:39972},env:{...process.env,PACKAGE_DB_USER:'synthetic',PACKAGE_DB_PASSWORD:'synthetic-private',HOST_LEAF_ROOT:owned}};
  const specPath=path.join(owned,'spec.json');fs.writeFileSync(specPath,JSON.stringify(spec));
  const launcher=path.join(owned,'launcher.cjs'),retainedHost=path.join(controllerPayload.root,'infra/p12-d/host-runtime.cjs');
  fs.writeFileSync(launcher,`const fs=require('fs'),host=require(${JSON.stringify(retainedHost)});const op=JSON.parse(fs.readFileSync(${JSON.stringify(specPath)}));op.releaseDir=()=>${JSON.stringify(release)};op.event=v=>fs.appendFileSync(${JSON.stringify(path.join(owned,'launcher-phases.jsonl'))},JSON.stringify(v)+'\\n');(async()=>{await host.start(op,'api');await host.start(op,'worker');console.log('OWNED_LAUNCHER_EXITED');})().catch(e=>{console.error(e);process.exitCode=1;});`);
  let originalApi,records;
  const op={...spec,releaseDir:()=>release,event:value=>fs.appendFileSync(path.join(owned,'rescue-phases.jsonl'),JSON.stringify(value)+'\n')};
  try{
    const launched=await runOwned(process.execPath,[launcher],{cwd:workspace,timeout:10000});assert.equal(launched.code,0,launched.err);assert.match(launched.out,/OWNED_LAUNCHER_EXITED/);
    records=await host.inspect(op);assert.equal(records.api.running,true);assert.equal(records.worker.running,true);
    const prefixCollision={...records.api,commandLine:records.api.commandLine.replace(records.api.jar,records.api.jar+'-different')};
    assert.throws(()=>host.verifyRecord(op,'api',records.api,prefixCollision),/exact JAR argument changed/);
    assert.deepEqual(host.windowsArguments('"C:\\Program Files\\java.exe" -jar "D:\\owned folder\\marketplace.jar"'),['C:\\Program Files\\java.exe','-jar','D:\\owned folder\\marketplace.jar']);
    const leaves={api:Number(fs.readFileSync(path.join(owned,'api.json'),'utf8')),worker:Number(fs.readFileSync(path.join(owned,'worker.json'),'utf8'))};
    const target={root:owned,mediaPath:spec.t.mediaPath,project:'p12d-'+ownedRun.replaceAll('-',''),database:spec.t.database,container:'p12d-postgres-'+ownedRun.replaceAll('-',''),httpsPort:39973,postgresPort:39971,apiPort:39972};
    fs.writeFileSync(path.join(owned,'ownership.json'),JSON.stringify({runId:ownedRun,target}));const cleanupManifest=path.join(owned,'manifest.json');fs.writeFileSync(cleanupManifest,JSON.stringify({runtime:{...spec.m.runtime,execution:'host-jar'}}));
    originalApi=fs.readFileSync(path.join(owned,'processes/api.json'),'utf8');const stale={...JSON.parse(originalApi),created:'2000-01-01T00:00:00.0000000Z'};fs.writeFileSync(path.join(owned,'processes/api.json'),JSON.stringify(stale));
    const cleanup=async(name)=>{const request={workspaceRoot:workspace,controllerPayload,target,runId:ownedRun,commandId:crypto.randomUUID(),manifestPath:cleanupManifest,evidenceDirectory:path.join(evidence,name),cleanupDeadlineEpochMs:Date.now()+10000};const file=path.join(owned,name+'.json');fs.writeFileSync(file,JSON.stringify(request));const result=await runOwned(process.execPath,[retainedHost,'--stop-owned',file],{cwd:workspace,timeout:10000});return {result,request,audit:JSON.parse(fs.readFileSync(path.join(request.evidenceDirectory,'result.json'),'utf8'))};};
    const guarded=await cleanup('stale-pid-cleanup');assert.notEqual(guarded.result.code,0);assert.equal(guarded.audit.status,'failed');assert.match(guarded.audit.attempts.find(a=>a.role==='api').error,/creation identity changed/);assert.equal(guarded.audit.attempts.find(a=>a.role==='worker').status,'stopped');
    assert.ok(await host.identity(records.api.pid,manifest.runtime.powershellPath));assert.equal(await host.identity(records.worker.pid,manifest.runtime.powershellPath),null);assert.equal(await host.identity(leaves.worker,manifest.runtime.powershellPath),null);
    fs.writeFileSync(path.join(owned,'processes/api.json'),originalApi);
    const cleaned=await cleanup('verified-host-cleanup');assert.equal(cleaned.result.code,0,cleaned.result.err);assert.equal(cleaned.audit.status,'passed');assert.equal(cleaned.audit.attempts.find(a=>a.role==='api').status,'stopped');assert.equal(cleaned.audit.attempts.find(a=>a.role==='worker').status,'already-stopped');
    assert.ok(cleaned.audit.dependencies.every(dependency=>dependency.path.startsWith(controllerPayload.root)));assert.equal(cleaned.audit.deadlineEpochMs,cleaned.request.cleanupDeadlineEpochMs);
    assert.equal(await host.identity(records.api.pid,manifest.runtime.powershellPath),null);assert.equal(await host.identity(leaves.api,manifest.runtime.powershellPath),null);
    const phases=fs.readFileSync(path.join(cleaned.request.evidenceDirectory,'phases.jsonl'),'utf8').trim().split(/\r?\n/).map(JSON.parse);assert.ok(Date.parse(phases[0].deadline)<=cleaned.request.cleanupDeadlineEpochMs);
    const stopped=JSON.parse(fs.readFileSync(path.join(owned,'processes/api.json'),'utf8'));
    const receipt=host.terminationReceipt(op,'api',stopped);assert.equal(receipt.commandId,cleaned.request.commandId);assert.equal(receipt.runId,ownedRun);assert.equal(receipt.pid,records.api.pid);assert.equal(receipt.created,records.api.created);assert.equal(receipt.observedAbsent,true);assert.equal(receipt.afterIdentity,null);
    // Deterministically model reuse of that numeric PID after observed exit.
    // Terminal evidence refers to the original incarnation, never its replacement.
    const replacement={pid:stopped.pid,created:'2099-01-01T00:00:00.0000000Z',commandLine:'C:\\unrelated.exe --unrelated'};
    const terminalState=host.roleState(op,'api',stopped,replacement);assert.equal(terminalState.running,false);assert.equal(terminalState.terminationVerified,true);assert.equal(terminalState.stoppedByCommandId,cleaned.request.commandId);
    assert.throws(()=>host.verifyRecord(op,'api',records.api,replacement),/creation identity changed/);
    assert.throws(()=>host.roleState(op,'api',{...records.api,status:'identity-pending'},null),/not verified/);
    assert.throws(()=>host.roleState(op,'api',{...records.api,status:'stopped'},replacement),/terminal incarnation receipt/);
    assert.throws(()=>host.roleState(op,'api',{...stopped,pid:process.pid},replacement),/incarnation mismatch/);
    assert.throws(()=>host.roleState(op,'api',{...stopped,termination:{...stopped.termination,sha256:'0'.repeat(64)}},replacement),/receipt hash mismatch/);
    const terminalOnly={...op,m:{...op.m,runtime:{...op.m.runtime,powershellPath:path.join(owned,'must-not-query-old-pids.exe')}}};
    const terminalRoles=await host.inspect(terminalOnly);assert.equal(terminalRoles.api.running,false);assert.equal(terminalRoles.worker.running,false);
    const later={...op,r:{...op.r,commandId:crypto.randomUUID()}};await host.stop(later);
    assert.equal(host.terminationReceipt(op,'api',JSON.parse(fs.readFileSync(path.join(owned,'processes/api.json'),'utf8'))).commandId,cleaned.request.commandId);
    await host.start(later,'api');const restarted=await host.inspect(later);assert.equal(restarted.api.status,'verified');assert.equal(restarted.api.running,true);assert.notEqual(restarted.api.created,stopped.created);
    const leafDeadline=Date.now()+3000;let restartedLeaf;
    while(Date.now()<leafDeadline){restartedLeaf=Number(fs.readFileSync(path.join(owned,'api.json'),'utf8'));if(restartedLeaf!==leaves.api)break;await new Promise(resolve=>setTimeout(resolve,20));}
    assert.notEqual(restartedLeaf,leaves.api);await host.stop(later);assert.equal(await host.identity(restarted.api.pid,manifest.runtime.powershellPath),null);assert.equal(await host.identity(restartedLeaf,manifest.runtime.powershellPath),null);
  }finally{
    if(originalApi){const current=JSON.parse(fs.readFileSync(path.join(owned,'processes/api.json'),'utf8'));if(current.status!=='stopped' && current.created!==JSON.parse(originalApi).created)fs.writeFileSync(path.join(owned,'processes/api.json'),originalApi);}
    await host.stop(op);
  }
});
test('D08 quiet rollback proof preserves every row sequence media and closed worker boundary',()=>{
  const rowHash=sha('canonical complete purchase row including private fields and amounts');
  const database={purchase_order:{count:1,md5:'a'.repeat(32),rowHashes:[rowHash]},__sequences:[{name:'order_sequence',value:4}]};
  const media={namespace:runId,files:[{path:runId+'.png',size:17,sha256:'c'.repeat(64)}],referenced:[runId],unreferenced:[]};
  const before={database,media,observedAt:'2026-11-16T00:00:00Z',quiescenceSha256:'d'.repeat(64),processes:{mode:'host-jar',containers:[],hostRoles:{api:{pid:123,releaseId:'R1',running:false},worker:{pid:124,releaseId:'R1',running:false}}}};
  const after={...before,observedAt:'2026-11-16T00:00:01Z',processes:{mode:'host-jar',containers:[],hostRoles:{api:{pid:125,releaseId:'R0',jarSha256:manifest.artifacts.jar.sha256,running:true},worker:{pid:124,releaseId:'R1',running:false}}}};
  const copy=value=>JSON.parse(JSON.stringify(value));
  const prove=(name,original,candidate)=>{const ev=path.join(evidence,name);fs.mkdirSync(ev);const op=new Operation({...request(),action:'Rollback',expectedReleaseId:'R1',evidenceDirectory:ev},manifest);return {op,ev,run:()=>op.preserveRollback(original,candidate)};};
  const good=prove('quiet-proof-pass',before,after),proof=good.run();assert.equal(proof.status,'passed');assert.equal(proof.equal.all,true);assert.equal(proof.commandId,commandId);assert.equal(proof.fromReleaseId,'R1');assert.equal(proof.toReleaseId,'R0');assert.equal(proof.beforeSha256,proof.afterSha256);
  assert.equal(proof.beforeSha256,snapshotSha256(database,{...media,path:'checkpoint-private-copy'}));assert.equal(proof.beforeSha256,snapshotSha256(database,media));
  assert.equal(proof.controllerPayloadSha256,controllerPayload.sha256);assert.equal(proof.releasePayloadSha256,manifest.artifacts.operationalPayload.sha256);
  const cases=[
    ['row-removed',sample=>{sample.database.purchase_order.count=0;sample.database.purchase_order.rowHashes=[];}],
    ['one-won-or-immutable-row-change',sample=>{sample.database.purchase_order.rowHashes=[sha('same purchase row with amount changed by 1 won')];}],
    ['unregistered-table',sample=>{sample.database.new_table={count:0,md5:'b'.repeat(32),rowHashes:[]};}],
    ['sequence-mutated',sample=>{sample.database.__sequences[0].value++;}],
    ['media-mutated',sample=>{sample.media.files[0].sha256='e'.repeat(64);}],
    ['media-removed',sample=>{sample.media.files=[];}],
    ['worker-opened-early',sample=>{sample.processes.hostRoles.worker.running=true;}],
    ['wrong-api-release',sample=>{sample.processes.hostRoles.api.releaseId='R1';}],
    ['row-coverage-omitted',sample=>{delete sample.database.purchase_order.rowHashes;}]
  ];
  for(const [name,mutate]of cases){const candidate=copy(after);mutate(candidate);const guarded=prove('quiet-'+name,before,candidate);assert.throws(guarded.run,/Application rollback/);const failed=JSON.parse(fs.readFileSync(path.join(guarded.ev,'rollback-preservation.json'),'utf8'));assert.equal(failed.status,'failed');assert.equal(failed.equal.all,false);}
  const bigintBefore=copy(before),bigintAfter=copy(after);bigintBefore.database.__sequences[0].value='9007199254740992';bigintAfter.database.__sequences[0].value='9007199254740993';
  assert.notEqual(snapshotSha256(bigintBefore.database,bigintBefore.media),snapshotSha256(bigintAfter.database,bigintAfter.media));assert.throws(prove('quiet-large-sequence-value',bigintBefore,bigintAfter).run,/changed database data/);
  const sourceWriting=copy(before);sourceWriting.processes.hostRoles.api.running=true;assert.throws(prove('quiet-source-still-writing',sourceWriting,after).run,/preservation boundary/);
});
test('D08 owner-private quiet rows preserve canonical bytes without stdout exposure',async()=>{
  const ev=path.join(evidence,'private-quiet-output');fs.mkdirSync(ev);const op=new Operation({...request(),evidenceDirectory:ev},manifest);
  const marker='synthetic-private-marker-'+crypto.randomUUID(),canonical='{"id":"private-id","amount":9007199254740993,"token":"'+marker+'"}';
  // Keep this literal as text; Java's precise JSON reader owns the later typed
  // row binding. JavaScript must not round or rewrite the canonical bigint.
  const line='{"table":"purchase_order","count":1,"md5":"'+crypto.createHash('md5').update(canonical).digest('hex')+'","rowHashes":["'+sha(canonical)+'"],"canonicalRows":['+JSON.stringify(canonical)+'],"rows":['+canonical+']}\n';
  const sink=await op.privateOutput();assert.equal(sink.access.ownerOnly,true);assert.equal(sink.access.protected,true);assert.equal(sink.access.emptyBeforeWrite,true);assert.equal(fs.statSync(sink.path).size,0);
  const written=await runOwned(process.execPath,['-e','process.stdin.pipe(process.stdout)'],{input:line,outputDescriptor:sink.descriptor,timeout:3000});assert.equal(written.code,0);assert.equal(written.out,'');assert.equal(written.err,'');
  assert.throws(()=>fs.fstatSync(sink.descriptor),error=>error.code==='EBADF');
  assert.equal(fs.readFileSync(sink.path,'utf8'),line);assert.match(fs.readFileSync(sink.path,'utf8'),/9007199254740993/);
  const inventory=privateRowInventory(sink.path);assert.deepEqual(inventory,{purchase_order:{count:1,md5:crypto.createHash('md5').update(canonical).digest('hex'),rowHashes:[sha(canonical)]}});
  assert.doesNotMatch(JSON.stringify(inventory),new RegExp(marker));
  const phases=fs.readFileSync(path.join(ev,'phases.jsonl'),'utf8');assert.doesNotMatch(phases,new RegExp(marker));assert.doesNotMatch(phases,/private-id|9007199254740993/);
  const bad=await op.privateOutput();fs.writeSync(bad.descriptor,line.replace('9007199254740993','9007199254740994'));fs.closeSync(bad.descriptor);assert.throws(()=>privateRowInventory(bad.path),error=>/Invalid owner-private/.test(error.message)&&!error.message.includes(marker));
  const malformed=await op.privateOutput();fs.writeSync(malformed.descriptor,'{"secret":"'+marker);fs.closeSync(malformed.descriptor);assert.throws(()=>privateRowInventory(malformed.path),error=>/Invalid owner-private/.test(error.message)&&!error.message.includes(marker));
});
test('D01 readiness retries remain progress under one absolute phase and preserve terminal failures',async()=>{
  const folder=name=>{const value=path.join(evidence,name);fs.mkdirSync(value);return value;};
  const events=ev=>fs.readFileSync(path.join(ev,'phases.jsonl'),'utf8').trim().split(/\r?\n/).map(JSON.parse);
  const successful=folder('readiness-transient'),op=new Operation({...request(),evidenceDirectory:successful},manifest);let calls=0;
  await op.readiness('controlled-readiness',async scope=>{
    const result=await op.command('controlled-readiness-probe',++calls===1?['p12d-known-invalid-readiness-command']:['--version'],{observation:scope.observation,acceptFailure:true,timeout:scope.remaining(3000)});
    return {ready:result.code===0,exitCode:result.code};
  },{limitMs:3000,pauseMs:1});
  const passed=events(successful);assert.equal(passed.filter(e=>e.state==='started').length,1);assert.equal(passed.filter(e=>e.state==='completed').length,1);assert.equal(passed.filter(e=>e.state==='failed'||e.state==='timeout').length,0);
  assert.equal(calls,2);assert.ok(passed.some(e=>e.state==='progress'&&e.observation==='command-result'&&e.exitCode!==0));assert.ok(passed.some(e=>e.state==='progress'&&e.exitCode===0));
  assert.ok(passed.filter(e=>e.state==='progress').every(e=>e.deadline===passed[0].deadline));
  const timeout=folder('readiness-deadline'),notReady=new Operation({...request(),evidenceDirectory:timeout},manifest);let attempts=0;const beginning=Date.now();
  await assert.rejects(notReady.readiness('persistent-not-ready',async()=>{attempts++;return {ready:false,exitCode:2};},{limitMs:40,pauseMs:3}),/deadline exceeded/);
  const expired=events(timeout);assert.equal(expired.filter(e=>e.state==='started').length,1);assert.equal(expired.filter(e=>e.state==='timeout').length,1);assert.equal(expired.filter(e=>e.state==='completed').length,0);assert.ok(attempts>1);assert.equal(Date.parse(expired[0].deadline)-Date.parse(expired[0].at),40);assert.ok(Date.now()-beginning<3000);
  assert.ok(expired.filter(e=>e.state==='progress').every(e=>e.deadline===expired[0].deadline));
  const hard=folder('readiness-hard-error'),broken=new Operation({...request(),evidenceDirectory:hard},manifest);let hardCalls=0;
  await assert.rejects(broken.readiness('hard-error',async()=>{hardCalls++;throw new Error('hard-fixture-failure');}),/hard-fixture-failure/);assert.equal(hardCalls,1);assert.equal(events(hard).filter(e=>e.state==='failed').length,1);assert.equal(events(hard).filter(e=>e.state==='completed').length,0);
  class DatabaseReadiness extends Operation {
    constructor(...args){super(...args);this.probes=[];this.answers=[{code:2,out:''},{code:0,out:'0\n'},{code:0,out:'1\n'}];}
    async command(phase,args,options){this.probes.push({phase,args,options});return this.answers.shift();}
  }
  const database=folder('readiness-final-server'),postgres=new DatabaseReadiness({...request(),evidenceDirectory:database},manifest);postgres.env={PACKAGE_DB_ADMIN_PASSWORD:'synthetic-private-password'};await postgres.postgresReady();
  assert.equal(postgres.probes.length,3);assert.ok(postgres.probes.every(probe=>probe.options.observation.deadline===postgres.probes[0].options.observation.deadline));
  assert.ok(postgres.probes.every(probe=>probe.args.includes('sh')&&probe.args.some(arg=>arg.includes('-h 127.0.0.1')&&arg.includes('$POSTGRES_PASSWORD')&&arg.includes('PGCONNECT_TIMEOUT=2'))));
  assert.ok(postgres.probes.every(probe=>!probe.args.join(' ').includes('synthetic-private-password')));assert.equal(events(database).filter(e=>e.state==='failed').length,0);
  const rejected=folder('readiness-hard-command'),badDatabase=new DatabaseReadiness({...request(),evidenceDirectory:rejected},manifest);badDatabase.answers=[{code:125,out:''}];await assert.rejects(badDatabase.postgresReady(),/probe failed/);assert.equal(badDatabase.probes.length,1);assert.equal(events(rejected).filter(e=>e.state==='failed').length,1);
});
test('D05 local mailbox is explicit API-only and public mode rejects the profile',()=>{
  const profiles=require('../infra/p12-d/host-runtime.cjs').roleProfiles;
  const copy=()=>JSON.parse(JSON.stringify(manifest));
  const local=copy();local.runtime={...local.runtime,mode:'local-full',execution:'host-jar',phoneProvider:'local-test',localMailbox:true,javaPath:path.join(workspace,'.local-tools/jdk-21.0.12.1+1/bin/java.exe'),applicationEnvironment:{FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:39971',GCLOUD_PROJECT:'demo-p12d-mailbox-guard',MARKETPLACE_PHONE_MODE:'local-test'}};
  assert.equal(validateManifest(local),local);assert.equal(profiles(local.runtime,'api'),'db,local-mailbox');assert.equal(profiles(local.runtime,'worker'),'db,worker');
  const disabled={...local.runtime,localMailbox:false},absent={...local.runtime};delete absent.localMailbox;
  assert.equal(profiles(disabled,'api'),'db');assert.equal(profiles(absent,'api'),'db');assert.equal(profiles(disabled,'worker'),'db,worker');
  const publicMode=copy();publicMode.runtime.localMailbox=true;assert.throws(()=>validateManifest(publicMode),/restricted to the local host API/);assert.throws(()=>profiles(publicMode.runtime,'api'),/restricted to the local host API/);assert.throws(()=>profiles(publicMode.runtime,'worker'),/restricted to the local host API/);
  publicMode.runtime.localMailbox=false;assert.equal(validateManifest(publicMode),publicMode);
  for(const invalid of ['true',1,null]){const input=copy();input.runtime.localMailbox=invalid;assert.throws(()=>validateManifest(input),/explicit boolean/);assert.throws(()=>profiles({...local.runtime,localMailbox:invalid},'api'),/explicit boolean/);}
  const bypass=copy();bypass.runtime.applicationEnvironment.SPRING_PROFILES_ACTIVE='db,local-mailbox';assert.throws(()=>validateManifest(bypass),/Unsupported application environment/);
  assert.throws(()=>profiles({...local.runtime,execution:'compose'},'api'),/restricted to the local host API/);
});
test.after(()=>{fs.writeFileSync(path.join(evidence,'contract-evidence.json'),JSON.stringify({kind:'operational-guard-tests',evidenceDirectory:evidence,registeredTests:17,intentionalRed:0,fullCompose:false,checkpointRestore:false},null,2));process.stdout.write('Operational guard evidence: '+evidence+'\n');});
