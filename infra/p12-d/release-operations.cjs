'use strict';
// Local, owned Compose releases only. No build, pull, deletion, down, repair or clean.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const https = require('node:https');
const hostRuntime = require('./host-runtime.cjs');
const payloads = require('./payload.cjs');
const startupRequest = require.main===module && process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,'')) : null;
const workspace = path.resolve(startupRequest?.workspaceRoot || process.env.P12_D_WORKSPACE_ROOT || path.resolve(__dirname, '../..'));
const localRoot = path.join(workspace, '.local-tools');
const UUID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const SHA = /^[0-9a-f]{64}$/;
const fail = message => { throw new Error(message); };
const check = (condition, message) => { if (!condition) fail(message); };
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const json = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const stable = value => JSON.stringify(value, (_, v) => v && !Array.isArray(v) && typeof v === 'object' ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b))) : v);
const equal = (a,b) => stable(a) === stable(b);
function snapshotSha256(database,media) {
  return sha(stable({database,media:{namespace:media.namespace,files:media.files,referenced:media.referenced,unreferenced:media.unreferenced}}));
}
function rollbackEquality(before,after) {
  const beforeTables=Object.keys(before.database).filter(name=>name!=='__sequences').sort();
  const afterTables=Object.keys(after.database).filter(name=>name!=='__sequences').sort();
  const tables=equal(beforeTables,afterTables);
  const validRows=database=>Object.entries(database).filter(([name])=>name!=='__sequences').every(([,table])=>Array.isArray(table.rowHashes) && table.rowHashes.length===table.count && table.rowHashes.every(value=>SHA.test(value)));
  const rows=tables && validRows(before.database) && validRows(after.database) && beforeTables.every(name=>equal(before.database[name],after.database[name]));
  const sequences=Array.isArray(before.database.__sequences) && Array.isArray(after.database.__sequences) && equal(before.database.__sequences,after.database.__sequences);
  const database=equal(before.database,after.database);
  const media=equal(before.media,after.media);
  const roles=sample=>sample.processes.mode==='host-jar'?{api:sample.processes.hostRoles?.api,worker:sample.processes.hostRoles?.worker}:{api:sample.processes.containers.find(c=>c.service==='api'),worker:sample.processes.containers.find(c=>c.service==='worker')};
  const oldRoles=roles(before),newRoles=roles(after);
  const processBoundary=oldRoles.api?.running===false && oldRoles.worker?.running===false && newRoles.api?.running===true && newRoles.worker?.running===false;
  return {database,tables,rows,sequences,media,processBoundary,all:database&&tables&&rows&&sequences&&media&&processBoundary};
}
function privateRowInventory(file) {
  try{
    const database={};
    for(const line of fs.readFileSync(file,'utf8').split(/\r?\n/).filter(Boolean)){
      const row=JSON.parse(line);check(/^[a-z_][a-z0-9_]*$/.test(row.table) && !Object.hasOwn(database,row.table),'Invalid private row table');
      check(Array.isArray(row.canonicalRows) && Array.isArray(row.rows) && Array.isArray(row.rowHashes) && row.canonicalRows.length===row.count && row.rows.length===row.count && row.rowHashes.length===row.count,'Private row coverage mismatch');
      // Keep canonical text exact rather than parsing bigint/decimal values
      // through JavaScript Number. The private consumer binds typed rows with
      // its precision-preserving JSON parser before running causal validators.
      check(row.canonicalRows.every((text,index)=>typeof text==='string' && sha(Buffer.from(text,'utf8'))===row.rowHashes[index]),'Private canonical row binding mismatch');
      check(crypto.createHash('md5').update(row.canonicalRows.join('\n'),'utf8').digest('hex')===row.md5,'Private canonical aggregate mismatch');
      database[row.table]={count:row.count,md5:row.md5,rowHashes:row.rowHashes};
    }
    check(Object.keys(database).length>0,'Private row inventory empty');return database;
  }catch{throw new Error('Invalid owner-private quiet row capture');}
}
function flywayChecksum(bytes) {
  let crc=0xffffffff;
  for(const line of bytes.toString('utf8').replace(/^\uFEFF/,'').split(/\r\n|\r|\n/))for(const byte of Buffer.from(line,'utf8')) {
    crc^=byte;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);
  }
  return (crc^0xffffffff)|0;
}
function safePath(p, parent, exists = false) {
  check(typeof p === 'string' && path.isAbsolute(p), 'Absolute path required');
  const full = path.resolve(p), relative = path.relative(parent, full);
  check(relative && !relative.startsWith('..') && !path.isAbsolute(relative), 'Path outside owned root');
  for (let current = full; ; current = path.dirname(current)) {
    if (fs.existsSync(current)) check(!fs.lstatSync(current).isSymbolicLink(), 'Linked path rejected');
    if (current === path.dirname(current)) break;
  }
  if (exists) check(fs.existsSync(full), 'Required path missing');
  return full;
}
function atomic(p, value) {
  const temporary = p + '.' + crypto.randomUUID() + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2), {flag:'wx'});
  fs.renameSync(temporary, p);
}
function inventory(root) {
  check(fs.statSync(root).isDirectory(), 'Inventory root is not a directory');
  const files = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir).sort()) {
      const full = path.join(dir, entry), info = fs.lstatSync(full);
      check(!info.isSymbolicLink(), 'Linked inventory entry rejected');
      if (info.isDirectory()) walk(full);
      else {
        check(info.isFile(), 'Nonregular inventory entry');
        const relative = path.relative(root, full).replaceAll('\\','/');
        if (relative !== '.lock') files.push({path:relative, size:info.size, sha256:sha(fs.readFileSync(full))});
      }
    }
  }
  walk(root);
  return files;
}
function assertInventory(root, expected) {
  check(Array.isArray(expected) && equal(inventory(root), expected), 'File inventory mismatch');
}
function copyFiles(source, target, files) {
  for (const item of files) {
    const from = safePath(path.resolve(source,item.path),source,true);
    const to = safePath(path.resolve(target,item.path),target);
    fs.mkdirSync(path.dirname(to), {recursive:true});
    fs.copyFileSync(from,to,fs.constants.COPYFILE_EXCL);
  }
  assertInventory(target,files);
}
function validateManifest(m) {
  check(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(m.releaseId || ''), 'Invalid release ID');
  check(/^[0-9a-f]{40}$/.test(m.source?.head || '') && SHA.test(m.source?.inputsSha256 || ''), 'Source identification missing');
  const operational=payloads.validatePayload(m.artifacts?.operationalPayload);
  safePath(operational.root,localRoot,true);
  check(operational.sourceReleaseId===m.releaseId && operational.sourceHead===m.source.head && operational.sourceInputsSha256===m.source.inputsSha256,'Release operational payload provenance mismatch');
  check(/^sha256:[0-9a-f]{64}$/.test(m.artifacts?.image || '') || /@sha256:[0-9a-f]{64}$/.test(m.artifacts?.image || ''), 'Immutable application image required');
  for (const item of [m.artifacts.jar,m.artifacts.nginxConfig,m.artifacts.proxyConfig,...m.artifacts.migrations]) {
    safePath(item.path,workspace,true);
    check(SHA.test(item.sha256) && sha(fs.readFileSync(item.path)) === item.sha256, 'Artifact hash mismatch');
  }
  safePath(m.artifacts.dist.path,workspace,true);
  assertInventory(m.artifacts.dist.path,m.artifacts.dist.files);
  check(m.artifacts.dist.files.some(f=>f.path === 'index.html'), 'Production index missing');
  check(['local-full','public-demo'].includes(m.runtime?.mode) && m.runtime.access === 'invited-only', 'Explicit runtime mode/access required');
  check(m.runtime.phoneProvider === (m.runtime.mode === 'local-full' ? 'local-test':'disabled'), 'Mode/provider mismatch');
  check(m.runtime.execution === (m.runtime.mode === 'local-full' ? 'host-jar':'compose'), 'Execution/mode mismatch');
  check(m.runtime.localMailbox===undefined || typeof m.runtime.localMailbox==='boolean','Local mailbox flag must be an explicit boolean');
  if(m.runtime.localMailbox===true)check(m.runtime.mode==='local-full' && m.runtime.execution==='host-jar','Local mailbox is restricted to the local host API');
  safePath(m.runtime.secretsPath,workspace,true);
  for (const f of ['tls.crt','tls.key','reviewers']) safePath(path.join(m.runtime.secretsPath,f),m.runtime.secretsPath,true);
  safePath(m.runtime.environmentFile,workspace,true);
  check(path.isAbsolute(m.runtime.powershellPath || ''),'Explicit PowerShell 7 executable required');
  safePath(m.runtime.powershellPath,path.parse(m.runtime.powershellPath).root,true);
  const env = m.runtime.applicationEnvironment || {};
  const allowed = ['FIREBASE_AUTH_EMULATOR_HOST','GCLOUD_PROJECT','MARKETPLACE_PHONE_PROVIDER','MARKETPLACE_PHONE_MODE'];
  check(Object.keys(env).every(k=>allowed.includes(k)), 'Unsupported application environment');
  if (m.runtime.mode === 'public-demo') check(Object.keys(env).length === 0, 'Public demo rejects emulator environment');
  if (m.runtime.mode === 'local-full') {
    check(/^127\.0\.0\.1:[0-9]+$/.test(env.FIREBASE_AUTH_EMULATOR_HOST || ''), 'Explicit loopback emulator host required');
    check(/^demo-[a-z0-9-]+$/.test(env.GCLOUD_PROJECT || '') && env.MARKETPLACE_PHONE_MODE === 'local-test', 'Local emulator configuration required');
    safePath(m.runtime.javaPath,workspace,true);
  }
  check(Array.isArray(m.compatibility?.apiReleaseIds) && Array.isArray(m.compatibility?.uiReleaseIds), 'Compatibility matrix missing');
  check(/^\d+$/.test(m.compatibility.schemaVersion || ''), 'Schema version missing');
  check(typeof m.readiness?.sql === 'string' && /^SELECT\b/i.test(m.readiness.sql) && typeof m.readiness.expected === 'string', 'Read-only business readiness probe required');
  return m;
}
function validateRequest(r) {
  check(['Prepare','Checkpoint','Restore','Activate','Rollback','Inspect'].includes(r.action), 'Unknown action');
  check(UUID.test(r.commandId || '') && UUID.test(r.runId || ''), 'Explicit command/run UUID required');
  check(typeof r.expectedReleaseId === 'string', 'Expected release ID required');
  check(path.isAbsolute(r.workspaceRoot || '') && path.resolve(r.workspaceRoot)===workspace,'Explicit workspace root mismatch');
  const controller=payloads.validatePayload(r.controllerPayload);
  check(controller.kind==='p12-d-operations','Pinned P12-D controller required');
  safePath(controller.root,localRoot,true);
  const t = r.target;
  check(t && /^d2_it_[0-9a-f]{32}$/.test(t.database || '') && /^p12d-[a-z0-9-]{8,70}$/.test(t.project || '') && /^p12d-[a-z0-9-]{8,90}$/.test(t.container || ''), 'Explicit isolated target identity required');
  check(Number.isInteger(t.httpsPort) && t.httpsPort >= 20000 && t.httpsPort <= 65535, 'Isolated HTTPS port required');
  if(t.postgresPort !== undefined) check(Number.isInteger(t.postgresPort) && t.postgresPort >= 20000 && t.postgresPort <= 65535 && t.postgresPort !== t.httpsPort, 'Isolated postgres port required');
  if(t.apiPort !== undefined) check(Number.isInteger(t.apiPort) && t.apiPort >= 20000 && t.apiPort <= 65535 && ![t.httpsPort,t.postgresPort].includes(t.apiPort),'Isolated API port required');
  safePath(t.root,localRoot);
  safePath(t.mediaPath,t.root);
  check(path.resolve(t.mediaPath)===path.join(path.resolve(t.root),'media'),'Dedicated target/media path required');
  safePath(r.evidenceDirectory,localRoot);
  check(path.relative(t.root,r.evidenceDirectory).startsWith('..'), 'Evidence must be outside target root');
  safePath(r.manifestPath,workspace,true);
  return r;
}
class Operation {
  constructor(r,m) { this.r=r; this.m=m; this.t=r.target; this.root=this.t.root; this.ev=r.evidenceDirectory; this.step=0; this.secrets=[]; }
  event(value) { fs.appendFileSync(path.join(this.ev,'phases.jsonl'),JSON.stringify({at:new Date().toISOString(),...value})+'\n'); }
  consume(payload,relative,role) {
    const file=payloads.fileEvidence(payload,relative);
    if(!this.execution)this.execution={controller:null,release:{sourceReleaseId:this.m.artifacts.operationalPayload.sourceReleaseId,kind:this.m.artifacts.operationalPayload.kind,sha256:this.m.artifacts.operationalPayload.sha256},consumed:[]};
    if(!this.execution.consumed.some(x=>x.path===file.path && x.role===role))this.execution.consumed.push({...file,role});
    atomic(path.join(this.ev,'execution-payload.json'),this.execution);return file.path;
  }
  controllerFile(relative) { return this.consume(this.r.controllerPayload,relative,'common-controller'); }
  releaseFile(relative) { return this.consume(this.m.artifacts.operationalPayload,relative,'release-runtime'); }
  generatedFile(file,role) {
    const entry={path:file,role,sha256:sha(fs.readFileSync(file)),size:fs.statSync(file).size};
    if(!this.execution.generated)this.execution.generated=[];
    this.execution.generated=this.execution.generated.filter(existing=>existing.path!==file);this.execution.generated.push(entry);
    atomic(path.join(this.ev,'execution-payload.json'),this.execution);
  }
  recordExecution() {
    const entry=payloads.fileEvidence(this.r.controllerPayload,this.r.controllerPayload.entrypoint);
    check(path.resolve(__filename)===path.resolve(entry.path),'Operation must execute the retained controller entrypoint');
    this.execution={controller:{...entry,pid:process.pid,argvEntrypoint:path.resolve(process.argv[1]),sourceHead:this.r.controllerPayload.sourceHead,sourceInputsSha256:this.r.controllerPayload.sourceInputsSha256},release:{sourceReleaseId:this.m.artifacts.operationalPayload.sourceReleaseId,kind:this.m.artifacts.operationalPayload.kind,sha256:this.m.artifacts.operationalPayload.sha256},consumed:[],generated:[]};
    for(const relative of ['infra/p12-d/release-operations.cjs','infra/p12-d/host-runtime.cjs','infra/p12-d/process-runner.cjs','infra/p12-d/payload.cjs']){
      const actual=this.controllerFile(relative);check(require.cache[actual] || actual===__filename,'Controller dependency did not load from retained payload');
    }
  }
  credentialSql() { const sqlPath=this.controllerFile('infra/p12-d/Invalidate-RestoredCredentials.sql');return {path:sqlPath,text:fs.readFileSync(sqlPath,'utf8'),sha256:sha(fs.readFileSync(sqlPath))}; }
  redact(s) { for (const secret of this.secrets) if(secret) s=s.split(secret).join('[REDACTED]'); return s; }
  async command(phase,args,{input,output,outputDescriptor,timeout=60000,acceptFailure=false,observation}={}) {
    const progress=detail=>this.event({phase:observation.phase,state:'progress',commandPhase:phase,attempt:observation.attempt,deadline:new Date(observation.deadline).toISOString(),...detail});
    if(observation)progress({observation:'command-started'});
    else this.event({phase,state:'started',deadline:new Date(Date.now()+timeout).toISOString()});
    const stem = String(++this.step).padStart(3,'0')+'-'+phase;
    let result;
    try {
      result=await require('./process-runner.cjs').runOwned('docker',args,{cwd:workspace,env:this.env || process.env,input,output,outputDescriptor,timeout,onSpawn:pid=>observation?progress({observation:'command-spawned',pid}):this.event({phase,state:'spawned',pid})});
    } catch(error) {
      result=error.result || {out:'',err:'',code:null,timedOut:true};
      result.failure=error.message;
    }
    fs.writeFileSync(path.join(this.ev,stem+'.stderr.log'),this.redact(result.err));
    if(!output && outputDescriptor===undefined)fs.writeFileSync(path.join(this.ev,stem+'.stdout.log'),this.redact(result.out));
    if(observation)progress({observation:'command-result',exitCode:result.code,timedOut:result.timedOut,...(result.failure?{failure:result.failure}:{})});
    else this.event({phase,state:result.timedOut?'timeout':result.code===0?'completed':'failed',exitCode:result.code,...(result.failure?{failure:result.failure}:{})});
    if((result.failure || result.code!==0) && !acceptFailure)throw new Error(phase+' failed (see retained phase logs)');
    return result;
  }
  async readiness(phase,probe,{limitMs=30000,pauseMs=150,prepare}={}) {
    check(Number.isInteger(limitMs) && limitMs>0 && limitMs<=30000 && Number.isInteger(pauseMs) && pauseMs>=0 && pauseMs<=150,'Invalid finite readiness budget');
    const deadline=Date.now()+limitMs;let attempt=0;
    const scope={phase,deadline,remaining:(maximum=Infinity)=>{const remaining=Math.min(maximum,deadline-Date.now());check(remaining>0,'Readiness deadline exceeded: '+phase);return remaining;}};
    this.event({phase,state:'started',deadline:new Date(deadline).toISOString()});
    try{
      if(prepare)await prepare(scope);
      while(Date.now()<deadline){
        const observation={phase,deadline,attempt:++attempt};
        const result=await probe({...scope,attempt,observation});
        this.event({phase,state:'progress',observation:'readiness-observation',attempt,ready:result.ready===true,...(result.exitCode===undefined?{}:{exitCode:result.exitCode}),deadline:new Date(deadline).toISOString()});
        scope.remaining();
        if(result.ready===true){this.event({phase,state:'completed',attempts:attempt});return result;}
        await new Promise(resolve=>setTimeout(resolve,Math.min(pauseMs,scope.remaining())));
      }
      throw new Error('Readiness deadline exceeded: '+phase);
    }catch(error){this.event({phase,state:Date.now()>=deadline?'timeout':'failed',attempts:attempt,error:error.message});throw error;}
  }
  async postgresReady() {
    const query=`SELECT count(*) FROM pg_database WHERE datname='${this.t.database}'`;
    // The bootstrap server has no TCP listener. Use the final server's
    // authenticated loopback TCP connection, reading its existing password only
    // inside the container; no secret is an argument or phase/log field.
    const shell='export PGPASSWORD="$POSTGRES_PASSWORD"; export PGCONNECT_TIMEOUT=2; exec psql -h 127.0.0.1 -X -q -A -t -v ON_ERROR_STOP=1 -U postgres -d postgres -c "$1"';
    return this.readiness('postgres-ready',async scope=>{
      const result=await this.command('postgres-ready-probe',['exec',this.t.container,'sh','-c',shell,'p12d-readiness',query],{timeout:scope.remaining(3000),acceptFailure:true,observation:scope.observation});
      check(!result.failure && [0,2].includes(result.code),'PostgreSQL readiness probe failed (see retained phase logs)');
      check(result.code!==0 || ['0','1'].includes(result.out.trim()),'Unexpected database readiness response');
      return {ready:result.code===0 && result.out.trim()==='1',exitCode:result.code};
    });
  }
  async dockerJson(phase,args) { const x=await this.command(phase,args); return JSON.parse(x.out); }
  async sql(sql,{database=this.t.database,phase='database-observe',output,outputDescriptor}={}) {
    return (await this.command(phase,['exec','-i',this.t.container,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-d',database],{input:`SET statement_timeout='10s'; SET lock_timeout='3s';\n${sql}\n`,output,outputDescriptor,timeout:15000})).out.trim();
  }
  loadEnv() {
    const env={};
    for (const line of fs.readFileSync(this.m.runtime.environmentFile,'utf8').split(/\r?\n/)) {
      if(!line.trim() || line.startsWith('#'))continue;
      const match=/^(PACKAGE_DB_USER|PACKAGE_DB_PASSWORD|PACKAGE_DB_ADMIN_PASSWORD)=(.+)$/.exec(line);
      check(match && !/[\r\n\0]/.test(match[2]),'Only explicit DB credential names allowed in environment file');
      env[match[1]]=match[2];
    }
    check(/^[a-z][a-z0-9_]{2,40}$/.test(env.PACKAGE_DB_USER || '') && env.PACKAGE_DB_USER!=='postgres','Unprivileged database role required');
    check(env.PACKAGE_DB_PASSWORD?.length>=16 && env.PACKAGE_DB_ADMIN_PASSWORD?.length>=16 && env.PACKAGE_DB_PASSWORD!==env.PACKAGE_DB_ADMIN_PASSWORD,'Separate nonempty credentials required');
    this.secrets=[env.PACKAGE_DB_PASSWORD,env.PACKAGE_DB_ADMIN_PASSWORD];
    // Ambient PACKAGE_* and emulator settings never leak between L/P stacks.
    this.env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('PACKAGE_') && !k.startsWith('FIREBASE_') && !k.startsWith('GCLOUD_')));
    Object.assign(this.env,env,{PACKAGE_PROJECT:this.t.project,PACKAGE_PUBLIC_ORIGIN:this.m.runtime.origin,PACKAGE_DB_NAME:this.t.database,PACKAGE_HTTPS_PORT:String(this.t.httpsPort),PACKAGE_APP_IMAGE:this.m.artifacts.image,PACKAGE_DIST:path.join(this.root,'www'),PACKAGE_CONFIG:path.join(this.root,'config'),PACKAGE_SECRETS:this.m.runtime.secretsPath,PACKAGE_MEDIA:this.t.mediaPath});
  }
  async compose(phase,args) {
    return this.command(phase,['compose','--project-name',this.t.project,'-f',this.releaseFile('infra/p12-c/compose.yaml'),'-f',path.join(this.root,'compose.override.json'),...args]);
  }
  async assertOwned() {
    const owner=json(path.join(this.root,'ownership.json'));
    check(owner.runId===this.r.runId && equal(owner.target,this.t),'Target ownership mismatch');
    const db=await this.dockerJson('inspect-owned-database',['inspect',this.t.container]);
    check(db.length===1 && db[0].Config.Labels['com.docker.compose.project']===this.t.project && db[0].Config.Labels['marketplace.p12d.run']===this.r.runId,'Container ownership mismatch');
    const comment=await this.sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()",{phase:'database-identity'});
    check(comment===stable({runId:this.r.runId,project:this.t.project,database:this.t.database}),'Database identity mismatch');
    this.state=json(path.join(this.root,'state.json'));
    check((this.state.activeReleaseId || 'NONE')===this.r.expectedReleaseId,'Expected release mismatch');
    if(['Checkpoint','Inspect'].includes(this.r.action))check(this.m.releaseId===this.r.expectedReleaseId,'Current release manifest required');
    this.ownershipVerified=true;
  }
  save(state,extra={}) { this.state={...this.state,...extra,state,at:new Date().toISOString()};atomic(path.join(this.root,'state.json'),this.state);this.event({state}); }
  releaseDir(id=this.m.releaseId) { return path.join(this.root,'releases',id); }
  async installRelease() {
    const dest=this.releaseDir();
    if(fs.existsSync(dest)) {
      check(json(path.join(dest,'manifest.json')).manifestSha===sha(stable(this.m)),'Release ID reused with different bytes');
      assertInventory(path.join(dest,'dist'),this.m.artifacts.dist.files);
      check(sha(fs.readFileSync(path.join(dest,'marketplace.jar')))===this.m.artifacts.jar.sha256,'Retained JAR changed');
      check(sha(fs.readFileSync(path.join(dest,'nginx.conf')))===this.m.artifacts.nginxConfig.sha256 && sha(fs.readFileSync(path.join(dest,'package-proxy.conf')))===this.m.artifacts.proxyConfig.sha256,'Retained proxy configuration changed');
      return;
    }
    await this.command('verify-image-cache',['image','inspect',this.m.artifacts.image]);
    const digest=await this.command('verify-image-jar',['run','--name','p12d-image-proof-'+this.r.commandId,'--label','marketplace.p12d.run='+this.r.runId,'--pull','never','--network','none','--entrypoint','sha256sum',this.m.artifacts.image,'/app/marketplace.jar']);
    check(digest.out.trim().split(/\s+/)[0]===this.m.artifacts.jar.sha256,'Image JAR does not match manifest JAR');
    fs.mkdirSync(dest,{recursive:true});
    fs.mkdirSync(path.join(dest,'dist'));
    copyFiles(this.m.artifacts.dist.path,path.join(dest,'dist'),this.m.artifacts.dist.files);
    fs.copyFileSync(this.m.artifacts.jar.path,path.join(dest,'marketplace.jar'),fs.constants.COPYFILE_EXCL);
    fs.copyFileSync(this.m.artifacts.nginxConfig.path,path.join(dest,'nginx.conf'),fs.constants.COPYFILE_EXCL);
    fs.copyFileSync(this.m.artifacts.proxyConfig.path,path.join(dest,'package-proxy.conf'),fs.constants.COPYFILE_EXCL);
    atomic(path.join(dest,'manifest.json'),{manifestSha:sha(stable(this.m)),manifest:this.m});
    atomic(path.join(dest,'operational-payload.json'),this.m.artifacts.operationalPayload);
    const www=path.join(this.root,'www');fs.mkdirSync(www,{recursive:true});
    // Keep every immutable asset from every retained release. Same path/different bytes fails.
    for (const file of this.m.artifacts.dist.files.filter(f=>f.path!=='index.html')) {
      const to=safePath(path.resolve(www,file.path),www);
      if(fs.existsSync(to))check(sha(fs.readFileSync(to))===file.sha256,'Immutable asset collision');
      else {fs.mkdirSync(path.dirname(to),{recursive:true});fs.copyFileSync(path.join(dest,'dist',file.path),to,fs.constants.COPYFILE_EXCL);}
    }
  }
  override() {
    const common={MARKETPLACE_RUNTIME_MODE:this.m.runtime.mode,MARKETPLACE_RUNTIME_ACCESS:this.m.runtime.access,SERVER_FORWARD_HEADERS_STRATEGY:'framework',...this.m.runtime.applicationEnvironment};
    // Both networks are isolated; only loopback proxy/optional loopback DB are published.
    const data={services:{postgres:{container_name:this.t.container,labels:{'marketplace.p12d.run':this.r.runId}},api:{labels:{'marketplace.p12d.run':this.r.runId,'marketplace.p12d.release':this.m.releaseId},environment:common},worker:{labels:{'marketplace.p12d.run':this.r.runId,'marketplace.p12d.release':this.m.releaseId},environment:common}},networks:{edge:{external:true,name:this.t.project+'_edge'},data:{external:true,name:this.t.project+'_data'}},volumes:{'package-db':{labels:{'marketplace.p12d.run':this.r.runId}}}};
    if(this.t.postgresPort){data.services.postgres.ports=[`127.0.0.1:${this.t.postgresPort}:5432`];data.services.postgres.networks=['data','edge'];}
    fs.mkdirSync(path.join(this.root,'bootstrap'),{recursive:true});
    fs.writeFileSync(path.join(this.root,'bootstrap','init-app.sh'),fs.readFileSync(this.releaseFile('infra/postgres/init-app.sh'),'utf8').replaceAll('\r\n','\n'));
    this.generatedFile(path.join(this.root,'bootstrap','init-app.sh'),'postgres-initialization');
    data.services.postgres.volumes=[{type:'bind',source:path.join(this.root,'bootstrap','init-app.sh'),target:'/docker-entrypoint-initdb.d/10-init-app.sh',read_only:true}];
    atomic(path.join(this.root,'compose.override.json'),data);
  }
  async createTarget() {
    check(this.r.expectedReleaseId==='NONE' && !fs.existsSync(this.root),'Existing restore/prepare root rejected');
    const p=await this.command('check-project-unused',['ps','-a','--filter',`label=com.docker.compose.project=${this.t.project}`,'--format','{{.ID}}']);check(!p.out.trim(),'Existing project rejected');
    const n=await this.command('check-container-unused',['ps','-a','--filter',`name=^/${this.t.container}$`,'--format','{{.ID}}']);check(!n.out.trim(),'Existing container rejected');
    const v=await this.command('check-volume-unused',['volume','ls','--filter',`label=com.docker.compose.project=${this.t.project}`,'--format','{{.Name}}']);check(!v.out.trim(),'Existing project volume rejected');
    fs.mkdirSync(this.root);fs.mkdirSync(this.t.mediaPath,{recursive:true});fs.mkdirSync(path.join(this.root,'config'));
    atomic(path.join(this.root,'ownership.json'),{runId:this.r.runId,target:this.t,created:new Date().toISOString()});
    this.state={activeReleaseId:null};this.save('PREPARED',{preparedReleaseId:this.m.releaseId});
    await this.installRelease();
    this.event({phase:'prepare-owned-networks',state:'started',deadline:new Date(Date.now()+60000).toISOString()});
    this.controllerFile('infra/p12-c/ProxyNetwork.ps1');
    await hostRuntime.command(this.m.runtime.powershellPath,['-NoProfile','-NonInteractive','-File',this.controllerFile('infra/p12-d/New-ReleaseNetworks.ps1'),'-Project',this.t.project,'-RunId',this.r.runId,'-Evidence',this.ev,'-Execution',this.m.runtime.execution],undefined,60000);
    fs.copyFileSync(path.join(this.ev,'network-ownership.json'),path.join(this.root,'network-ownership.json'),fs.constants.COPYFILE_EXCL);
    this.event({phase:'prepare-owned-networks',state:'completed'});
    this.override();
    await this.compose('start-new-postgres',['up','-d','--pull','never','postgres']);
    await this.postgresReady();
    const actual=await this.dockerJson('verify-new-container-identity',['inspect',this.t.container]);
    check(actual.length===1 && actual[0].Config.Labels['marketplace.p12d.run']===this.r.runId && actual[0].Config.Labels['com.docker.compose.project']===this.t.project,'New container identity mismatch');
    const identity=stable({runId:this.r.runId,project:this.t.project,database:this.t.database});
    await this.sql(`COMMENT ON DATABASE "${this.t.database}" IS '${identity}';`,{phase:'mark-new-database'});
    await this.assertOwned();
  }
  async serviceIds() {
    const ids=(await this.command('inspect-project-containers',['ps','-a','--filter',`label=com.docker.compose.project=${this.t.project}`,'--format','{{.ID}}'])).out.trim().split(/\s+/).filter(Boolean);
    return ids.length ? this.dockerJson('inspect-project-identities',['inspect',...ids]):[];
  }
  async quiesce() {
    await this.assertOwned();
    await this.compose('close-ingress',['stop','-t','10','proxy']);
    await this.compose('stop-all-writers',['stop','-t','10','api','worker']);
    if(this.m.runtime.execution==='host-jar') await hostRuntime.stop(this);
    const containers=await this.serviceIds();
    check(containers.filter(c=>['proxy','api','worker'].includes(c.Config.Labels['com.docker.compose.service'])).every(c=>!c.State.Running),'Writer termination not observed');
    const connections=await this.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'",{phase:'observe-no-open-connections'});
    check(connections==='0','Open database connections prohibit checkpoint');
    atomic(path.join(this.ev,'quiescence.json'),{containers:containers.map(c=>({id:c.Id,service:c.Config.Labels['com.docker.compose.service'],running:c.State.Running})),otherConnections:Number(connections),observed:new Date().toISOString()});
    this.save('QUIESCED');
  }
  async privateOutput() {
    const file=path.join(this.ev,'quiet-after-'+crypto.randomUUID()+'.private.jsonl');
    // Create an empty file, remove inherited access and verify owner-only access
    // before any database row is written. No private values enter this script.
    const script=`$ErrorActionPreference='Stop'; $p=([Console]::In.ReadToEnd()|ConvertFrom-Json).path; $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User; $empty=[IO.File]::Open($p,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None); $empty.Dispose(); $acl=[Security.AccessControl.FileSecurity]::new(); $acl.SetOwner($owner); $acl.SetAccessRuleProtection($true,$false); $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner,[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.AccessControlType]::Allow)); Set-Acl -LiteralPath $p -AclObject $acl; $read=Get-Acl -LiteralPath $p; $rules=@($read.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])); if(-not $read.AreAccessRulesProtected -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $owner.Value -or $rules[0].AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow){throw 'Owner-private file ACL rejected'}; @{ownerSid=$owner.Value;protected=$true;ownerOnly=$true;emptyBeforeWrite=((Get-Item -LiteralPath $p).Length -eq 0)}|ConvertTo-Json -Compress`;
    this.event({phase:'prepare-private-quiet-output',state:'started',deadline:new Date(Date.now()+10000).toISOString()});
    const access=JSON.parse(await hostRuntime.command(this.m.runtime.powershellPath,['-NoProfile','-NonInteractive','-Command',script],JSON.stringify({path:file}),10000));
    check(access.ownerOnly && access.protected && access.emptyBeforeWrite,'Owner-private output preparation failed');
    this.event({phase:'prepare-private-quiet-output',state:'completed',ownerOnly:true});
    return {path:file,descriptor:fs.openSync(file,'r+'),access};
  }
  async snapshot({privateRows=false}={}) {
    // One read-only transaction observes a coherent canonical inventory, even
    // when Inspect is used while a caller is diagnosing a nonquiescent stack.
    const privateFields=privateRows?",''canonicalRows'',coalesce(json_agg(rowtext ORDER BY rowtext),''[]''::json),''rows'',coalesce(json_agg(document ORDER BY rowtext),''[]''::json)":'';
    const sql=`BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle='ISO, YMD';
SELECT format('SELECT json_build_object(''table'',%L,''count'',count(*),''md5'',md5(coalesce(string_agg(rowtext,E''\\n'' ORDER BY rowtext),'''')),''rowHashes'',coalesce(json_agg(encode(sha256(convert_to(rowtext,''UTF8'')),''hex'') ORDER BY rowtext),''[]''::json)${privateFields}) FROM (SELECT row_to_json(t)::text rowtext${privateRows?',to_jsonb(t) document':''} FROM marketplace.%I t) rows',tablename,tablename) FROM pg_tables WHERE schemaname='marketplace' ORDER BY tablename
\\gexec
COMMIT;`;
    let result;
    if(privateRows){
      const output=await this.privateOutput();
      // The owned runner takes and closes the secured descriptor. Do not close
      // its old numeric value twice after it could have been reused.
      await this.sql(sql,{phase:'private-quiet-database-snapshot',outputDescriptor:output.descriptor});
      result=privateRowInventory(output.path);
      this.lastPrivateRows={path:output.path,size:fs.statSync(output.path).size,sha256:sha(fs.readFileSync(output.path)),rowEncoding:'canonicalRows: exact PostgreSQL row_to_json text; rows: typed to_jsonb objects; both ordered by canonical row text',observedAt:new Date().toISOString(),access:output.access};
    }else{
      const output=await this.sql(sql,{phase:'canonical-database-snapshot'});result={};
      for(const line of output.split(/\r?\n/).filter(Boolean)){
        const {table,...value}=JSON.parse(line);check(/^[a-z_][a-z0-9_]*$/.test(table),'Unexpected table identifier');result[table]=value;
      }
    }
    check(Object.keys(result).length>0,'Application schema absent');
    result.__sequences=JSON.parse(await this.sql("SELECT coalesce(json_agg(json_build_object('name',sequencename,'value',last_value::text) ORDER BY sequencename),'[]') FROM pg_sequences WHERE schemaname='marketplace'"));
    return result;
  }
  async sequenceDiagnostics() {
    const output=await this.sql(`BEGIN READ ONLY;
SELECT format('SELECT json_build_object(''name'',%L,''canonicalValue'',%L,''lastValue'',last_value::text,''isCalled'',is_called,''start'',%L,''increment'',%L,''cache'',%L,''cycle'',%s,''observationRole'',current_user,''canSelect'',has_sequence_privilege(current_user,%L,''SELECT''),''canUsage'',has_sequence_privilege(current_user,%L,''USAGE'')) FROM marketplace.%I',sequencename,last_value::text,start_value::text,increment_by::text,cache_size::text,CASE WHEN cycle THEN 'true' ELSE 'false' END,format('marketplace.%I',sequencename),format('marketplace.%I',sequencename),sequencename) FROM pg_sequences WHERE schemaname='marketplace' ORDER BY sequencename
\\gexec
COMMIT;`,{phase:'quiet-sequence-diagnostics'});
    return output.split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));
  }
  async media() {
    const namespace=await this.sql('SELECT namespace FROM marketplace.media_namespace WHERE singleton');
    check(UUID.test(namespace),'Media namespace missing');
    check(fs.readFileSync(path.join(this.t.mediaPath,'.database'),'utf8')===namespace,'Media namespace mismatch');
    const files=inventory(this.t.mediaPath);
    const foreign=JSON.parse(await this.sql("SELECT coalesce(json_agg(json_build_object('table',c.relname,'column',a.attname)),'[]') FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_attribute a ON a.attrelid=f.conrelid AND a.attnum=f.conkey[1] WHERE f.contype='f' AND f.confrelid='marketplace.media_asset'::regclass"));
    const conditions=[];
    for(const entry of foreign){check(/^[a-z_][a-z0-9_]*$/.test(entry.table) && /^[a-z_][a-z0-9_]*$/.test(entry.column),'Invalid media foreign key');conditions.push(`EXISTS (SELECT 1 FROM marketplace."${entry.table}" r WHERE r."${entry.column}"=m.id)`);}
    const refs=JSON.parse(await this.sql(`SELECT coalesce(json_agg(id ORDER BY id),'[]') FROM marketplace.media_asset m WHERE retired_at IS NULL${conditions.length?' OR '+conditions.join(' OR '):''}`));
    for(const id of refs) {check(UUID.test(id),'Invalid media reference');check(files.some(f=>f.path===id+'.png'),'Referenced media bytes missing');}
    return {namespace,files,referenced:refs,unreferenced:files.filter(f=>f.path!=='.database' && !refs.some(id=>f.path===id+'.png')).map(f=>f.path)};
  }
  async rollbackBoundary({privateRows=false}={}) {
    const database=await this.snapshot({privateRows}),media=await this.media();
    const containers=(await this.serviceIds()).map(c=>({id:c.Id,image:c.Image,service:c.Config.Labels['com.docker.compose.service'],releaseId:c.Config.Labels['marketplace.p12d.release'] || null,running:c.State.Running}));
    const sample={database,media,observedAt:new Date().toISOString(),processes:{mode:this.m.runtime.execution,containers,hostRoles:this.m.runtime.execution==='host-jar'?await hostRuntime.inspect(this):null}};
    if(privateRows){
      sample.privateRows={...this.lastPrivateRows,snapshotSha256:snapshotSha256(database,media)};
      sample.sequenceDiagnostics=await this.sequenceDiagnostics();
      check(sample.sequenceDiagnostics.length===database.__sequences.length && sample.sequenceDiagnostics.every(item=>item.observationRole==='postgres' && item.canSelect===true && item.canUsage===true && database.__sequences.some(sequence=>sequence.name===item.name && sequence.value===item.canonicalValue)),'Quiet sequence diagnostic identity mismatch');
    }
    return sample;
  }
  preserveRollback(before,after) {
    const comparison=rollbackEquality(before,after);
    const api=after.processes.mode==='host-jar'?after.processes.hostRoles.api:after.processes.containers.find(container=>container.service==='api');
    comparison.processRelease=api?.releaseId===this.m.releaseId && (after.processes.mode!=='host-jar' || api?.jarSha256===this.m.artifacts.jar.sha256);
    comparison.all=comparison.all&&comparison.processRelease;
    const proof={version:1,status:comparison.all?'passed':'failed',commandId:this.r.commandId,runId:this.r.runId,target:this.t,fromReleaseId:this.r.expectedReleaseId,toReleaseId:this.m.releaseId,boundary:'after-quiesce-to-api-ready-before-worker-start',manifestSha256:sha(stable(this.m)),controllerPayloadSha256:this.r.controllerPayload.sha256,releasePayloadSha256:this.m.artifacts.operationalPayload.sha256,rowHashEncoding:'sha256(UTF8(row_to_json(row)::text)), ordered by full PostgreSQL row text',before,after,equal:comparison,beforeSha256:snapshotSha256(before.database,before.media),afterSha256:snapshotSha256(after.database,after.media)};
    atomic(path.join(this.ev,'rollback-preservation.json'),proof);
    // Retain the original exact full-database guard at this boundary. Media,
    // row coverage and actual worker-closed evidence strengthen the same guard.
    check(equal(before.database,after.database),'Application rollback changed database data');
    check(comparison.media,'Application rollback changed media data');
    check(comparison.all,'Application rollback preservation boundary failed');
    return proof;
  }
  async checkpoint() {
    await this.quiesce();
    const before=await this.snapshot(), mediaBefore=await this.media();
    const dump=path.join(this.ev,'database.dump.partial');
    await this.command('database-dump',['exec',this.t.container,'pg_dump','-U','postgres','-d',this.t.database,'--format=custom','--no-owner','--no-acl'],{output:dump});
    check(fs.statSync(dump).size>0,'Empty dump');
    await this.command('validate-dump-catalog',['exec','-i',this.t.container,'pg_restore','--list'],{input:fs.createReadStream(dump)});
    const copied=path.join(this.ev,'media.partial');fs.mkdirSync(copied);copyFiles(this.t.mediaPath,copied,mediaBefore.files);
    const after=await this.snapshot(),mediaAfter=await this.media();
    check(equal(before,after) && equal(mediaBefore,mediaAfter),'Writes observed during checkpoint');
    check(await this.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'")==='0','Connection appeared during checkpoint');
    fs.renameSync(dump,path.join(this.ev,'database.dump'));fs.renameSync(copied,path.join(this.ev,'media'));
    const manifest={version:1,complete:true,commandId:this.r.commandId,runId:this.r.runId,target:this.t,releaseId:this.state.activeReleaseId,releaseManifestSha:sha(stable(this.m)),snapshotSha256:snapshotSha256(before,mediaBefore),dump:{path:path.join(this.ev,'database.dump'),size:fs.statSync(path.join(this.ev,'database.dump')).size,sha256:sha(fs.readFileSync(path.join(this.ev,'database.dump')))},media:{...mediaBefore,path:path.join(this.ev,'media')},database:before,quiescenceSha256:sha(fs.readFileSync(path.join(this.ev,'quiescence.json')))};
    atomic(path.join(this.ev,'checkpoint.json'),manifest);
    this.save('CHECKPOINTED',{checkpointPath:path.join(this.ev,'checkpoint.json')});return manifest;
  }
  async restore() {
    safePath(this.r.checkpointPath,localRoot,true);const c=json(this.r.checkpointPath);
    check(c.complete===true && c.version===1,'Incomplete checkpoint rejected');
    check(c.runId!==this.r.runId && c.target.project!==this.t.project && c.target.database!==this.t.database && path.resolve(c.target.mediaPath)!==path.resolve(this.t.mediaPath),'Restore requires a separate target');
    check(SHA.test(c.snapshotSha256 || '') && c.snapshotSha256===snapshotSha256(c.database,c.media),'Checkpoint snapshot digest mismatch');
    safePath(c.dump.path,localRoot,true);safePath(c.media.path,localRoot,true);
    check(sha(fs.readFileSync(c.dump.path))===c.dump.sha256 && fs.statSync(c.dump.path).size===c.dump.size,'Checkpoint dump mismatch');assertInventory(c.media.path,c.media.files);
    check(sha(fs.readFileSync(path.join(path.dirname(this.r.checkpointPath),'quiescence.json')))===c.quiescenceSha256,'Quiescence evidence mismatch');
    await this.createTarget();
    await this.command('strict-new-database-restore',['exec','-i',this.t.container,'pg_restore','-U','postgres','-d',this.t.database,'--exit-on-error','--no-owner','--no-acl','--role',this.env.PACKAGE_DB_USER],{input:fs.createReadStream(c.dump.path)});
    copyFiles(c.media.path,this.t.mediaPath,c.media.files);
    const restored=await this.snapshot(),media=await this.media();
    check(equal(c.database,restored) && equal(c.media.files,media.files) && c.media.namespace===media.namespace,'Original/restore comparison failed');
    const restoredSnapshotSha256=snapshotSha256(restored,media);check(restoredSnapshotSha256===c.snapshotSha256,'Restored checkpoint snapshot digest mismatch');
    atomic(path.join(this.ev,'before-invalidation.json'),{database:restored,media,snapshotSha256:restoredSnapshotSha256,checkpointPath:this.r.checkpointPath,checkpointCommandId:c.commandId,checkpointManifestSha256:sha(fs.readFileSync(this.r.checkpointPath))});
    const loadedSql=this.credentialSql(),sqlPath=loadedSql.path;
    const sqlText=`SET p12d.restore_database='${this.t.database}'; SET p12d.restore_run='${this.r.runId}';\n`+loadedSql.text;
    await this.assertOwned();
    const invalidation=JSON.parse(await this.sql(sqlText,{phase:'restore-only-credential-invalidation'}));
    check(invalidation.database===this.t.database && invalidation.runId===this.r.runId,'Credential invalidation identity mismatch');
    const invalidated=await this.snapshot();
    const allowed=['spring_session','spring_session_attributes','guest_access_session','email_verification_token','password_reset_token','guest_checkout','guest_recovery_attempt'];
    const changed=Object.keys(restored).filter(t=>!equal(restored[t],invalidated[t]));check(changed.every(t=>allowed.includes(t)),'Business data changed during credential invalidation');
    atomic(path.join(this.ev,'after-invalidation.json'),{database:invalidated,changedTables:changed,invalidation,sqlSha256:loadedSql.sha256,sqlPath,controllerPayloadSha256:this.r.controllerPayload.sha256,selectedReleasePayloadSha256:this.m.artifacts.operationalPayload.sha256});
    this.save('CHECKPOINTED',{restoredFrom:this.r.checkpointPath,credentialsInvalidated:true});
  }
  async activate(rollback=false) {
    await this.assertOwned();await this.installRelease();
    if(rollback) {
      check(this.state.activeReleaseId!==this.m.releaseId,'Rollback must change the application release');
      check(this.m.compatibility.apiReleaseIds.includes(this.state.activeReleaseId),'Rollback compatibility matrix excludes current release');
    }
    if(this.state.activeReleaseId)check(this.m.compatibility.apiReleaseIds.includes(this.state.activeReleaseId) && this.m.compatibility.uiReleaseIds.includes(this.state.activeReleaseId),'Current API/UI release excluded by compatibility matrix');
    await this.quiesce();
    const original=rollback?await this.rollbackBoundary():null;
    if(original)original.quiescenceSha256=sha(fs.readFileSync(path.join(this.ev,'quiescence.json')));
    this.override();
    const release=this.releaseDir();
    for(const name of ['nginx.conf','package-proxy.conf']){fs.copyFileSync(path.join(release,name),path.join(this.root,'config',name));this.generatedFile(path.join(this.root,'config',name),'active-proxy-configuration');}
    if(this.m.runtime.execution!=='host-jar')await this.compose('start-api-exclusive-migration',['up','-d','--no-deps','--pull','never','api']);
    // Observe real HTTP status+body inside the isolated data network. No public traffic yet.
    await this.readiness('api-readiness',async scope=>{
      const result=this.m.runtime.execution==='host-jar' ? await hostRuntime.health(this,scope.remaining(3000)) : await this.command('api-health-probe',['run','--name',`p12d-health-${this.r.commandId}-${this.step}`,'--label','marketplace.p12d.run='+this.r.runId,'--pull','never','--network',this.t.project+'_edge','--entrypoint','/bin/sh','nginx@sha256:5b44a5ab8ab467854f2bf7b835a32f850f32eb414b749fbf7ed506b139cd8d6b','-c','wget -q -T 3 -O - http://api:8080/actuator/health'],{timeout:scope.remaining(5000),acceptFailure:true,observation:scope.observation});
      check(!result.failure && [0,1].includes(result.code),'API readiness probe failed (see retained phase logs)');
      return {ready:result.code===0 && result.out.trim()==='{"status":"UP"}',exitCode:result.code};
    },{prepare:this.m.runtime.execution==='host-jar'?()=>hostRuntime.start(this,'api'):undefined});
    const history=await this.sql("SELECT coalesce(max(version::integer),0) FROM marketplace.flyway_schema_history WHERE success AND version IS NOT NULL",{phase:'observe-migrated-schema'});
    check(history===this.m.compatibility.schemaVersion,'Schema version mismatch');
    const applied=JSON.parse(await this.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM marketplace.flyway_schema_history WHERE version IS NOT NULL",{phase:'validate-migration-inventory'}));
    check(applied.length===this.m.artifacts.migrations.length && applied.every(row=>row.success && this.m.artifacts.migrations.some(file=>file.version===row.version && flywayChecksum(fs.readFileSync(file.path))===row.checksum)),'Migration version/checksum inventory mismatch');
    atomic(path.join(this.ev,'flyway-validation.json'),{schemaVersion:history,applied});
    this.save('MIGRATED');
    const probe=await this.sql(`BEGIN READ ONLY; ${this.m.readiness.sql}; COMMIT;`,{phase:'business-readiness'});check(probe===this.m.readiness.expected,'Business readiness assertion failed');
    if(rollback)this.preserveRollback(original,await this.rollbackBoundary({privateRows:true}));
    this.save('READY');
    if(this.m.runtime.execution==='host-jar')await this.readiness('worker-readiness',async()=>({ready:true}),{prepare:()=>hostRuntime.start(this,'worker')});
    else await this.compose('start-matching-worker',['up','-d','--no-deps','--pull','never','worker']);
    const identities=await this.serviceIds();
    for(const service of this.m.runtime.execution==='compose'?['api','worker']:[]) {
      const c=identities.find(x=>x.Config.Labels['com.docker.compose.service']===service);
      check(c?.State.Running && c.Config.Labels['marketplace.p12d.release']===this.m.releaseId,'Actual running release mismatch');
      const image=await this.dockerJson('verify-running-image',['image','inspect',this.m.artifacts.image]);check(c.Image===image[0].Id,'Actual image bytes mismatch');
    }
    const index=path.join(this.root,'www','index.html'),temporary=index+'.'+this.r.commandId+'.tmp';
    fs.copyFileSync(path.join(release,'dist','index.html'),temporary,fs.constants.COPYFILE_EXCL);fs.renameSync(temporary,index);
    // Publish the pointer only once the complete index and API/worker pair are ready.
    await this.compose('open-loopback-ingress',['up','-d','--no-deps','--pull','never','proxy']);
    const proxyDeadline=Date.now()+30000;let proxyReady=false;
    this.event({phase:'verify-tls-invitation-gate',state:'started',deadline:new Date(proxyDeadline).toISOString()});
    while(Date.now()<proxyDeadline){
      const status=await new Promise(resolve=>{
        const request=https.get({hostname:'localhost',port:this.t.httpsPort,path:'/actuator/health',ca:fs.readFileSync(path.join(this.m.runtime.secretsPath,'tls.crt')),timeout:Math.max(1,Math.min(3000,proxyDeadline-Date.now()))},response=>{response.resume();resolve(response.statusCode);});
        request.on('timeout',()=>request.destroy());request.on('error',()=>resolve(0));
      });
      if(status===401){proxyReady=true;break;}await new Promise(r=>setTimeout(r,100));
    }
    check(proxyReady,'TLS invitation gate did not become ready');
    this.event({phase:'verify-tls-invitation-gate',state:'completed',unauthenticatedStatus:401});
    atomic(path.join(this.root,'active-release.json'),{releaseId:this.m.releaseId,manifestSha:sha(stable(this.m)),indexSha256:sha(fs.readFileSync(index)),operationalPayloadSha256:this.m.artifacts.operationalPayload.sha256,controllerPayloadSha256:this.r.controllerPayload.sha256,activated:new Date().toISOString()});
    this.save('ACTIVE',{activeReleaseId:this.m.releaseId,previousReleaseId:this.r.expectedReleaseId,artifactIdentities:identities.map(c=>({id:c.Id,image:c.Image,service:c.Config.Labels['com.docker.compose.service']})),hostRoles:this.m.runtime.execution==='host-jar'?await hostRuntime.inspect(this):null});
  }
  async inspect() {await this.assertOwned();return {state:this.state,containers:(await this.serviceIds()).map(c=>({id:c.Id,image:c.Image,service:c.Config.Labels['com.docker.compose.service'],running:c.State.Running})),hostRoles:this.m.runtime.execution==='host-jar'?await hostRuntime.inspect(this):null,pointer:fs.existsSync(path.join(this.root,'active-release.json'))?json(path.join(this.root,'active-release.json')):null};}
  async execute() {
    this.recordExecution();
    this.loadEnv();
    check(await hostRuntime.command(this.m.runtime.powershellPath,['-NoProfile','-NonInteractive','-Command','$PSVersionTable.PSVersion.Major'])==='7','PowerShell 7 is required');
    check(this.m.runtime.origin==='https://localhost','Exact loopback origin required');
    if(this.m.runtime.execution==='host-jar')check(this.t.postgresPort && this.t.apiPort,'Host JAR mode requires isolated database/API ports');
    for(const [name,item]of [['nginx.conf',this.m.artifacts.nginxConfig],['package-proxy.conf',this.m.artifacts.proxyConfig]]) {
      const expected=fs.readFileSync(this.releaseFile('infra/p12-c/'+name+'.template'),'utf8').replaceAll('__HOST__','localhost').replaceAll('__PORT__',String(this.t.httpsPort)).replaceAll('__UPSTREAM__',this.m.runtime.execution==='host-jar'?`host.docker.internal:${this.t.apiPort}`:'api:8080');
      check(fs.readFileSync(item.path,'utf8').replaceAll('\r\n','\n')===expected.replaceAll('\r\n','\n'),'Proxy configuration must render reviewed P12-C templates exactly');
    }
    if(this.r.action==='Prepare')await this.createTarget();
    if(this.r.action==='Restore')await this.restore();
    if(this.r.action==='Checkpoint')return this.checkpoint();
    if(this.r.action==='Activate')await this.activate();
    if(this.r.action==='Rollback')await this.activate(true);
    if(this.r.action==='Inspect')return this.inspect();
    return this.state;
  }
}
async function main(requestPath) {
  const r=validateRequest(json(requestPath)),m=validateManifest(json(r.manifestPath));
  const fingerprint=sha(stable({...r,evidenceDirectory:undefined})),locks=path.join(localRoot,'p12-d-operation-locks');fs.mkdirSync(locks,{recursive:true});
  const lock=path.join(locks,r.target.project+'.lock');let lockFd;
  try {lockFd=fs.openSync(lock,'wx');fs.writeFileSync(lockFd,stable({pid:process.pid,runId:r.runId,commandId:r.commandId}));}
  catch {fail('Project operation lock held; automatic stale lock removal is forbidden');}
  let op;
  try {
    const record=path.join(r.target.root,'commands',r.commandId+'.json');
    if(fs.existsSync(record)) {
      const prior=json(record);check(prior.fingerprint===fingerprint && prior.manifestSha===sha(stable(m)),'Command ID reused with different input');
      check(prior.resultSha256===sha(fs.readFileSync(prior.resultPath)),'Completed command artifact changed');
      for(const f of prior.artifacts)check(sha(fs.readFileSync(f.path))===f.sha256,'Completed output artifact changed');
      return json(prior.resultPath);
    }
    check(!fs.existsSync(r.evidenceDirectory),'Existing evidence directory rejected');fs.mkdirSync(r.evidenceDirectory);
    atomic(path.join(r.evidenceDirectory,'request.json'),{...r,manifestSha:sha(stable(m))});
    op=new Operation(r,m);const result=await op.execute();
    const output={status:'passed',action:r.action,commandId:r.commandId,runId:r.runId,target:r.target,releaseId:m.releaseId,result};
    const resultPath=path.join(r.evidenceDirectory,'result.json');atomic(resultPath,output);
    if(r.action!=='Inspect') {
      fs.mkdirSync(path.join(r.target.root,'commands'),{recursive:true});
      atomic(record,{fingerprint,manifestSha:sha(stable(m)),resultPath,resultSha256:sha(fs.readFileSync(resultPath)),artifacts:inventory(r.evidenceDirectory).filter(f=>f.path!=='result.json').map(f=>({path:path.join(r.evidenceDirectory,f.path),sha256:f.sha256}))});
    }
    return output;
  } catch(e) {
    if(op) {
      if(op.ownershipVerified && ['Activate','Rollback'].includes(r.action)){
        try{await op.compose('failed-cutover-maintenance',['stop','-t','10','proxy','api','worker']);if(m.runtime.execution==='host-jar')await hostRuntime.stop(op);}
        catch(cleanup){fs.writeFileSync(path.join(r.evidenceDirectory,'maintenance-failure.log'),op.redact(cleanup.message));}
      }
      if(e.detail)fs.writeFileSync(path.join(r.evidenceDirectory,'infrastructure-subprocess.stderr.log'),op.redact(e.detail));
      const failed={status:'failed',action:r.action,commandId:r.commandId,error:op.redact(e.message),completedState:op.state || null};
      try{atomic(path.join(r.evidenceDirectory,'result.json'),failed);}
      catch(resultWrite){process.stderr.write('P12D_RESULT_FALLBACK='+JSON.stringify({...failed,resultWriteFailure:resultWrite.code || resultWrite.message})+'\n');}
      // Never reopen automatically after a failed cutover. Retain all targets/evidence.
      if(fs.existsSync(r.target.root)){try{atomic(path.join(r.target.root,'last-failure.json'),{commandId:r.commandId,evidenceDirectory:r.evidenceDirectory,state:op.state || null});}catch(recordWrite){process.stderr.write('P12D_FAILURE_RECORD_WRITE='+String(recordWrite.code)+'\n');}}
    }
    throw e;
  } finally {if(lockFd!==undefined)fs.closeSync(lockFd);fs.unlinkSync(lock);}
}
module.exports={main,validateRequest,validateManifest,inventory,assertInventory,safePath,stable,Operation,flywayChecksum,snapshotSha256,rollbackEquality,privateRowInventory};
async function cli(requestPath) {
  const request=validateRequest(json(requestPath)),entry=payloads.fileEvidence(request.controllerPayload,request.controllerPayload.entrypoint);
  check(path.resolve(process.cwd())===workspace,'Controller workspace/cwd mismatch');
  if(path.resolve(__filename)===path.resolve(entry.path))return main(requestPath);
  // Only the retained child runs operations. There is intentionally no child
  // overall cap: its declared infrastructure phases each have finite deadlines.
  return new Promise((resolve,reject)=>{
    const child=spawn(process.execPath,[entry.path,requestPath],{cwd:workspace,windowsHide:true,stdio:'inherit',env:{...process.env,P12_D_WORKSPACE_ROOT:workspace}});
    child.on('error',reject);child.on('exit',code=>code===0?resolve({delegated:true}):reject(new Error('Retained controller failed; preserve its request evidence')));
  });
}
module.exports.cli=cli;
if(require.main===module)cli(process.argv[2]).then(r=>{if(!r.delegated)process.stdout.write(JSON.stringify({status:r.status,evidenceDirectory:json(process.argv[2]).evidenceDirectory})+'\n');}).catch(e=>{process.stderr.write(e.message+'\n');process.exitCode=1;});
