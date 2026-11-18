const test=require('node:test'),assert=require('node:assert/strict');
const cp=require('node:child_process'),path=require('node:path'),fs=require('node:fs'),crypto=require('node:crypto');
const {assertCollection,registeredGuardFiles,assertSameSource,projectRequirements,snapshotPreparation,snapshotPreflightNetworks}=require('./Preflight.cjs');
const collected=(names)=>({errors:[],suites:[{specs:names.map(([project,title])=>({file:'nested/example.spec.ts',title,tests:[{projectName:project,expectedStatus:'passed'}]}))}]});
test('preflight collection compares exact project file and title rather than counts',()=>{
 const required=[{tests:['desktop#example.spec.ts:required']}];
 assert.deepEqual(assertCollection(collected([['desktop','required']]),required),['desktop#example.spec.ts:required']);
 assert.throws(()=>assertCollection(collected([['desktop','renamed']]),required),/collection differs/);
 assert.throws(()=>assertCollection(collected([['mobile','required']]),required),/collection differs/);
});
test('preflight collection rejects missing duplicate skipped and collection errors',()=>{
 const required=[{tests:['desktop#example.spec.ts:required']}],report=collected([['desktop','required']]);
 assert.throws(()=>assertCollection(collected([]),required));
 assert.throws(()=>assertCollection(collected([['desktop','required'],['desktop','required']]),required));
 report.suites[0].specs[0].tests[0].expectedStatus='skipped';assert.throws(()=>assertCollection(report,required));
 report.errors=[{message:'parse failed'}];assert.throws(()=>assertCollection(report,required));
});
test('preflight guard invocation consumes registered files including cached image guards',()=>{
 const registry=require('./required-container-reproduction-guards.json');
 const files=registeredGuardFiles(registry);
 assert.ok(files.includes('infra/p12-e/cached-images.test.cjs'));
 assert.ok(files.includes('verification/Test-Preflight.cjs'));
 assert.throws(()=>registeredGuardFiles({...registry,files:[...registry.files,registry.files[0]]}));
 assert.throws(()=>registeredGuardFiles({...registry,files:['../outside.cjs']}));
});
test('preflight reuse rejects changed source selection or missing successful evidence',()=>{
 const proof={status:'passed',inputs:[{path:'verify.ps1',sha256:'abc'}],selection:{selected:['inventory']},preparation:{}};
 assert.doesNotThrow(()=>assertSameSource(proof,proof.inputs,proof.selection,{}));
 assert.throws(()=>assertSameSource({...proof,status:'failed'},proof.inputs,proof.selection,{}));
 assert.throws(()=>assertSameSource(proof,[{path:'verify.ps1',sha256:'changed'}],proof.selection,{}));
 assert.throws(()=>assertSameSource(proof,proof.inputs,{selected:['identity']},{}));
 assert.throws(()=>assertSameSource({...proof,preparation:undefined},proof.inputs,proof.selection,{}));
});
test('preflight site collection preserves object and array registrations with only the explicit project selected',()=>{
 const registration={id:'site',tests:['desktop#site.spec.ts:one','mobile#site.spec.ts:one']};
 for(const value of [registration,[registration]])assert.deepEqual(projectRequirements(value,'desktop'),[{id:'site',tests:['desktop#site.spec.ts:one']}]);
 assert.deepEqual(registration.tests,['desktop#site.spec.ts:one','mobile#site.spec.ts:one']);
});
test('preflight silent native success produces consumable empty log and failure preserves UTF8 exit and evidence',()=>{
 const runtime=require('./PowerShellRuntime.cjs');
 const result=cp.spawnSync(runtime.executable,runtime.checkedArguments(['-File',path.join(__dirname,'Test-PreflightLogging.ps1')]),{encoding:'utf8',windowsHide:true,timeout:15000});
 assert.equal(result.error,undefined);assert.equal(result.status,0,result.stderr);
 const proof=JSON.parse(result.stdout);assert.equal(proof.status,'passed');assert.equal(proof.silentExit,0);assert.equal(proof.failureExit,7);assert.equal(proof.copiedBytes,0);assert.equal(proof.failurePreserved,true);
});
test('preflight reuse binds immutable execution settings and historical artifact paths contents and dist inventory',()=>{
 const folder=path.resolve(__dirname,'../.local-tools/preflight-config-'+crypto.randomUUID());fs.mkdirSync(folder);
 const sourceRoot=path.join(folder,'R0'),file=path.join(sourceRoot,'backend/target/old.jar'),proofFile=path.join(folder,'proof.json'),dist=path.join(folder,'dist');fs.mkdirSync(path.dirname(file),{recursive:true});fs.mkdirSync(dist);fs.writeFileSync(file,'old jar');fs.writeFileSync(proofFile,'history proof');fs.writeFileSync(path.join(dist,'index.html'),'old dist');
 for(const relative of require('../infra/p12-d/payload.cjs').runtimeFiles){const target=path.join(sourceRoot,relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,'old runtime '+relative)}
 const selection={selected:['release-upgrade','release-recovery']},settings={groupConcurrency:2,offline:true,reverseGroups:false,resourceOnlyDiagnostic:false,endpoint:{port:15433,instance:'default',container:'',confirmation:'127.0.0.1:15433|default'}},env={P12_D_H129_JAR:file,P12_D_R0_JAR:file,P12_D_R0_DIST:dist,P12_D_HISTORY_PROOF:proofFile};
 const proof={status:'passed',inputs:[{path:'same',sha256:'same'}],selection,preparation:snapshotPreparation(selection,settings,env)};
 const matches=(changedSettings=settings,changedEnv=env)=>assertSameSource(proof,proof.inputs,selection,snapshotPreparation(selection,changedSettings,changedEnv));
 assert.doesNotThrow(()=>matches(settings,{...env,FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:9999',GCLOUD_PROJECT:'demo-new-child'}));
 for(const flag of ['offline','reverseGroups','resourceOnlyDiagnostic'])assert.throws(()=>matches({...settings,[flag]:!settings[flag]}),/Preparation settings/);
 assert.throws(()=>matches({...settings,groupConcurrency:1}),/Preparation settings/);
 assert.throws(()=>matches({...settings,groupConcurrency:3}),/concurrency/);
 for(const [field,value]of Object.entries({port:5785,instance:'different-instance',container:'different-container',confirmation:'different-confirmation'}))assert.throws(()=>matches({...settings,endpoint:{...settings.endpoint,[field]:value}}),/Preparation settings/);
 const alias=path.join(folder,'same-bytes-other-path.jar');fs.copyFileSync(file,alias);assert.throws(()=>matches(settings,{...env,P12_D_H129_JAR:alias}),/Preparation settings/);
 assert.throws(()=>matches(settings,{...env,P12_D_H129_JAR:undefined}),/Explicit preflight historical/);
 for(const target of [file,proofFile,path.join(dist,'index.html')]){const bytes=fs.readFileSync(target);fs.appendFileSync(target,'changed');assert.throws(()=>matches(),/Preparation settings/);fs.writeFileSync(target,bytes)}
 fs.writeFileSync(path.join(dist,'extra.js'),'added file');assert.throws(()=>matches(),/Preparation settings/);
 assert.ok(!JSON.stringify(proof.preparation).includes('history proof'),'Only artifact identities and hashes are persisted');
});
test('preflight network observation reuses bounded20 batches complete exact identities and secret free projection',async()=>{
 const ids=Array.from({length:47},(_,i)=>'network-'+i),batches=[];
 const row=id=>({Id:id,Name:id,Driver:'bridge',Scope:'local',Internal:false,Attachable:false,Ingress:false,EnableIPv6:false,ConfigOnly:false,Project:'old',IPAM:{Driver:'default',Config:[{Subnet:'10.1.0.0/28'}]},Config:{Env:['SECRET=must-not-enter-proof']}});
 async function docker(args){if(args[0]!=='network')return '';if(args[1]==='ls')return ids.join('\n');assert.equal(args[1],'inspect');const batch=args.slice(4);assert.ok(batch.length<=20);assert.ok(!args[3].includes('Config.Env'));batches.push(batch);return batch.map(id=>JSON.stringify(row(id))).join('\n')}
 const observed=await snapshotPreflightNetworks(docker);assert.equal(batches.length,3);assert.deepEqual(batches.flat(),ids);assert.deepEqual(observed.map(x=>x.id).sort(),ids.toSorted());assert.ok(!JSON.stringify(observed).includes('SECRET'));assert.ok(observed.every(x=>x.ipam.config[0].subnet==='10.1.0.0/28'));
 await assert.rejects(()=>snapshotPreflightNetworks(async args=>args[0]!=='network'?'':args[1]==='ls'?ids.join('\n'):JSON.stringify(row(ids[0]))),/Incomplete or duplicate/);
});
test('preflight rejects missing historical runtime closure before commands and changed closure before evidence reuse',()=>{
 const runtimeFiles=require('../infra/p12-d/payload.cjs').runtimeFiles;
 function fixture(omitted){
  const folder=path.resolve(__dirname,'../.local-tools/preflight-history-'+crypto.randomUUID()),sourceRoot=path.join(folder,'R0'),jar=path.join(sourceRoot,'backend/target/old.jar'),dist=path.join(folder,'dist'),history=path.join(folder,'proof.json');
  fs.mkdirSync(path.dirname(jar),{recursive:true});fs.mkdirSync(dist);fs.writeFileSync(jar,'old jar');fs.writeFileSync(path.join(dist,'index.html'),'old dist');fs.writeFileSync(history,'history proof');
  for(const file of runtimeFiles.filter(file=>file!==omitted)){const target=path.join(sourceRoot,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,'immutable old '+file)}
  return {folder,sourceRoot,env:{P12_D_R0_JAR:jar,P12_D_R0_DIST:dist,P12_D_HISTORY_PROOF:history}};
 }
 const selection={selected:['release-recovery'],rootPhases:{selected:[]}},settings={groupConcurrency:2,offline:true,reverseGroups:true,resourceOnlyDiagnostic:false,endpoint:{port:15433,instance:'default',container:'',confirmation:'127.0.0.1:15433|default'}};
 for(const omitted of runtimeFiles){const missing=fixture(omitted);assert.throws(()=>snapshotPreparation(selection,settings,missing.env),/historical runtime/)}
 const missing=fixture(runtimeFiles[0]),request=path.join(missing.folder,'request.json');fs.writeFileSync(request,JSON.stringify({selection,settings}));
 const failed=cp.spawnSync(process.execPath,[path.join(__dirname,'Preflight.cjs'),'--run',request],{env:{...process.env,...missing.env},cwd:path.resolve(__dirname,'..'),encoding:'utf8',windowsHide:true,timeout:15000});
 assert.equal(failed.error,undefined);assert.notEqual(failed.status,0);const failure=JSON.parse(fs.readFileSync(path.join(missing.folder,'result.json'),'utf8'));assert.deepEqual(failure.commands,[]);assert.equal(failure.status,'failed');assert.match(failure.error,/historical runtime/);assert.ok(!fs.existsSync(path.join(missing.folder,'payload')));
 const complete=fixture(),preparation=snapshotPreparation(selection,settings,complete.env),proof={status:'passed',inputs:[{path:'same',sha256:'same'}],selection,preparation};
 assert.equal(preparation.historicalRuntime.sourceRoot,complete.sourceRoot);assert.deepEqual(preparation.historicalRuntime.files.map(file=>file.path),runtimeFiles);assert.ok(!fs.existsSync(path.join(complete.sourceRoot,'verification/PowerShellRuntime.ps1')),'Old runtime does not acquire current controller helpers');
 assert.doesNotThrow(()=>assertSameSource(proof,proof.inputs,selection,snapshotPreparation(selection,settings,complete.env)));
 fs.appendFileSync(path.join(complete.sourceRoot,runtimeFiles[0]),'changed');assert.throws(()=>assertSameSource(proof,proof.inputs,selection,snapshotPreparation(selection,settings,complete.env)),/Preparation settings/);
});
