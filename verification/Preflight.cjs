// Cheap, read-only checks before emulators, builds, databases or Docker resource creation.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
function registeredGuardFiles(registry){
 assert.ok(Array.isArray(registry.files)&&registry.files.length,'Explicit guard file registration required');
 const seen=new Set();for(const file of registry.files){assert.ok(typeof file==='string'&&!path.isAbsolute(file)&&!file.split(/[\\/]/).includes('..')&&/\.(cjs|mjs)$/.test(file)&&!seen.has(file),'Invalid or duplicate guard file');seen.add(file)}
 return [...registry.files];
}
function assertCollection(report,requirements){
 assert.equal((report.errors||[]).length,0,'Browser collection errors');
 const actual=[];
 function visit(suites){for(const suite of suites||[]){for(const spec of suite.specs||[])for(const test of spec.tests||[]){assert.equal(test.expectedStatus,'passed','Skipped collection case');actual.push(test.projectName+'#'+path.basename(spec.file.replaceAll('\\','/'))+':'+spec.title)}visit(suite.suites)}}visit(report.suites);
 const expected=[requirements].flat().flatMap(row=>row.tests);assert.ok(expected.length,'Empty browser registration');
 assert.equal(new Set(expected).size,expected.length,'Duplicate browser registration');assert.equal(new Set(actual).size,actual.length,'Duplicate collected browser identity');
 assert.deepEqual(actual.toSorted(),expected.toSorted(),'Browser collection differs from preserved required project/file/title IDs');return actual;
}
function snapshotSources(){
 const result=cp.spawnSync('git',['-c','safe.directory='+root.replaceAll('\\','/'),'ls-files','--cached','--others','--exclude-standard','--','backend','frontend','infra','verification','verify.ps1'],{cwd:root,encoding:'utf8',windowsHide:true,timeout:10000,maxBuffer:8*1024*1024});
 assert.equal(result.status,0,'Source inventory failed');return [...new Set(result.stdout.trim().split(/\r?\n/))].filter(file=>file&&fs.existsSync(path.join(root,file))&&fs.statSync(path.join(root,file)).isFile()).sort().map(file=>({path:file,sha256:sha(path.join(root,file))}));
}
function snapshotPreparation(selection,settings,environment=process.env){
 const groupConcurrency=require('./DbGroupExecution.cjs').validateConcurrency(settings?.groupConcurrency);
 for(const name of ['offline','reverseGroups','resourceOnlyDiagnostic'])assert.equal(typeof settings?.[name],'boolean','Explicit preflight setting '+name);
 const endpoint=settings.endpoint;assert.ok(endpoint&&Number.isInteger(endpoint.port)&&typeof endpoint.instance==='string'&&typeof endpoint.container==='string'&&typeof endpoint.confirmation==='string','Explicit preflight endpoint identity');
 const names=new Set();if(selection.selected.includes('release-upgrade'))for(const name of ['P12_D_H129_JAR','P12_D_HISTORY_PROOF'])names.add(name);
 if(selection.selected.includes('release-recovery'))for(const name of ['P12_D_R0_JAR','P12_D_R0_DIST','P12_D_HISTORY_PROOF'])names.add(name);
 function inventory(directory,base=directory){return fs.readdirSync(directory,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?inventory(path.join(directory,entry.name),base):[{file:path.relative(base,path.join(directory,entry.name)).replaceAll('\\','/'),sha256:sha(path.join(directory,entry.name)),bytes:fs.statSync(path.join(directory,entry.name)).size}]).sort((a,b)=>a.file.localeCompare(b.file))}
 const artifacts=[...names].sort().map(name=>{const value=environment[name];assert.ok(typeof value==='string'&&path.isAbsolute(value),'Explicit preflight historical artifact '+name);const absolute=path.resolve(value),stat=fs.statSync(absolute);return {name,path:absolute,realPath:fs.realpathSync(absolute),...(stat.isDirectory()?{kind:'directory',files:inventory(absolute)}:{kind:'file',sha256:sha(absolute),bytes:stat.size})}});
 let historicalRuntime;
 if(selection.selected.includes('release-recovery')){
  // Same derivation as ReleaseRecoveryIT: R0/backend/target/JAR -> R0.
  const sourceRoot=path.dirname(path.dirname(path.dirname(path.resolve(environment.P12_D_R0_JAR))));
  const files=require('../infra/p12-d/payload.cjs').runtimeFiles.map(relative=>{
   const file=path.join(sourceRoot,relative);
   assert.ok(fs.existsSync(file),'Explicit historical runtime closure missing: '+relative);
   assert.ok(fs.lstatSync(file).isFile(),'Explicit historical runtime closure requires regular file: '+relative);
   for(let current=path.dirname(file);;current=path.dirname(current)){assert.ok(!fs.lstatSync(current).isSymbolicLink(),'Linked historical runtime closure rejected: '+relative);if(current===path.dirname(current))break}
   return {path:relative,sha256:sha(file),bytes:fs.statSync(file).size};
  });
  historicalRuntime={sourceRoot,realPath:fs.realpathSync(sourceRoot),files};
 }
 const portPrerequisites=releasePortPrerequisites(selection);
 return {groupConcurrency,offline:settings.offline,reverseGroups:settings.reverseGroups,resourceOnlyDiagnostic:settings.resourceOnlyDiagnostic,endpoint:{port:endpoint.port,instance:endpoint.instance,container:endpoint.container,confirmation:endpoint.confirmation},artifacts,...(historicalRuntime?{historicalRuntime}:{}),...(portPrerequisites.length?{releasePortPrerequisites:portPrerequisites}:{})};
}
function releasePortPrerequisites(selection,exists=fs.existsSync){
 if(!selection.selected.includes('release-recovery'))return [];
 const files=require('./ReleasePortObservation.cjs').prerequisiteFiles(root);
 return files.map(file=>{assert.ok(exists(file),'Explicit release port observation prerequisite missing: '+file);assert.ok(fs.statSync(file).isFile(),'Release port prerequisite must be a regular file');return {path:file,sha256:sha(file)}});
}
async function assertReleasePortAvailability(selection,run){
 if(!selection.selected.includes('release-recovery'))return;
 const {tools,alreadyRunning}=require('./ReleasePortObservation.cjs');
 assert.ok(alreadyRunning(await run('release-port-running-distro',tools.wsl,['--list','--running','--quiet'])),'docker-desktop must be already running before release preparation; no auto-start');
 const command=await run('release-port-nsenter-prerequisite',tools.wsl,['-d','docker-desktop','--exec','sh','-c','command -v nsenter']);
 assert.match(command.replaceAll('\0','').trim(),/^\/[A-Za-z0-9/_-]*\/nsenter$/,'Explicit existing nsenter prerequisite required');
}
function assertSameSource(proof,inputs,selection,preparation){assert.equal(proof.status,'passed','Passed preflight evidence required');assert.deepEqual(proof.inputs,inputs,'Source changed since preflight');assert.deepEqual(proof.selection,selection,'Selection changed since preflight');assert.ok(proof.preparation&&preparation,'Immutable preparation evidence required');assert.deepEqual(proof.preparation,preparation,'Preparation settings or historical artifacts changed since preflight')}
async function snapshotPreflightNetworks(execDocker){return (await require('../infra/p12-e/preservation.cjs').snapshotDockerResources(execDocker)).networks}
function projectRequirements(requirements,project){return [requirements].flat().map(row=>({...row,tests:row.tests.filter(id=>id.startsWith(project+'#'))})).filter(row=>row.tests.length)}
module.exports={registeredGuardFiles,assertCollection,assertSameSource,snapshotSources,projectRequirements,snapshotPreparation,snapshotPreflightNetworks,releasePortPrerequisites,assertReleasePortAvailability};
if(require.main===module)main().catch(error=>{console.error(error.message);process.exitCode=1});
async function main(){
 const [mode,requestPath]=process.argv.slice(2),request=read(requestPath),out=path.dirname(requestPath),resultPath=path.join(out,'result.json');
 if(mode==='--assert'){const current=JSON.parse(Buffer.from(process.argv[4],'base64').toString('utf8'));assertSameSource(read(resultPath),snapshotSources(),current.selection,snapshotPreparation(current.selection,current.settings));return}
 assert.equal(mode,'--run');assert.ok(!fs.existsSync(resultPath),'Fresh preflight required');
 const result={status:'failed',started:new Date().toISOString(),selection:request.selection,inputs:snapshotSources(),commands:[],collections:[],tools:{node:process.execPath,version:process.version}};
 const node=process.execPath,frontend=path.join(root,'frontend'),npm=path.join(path.dirname(node),'node_modules/npm/bin/npm-cli.js');
 async function run(name,program,args,env=process.env,cwd=root,limit=60000){
  const at=Date.now();let r,error;try{r=await require('../infra/p12-d/process-runner.cjs').runOwned(program,args,{cwd,env,timeout:limit,maxBuffer:16*1024*1024})}catch(value){error=value;r=value.result||{out:'',err:value.message,code:null}}
  fs.writeFileSync(path.join(out,name+'.log'),(r.out||'')+(r.err||''));result.commands.push({name,program,args,cwd,limitMs:limit,elapsedMs:Date.now()-at,status:r.code,timedOut:r.timedOut,error:error?.message});
  assert.ok(!error&&r.code===0,'Preflight '+name+' failed; evidence retained');return r.out;
 }
 function exists(file){assert.ok(fs.existsSync(file),'Explicit preflight prerequisite missing: '+file)}
 try{
  result.preparation=snapshotPreparation(request.selection,request.settings);
  assert.equal(process.version,'v24.22.0','Declared Node runtime');
  for(const file of [npm,path.join(frontend,'node_modules/@playwright/test/cli.js'),path.join(frontend,'node_modules/typescript/bin/tsc'),path.join(root,'.local-tools/jdk-21.0.12.1+1/bin/java.exe'),path.join(root,'.local-tools/m2/repository'),path.join(root,'.local-tools/playwright'),path.join(root,'.local-tools/p06-a-provider-probe-20261025/node_modules/.bin/firebase.cmd')])exists(file);
  const selected=request.selection.selected;
  if(selected.includes('release-upgrade'))for(const name of ['P12_D_H129_JAR','P12_D_HISTORY_PROOF']){assert.ok(process.env[name],'Explicit prerequisite '+name);exists(process.env[name])}
  if(selected.includes('release-recovery'))for(const name of ['P12_D_R0_JAR','P12_D_R0_DIST','P12_D_HISTORY_PROOF']){assert.ok(process.env[name],'Explicit prerequisite '+name);exists(process.env[name])}
  await assertReleasePortAvailability(request.selection,(name,program,args)=>run(name,program,args,process.env,root,10000));
  for(const source of result.inputs.filter(x=>/\.(cjs|mjs)$/.test(x.path)))await run('syntax-'+result.commands.length,node,['--check',path.join(root,source.path)],process.env,root,10000);
  await run('typecheck',node,[npm,'run','typecheck'],process.env,frontend);await run('lint',node,[npm,'run','lint'],process.env,frontend);
  // These values are inert collection inputs: --list never starts a web server or issues a business command.
  const env={...process.env,A3_API_PORT:'1',A3_EVIDENCE:out,P12_C_BASE_URL:'https://localhost:1',P12_C_ACCOUNTS:'{}',P12_C_PASSWORDS:'{}',P12_C_GATE_USER:'collection',P12_C_GATE_PASSWORD:'collection',P12_C_OBSERVER_URL:'http://127.0.0.1:1',P12_C_OBSERVER_KEY:'collection-only',P12_C_DIST:out,P12_C_LEGACY_DIST:out,P12_C_LEGACY_BASE_URL:'https://localhost:2'};
  async function collect(label,config,required,extra={}){const report=JSON.parse(await run('collection-'+label,node,[path.join(frontend,'node_modules/@playwright/test/cli.js'),'test','--list','--reporter=json','--config',config],{...env,...extra},frontend));const ids=assertCollection(report,read(path.join(__dirname,required)));result.collections.push({label,config,requirements:required,requirementsSha256:sha(path.join(__dirname,required)),ids});}
  if(selected.includes('browser-api'))await collect('browser-api',path.join(frontend,'auth-e2e/playwright.config.ts'),'required-browser.json');
  for(const project of ['desktop','mobile'])if(selected.includes('site-maintenance-'+project)){
   const required=projectRequirements(read(path.join(__dirname,'required-browser-site-maintenance.json')),project);
   const config=path.join(frontend,'auth-e2e/site-maintenance.config.ts');const report=JSON.parse(await run('collection-site-'+project,node,[path.join(frontend,'node_modules/@playwright/test/cli.js'),'test','--list','--reporter=json','--config',config],{...env,P12_B_BROWSER_PROJECT:project},frontend));result.collections.push({label:'site-maintenance-'+project,config,requirements:'required-browser-site-maintenance.json',requirementsSha256:sha(path.join(__dirname,'required-browser-site-maintenance.json')),ids:assertCollection(report,required)});
  }
  if(selected.includes('package-runtime'))await collect('package',path.join(frontend,'auth-e2e/package-runtime.config.ts'),'required-browser-package.json');
  if(selected.includes('release-recovery'))for(const mode of ['L','P']){
   const contract={runId:crypto.randomUUID(),mode,source:{baseURL:'https://localhost:1',observerURL:'http://127.0.0.1:1',observerKey:'collection-only-24-characters'},restore:{baseURL:'https://localhost:2',observerURL:'http://127.0.0.1:2',observerKey:'collection-only-24-characters'},dist:{R0:out,R1:out},accounts:{desktop:{},mobile:{}},passwords:{},...(mode==='L'?{emulator:{host:'127.0.0.1:1',project:'demo-collection'}}:{})};const file=path.join(out,'collection-contract-'+mode+'.json');fs.writeFileSync(file,JSON.stringify(contract));await collect('release-'+mode,path.join(frontend,'auth-e2e/release-runtime.config.ts'),mode==='L'?'required-browser-release-recovery.json':'required-browser-release-public.json',{P12_D_BROWSER_CONTRACT:file,FIREBASE_AUTH_EMULATOR_HOST:'',GCLOUD_PROJECT:''});
  }
  if(request.selection.rootPhases.selected.includes('container-reproduction')){
   const access=path.join(out,'collection-access.json'),accounts=path.join(out,'collection-accounts.json');fs.writeFileSync(access,JSON.stringify({baseURL:'https://localhost:1',gateUser:'collection',gatePassword:'collection'}));fs.writeFileSync(accounts,'{}');await collect('container',path.join(root,'infra/p12-e/browser.config.ts'),'required-browser-container-reproduction.json',{P12_E_ACCESS:access,P12_E_ACCOUNTS:accounts});
   const {preflightCachedImages,references}=require('../infra/p12-e/cached-images.cjs'),observedImages={};
   for(const reference of Object.values(references))observedImages[reference]=JSON.parse(await run('cached-image-'+result.commands.length,'docker',['image','inspect','--format','{"Id":{{json .Id}},"RepoDigests":{{json .RepoDigests}},"RootFS":{"Layers":{{json .RootFS.Layers}}}}',reference]));
   result.cachedImages=preflightCachedImages({dockerfile:fs.readFileSync(path.join(root,'infra/p12-c/Dockerfile'),'utf8'),compose:fs.readFileSync(path.join(root,'infra/p12-c/compose.yaml'),'utf8'),inspect:reference=>observedImages[reference]});
   const runtime=require('./PowerShellRuntime.cjs'),{subnets}=require('../infra/p12-e/reproduce.cjs');
   const routes=JSON.parse(await run('host-routes',runtime.executable,runtime.commandArguments('Import-VerificationNetworkModule; @(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -AsArray'),process.env,root,15000));
   assert.ok(Array.isArray(routes)&&routes.length&&routes.every(value=>typeof value==='string'&&/^\d+\.\d+\.\d+\.\d+\/\d+$/.test(value)),'Nonempty native IPv4 host routes required');
   // Reuse exact identity validation, secret-free projection and batches of at most20.
   const observed=await snapshotPreflightNetworks(args=>run('network-inventory-'+result.commands.length,'docker',args));
   const excluded=[...routes.filter(cidr=>cidr!=='0.0.0.0/0'),...observed.flatMap(network=>network.ipam.config.map(value=>value.subnet).filter(value=>value&&!value.includes(':')))];result.network={hostRoutes:routes,networks:observed,excluded,available:subnets(excluded)};
  }
  assertSameSource({...result,status:'passed'},snapshotSources(),request.selection,snapshotPreparation(request.selection,request.settings));result.status='passed';
 }catch(error){result.error=error.message;throw error}finally{result.finished=new Date().toISOString();fs.writeFileSync(resultPath,JSON.stringify(result,null,2));}
}
