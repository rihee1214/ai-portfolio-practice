'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {observe,prerequisiteFiles}=require('./ReleasePortObservation.cjs');
const root=path.resolve(__dirname,'..'),runtime={accepted:true,version:'7.6.9',edition:'Core',executable:require('./PowerShellRuntime.cjs').executable};
function fixture(changes={}){
 const output=path.join(root,'.local-tools/release-port-guard-'+crypto.randomUUID());fs.mkdirSync(output);const calls=[];
 const state={running:true,daemon:'DOCKERD_PID=192\nDOCKERD_NET=net:[4026532283]\n',identity:'net:[4026532283]',windows:'Start Port : 1024\nNumber of Ports : 13977\n',range:'32768 60999',...changes};
 const runner=async(program,args,options)=>{
  calls.push({program,args,timeout:options.timeout});
  if(state.error){throw Object.assign(new Error('controlled owned child failure'),{result:{out:'partial UTF8 한글',err:'controlled error',code:null,timedOut:true}})}
  if(path.basename(program).toLowerCase()==='netsh.exe')return {out:state.windows,err:'',code:state.code??0,timedOut:false};
  if(args[0]==='--list')return {out:state.running?'docker-desktop'.split('').map(c=>c+'\0').join('')+'\r\n':'Ubuntu\r\n',err:'',code:0,timedOut:false};
  if(args.includes('nsenter'))return {out:'NS='+state.identity+'\nRANGE='+state.range+'\n',err:'',code:0,timedOut:false};
  return {out:state.daemon,err:'',code:0,timedOut:false};
 };
 return {output,calls,state,options:{root,output,observationId:crypto.randomUUID(),runtime,remainingMs:60000,runner}};
}
const record=f=>JSON.parse(fs.readFileSync(path.join(f.output,'observation.json'),'utf8'));
test('release port observation refuses missing explicit prerequisites before native commands',async()=>{
 for(const missing of prerequisiteFiles(root)){
  const f=fixture();await assert.rejects(()=>observe({...f.options,exists:file=>file!==missing}),/prerequisite/);assert.equal(f.calls.length,0);assert.equal(record(f).status,'failed');
 }
});
test('release port observation does not execute or start a stopped Docker distribution',async()=>{
 const f=fixture({running:false});await assert.rejects(()=>observe(f.options),/already running/);
 assert.ok(!f.calls.some(c=>c.args[0]==='-d'));assert.ok(f.calls.every(c=>!c.args.includes('--shutdown')));assert.equal(record(f).status,'failed');
});
test('release port observation rejects ambiguous daemon and mismatched actual host namespace',async()=>{
 for(const changes of [{daemon:'DOCKERD_PID=192\nDOCKERD_NET=net:[1]\nDOCKERD_PID=193\nDOCKERD_NET=net:[2]\n'},{daemon:''},{identity:'net:[999]'}]){
  const f=fixture(changes);await assert.rejects(()=>observe(f.options),/daemon|namespace/);assert.equal(record(f).status,'failed');
 }
});
test('release port observation rejects malformed missing and out of bound ranges without defaults',async()=>{
 for(const changes of [{windows:''},{windows:'Start Port : 1024\nNumber of Ports : 0\n'},{windows:'Start Port : 65000\nNumber of Ports : 999\n'},{range:''},{range:'60999 32768'},{range:'32768 65536'}]){
  const f=fixture(changes);await assert.rejects(()=>observe(f.options),/range/);assert.equal(record(f).status,'failed');assert.equal(record(f).dockerHost,undefined);
 }
});
test('release port observation retains child failure timeout output and shared preparation budget refusal',async()=>{
 for(const changes of [{code:7},{error:true}]){
  const f=fixture(changes);await assert.rejects(()=>observe(f.options),/child/);const proof=record(f);assert.equal(proof.status,'failed');assert.equal(proof.commands.length,1);
  if(changes.error){assert.equal(proof.commands[0].timedOut,true);assert.match(fs.readFileSync(proof.commands[0].stdout,'utf8'),/partial UTF8 한글/)}
 }
 const expired=fixture();await assert.rejects(()=>observe({...expired.options,remainingMs:0}),/budget/);assert.equal(expired.calls.length,0);assert.equal(record(expired).status,'failed');
});
test('release port observation combines verified synthetic host records without repeated range queries',async()=>{
 const environmentDigest=()=>crypto.createHash('sha256').update(JSON.stringify(Object.entries(process.env).sort(([a],[b])=>a.localeCompare(b)))).digest('hex');
 const f=fixture(),environment=environmentDigest();await observe(f.options);const proof=record(f);
 assert.equal(proof.status,'passed');assert.equal(proof.mode,'guard-synthetic');assert.deepEqual(proof.windows.ipv4,{start:1024,end:15000});assert.deepEqual(proof.windows.ipv6,{start:1024,end:15000});
 assert.equal(proof.dockerHost.start,32768);assert.equal(proof.dockerHost.end,60999);assert.equal(proof.dockerHost.namespace,proof.dockerHost.observedNamespace);
 assert.equal(f.calls.filter(c=>c.args.includes('dynamicport')).length,2);assert.equal(f.calls.filter(c=>c.args.includes('nsenter')).length,1);
 for(let i=0;i<f.calls.length;i++)if(f.calls[i].args[0]==='-d')assert.deepEqual(f.calls[i-1].args,['--list','--running','--quiet']);
 assert.ok(f.calls.every(c=>c.timeout>0&&c.timeout<=5000));assert.equal(environmentDigest(),environment,'Parent environment values must remain identical');
});
test('release port observation preflight prerequisites are selected only and Java consumes fresh fixed pool',async()=>{
 const {releasePortPrerequisites,assertReleasePortAvailability}=require('./Preflight.cjs');let reads=0;
 assert.deepEqual(releasePortPrerequisites({selected:['inventory']},()=>{reads++;return false}),[]);assert.equal(reads,0);
 assert.throws(()=>releasePortPrerequisites({selected:['release-recovery']},()=>false),/prerequisite/);
 await assertReleasePortAvailability({selected:['inventory']},async()=>{throw Error('unselected branch executed')});
 const calls=[];await assert.rejects(()=>assertReleasePortAvailability({selected:['release-recovery']},async(name,program,args)=>{calls.push(args);return 'Ubuntu'}),/already running/);assert.equal(calls.length,1);assert.deepEqual(calls[0],['--list','--running','--quiet']);
 const available=[];await assertReleasePortAvailability({selected:['release-recovery']},async(name,program,args)=>{available.push(args);return args[0]==='--list'?'docker-desktop':'/usr/bin/nsenter'});assert.equal(available.length,2);assert.ok(available[1].includes('command -v nsenter'));assert.ok(!available.some(args=>args.join(' ').includes('ip_local_port_range')));
 const source=fs.readFileSync(path.join(root,'backend/src/test/java/com/example/marketplace/dbtest/ReleaseRecoveryIT.java'),'utf8');
 assert.ok(source.indexOf('preparePortPool();')<source.indexOf('prepareArtifacts();'));assert.ok(source.includes('ReleasePortPool.fromObservation'));assert.ok(source.includes('attempt<100'));assert.ok(source.includes('socket.bind('));assert.ok(source.includes('allocatedPorts.contains(candidate)'));
});
