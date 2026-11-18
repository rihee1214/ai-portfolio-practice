'use strict';
// Test-only current observations; no range changes, VM recovery or historical cache.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{performance}=require('node:perf_hooks');
const {runOwned}=require('../infra/p12-d/process-runner.cjs');
const tools={netsh:'C:/Windows/System32/netsh.exe',wsl:'C:/Windows/System32/wsl.exe'};
const clean=value=>String(value).replaceAll('\0','').replace(/^\uFEFF/,'').trim();
function prerequisiteFiles(root){return [tools.netsh,tools.wsl,path.join(root,'verification/Observe-ReleasePortRanges.ps1'),path.join(root,'verification/ReleasePortObservation.cjs'),path.join(root,'verification/PowerShellRuntime.ps1'),path.join(root,'infra/p12-d/process-runner.cjs')]}
function assertPrerequisites(root,exists=fs.existsSync){for(const file of prerequisiteFiles(root))assert.ok(exists(file),'Explicit release port observation prerequisite missing: '+file)}
function alreadyRunning(stdout){return clean(stdout).split(/\r?\n/).filter(line=>line.trim()==='docker-desktop').length===1}
function netshRange(stdout){
 const values=[...clean(stdout).matchAll(/^\s*[^:\r\n]+:\s*(\d+)\s*$/gm)].map(match=>Number(match[1]));
 assert.equal(values.length,2,'Windows dynamic range requires exact start/count');const [start,count]=values,end=start+count-1;
 assert.ok(Number.isSafeInteger(start)&&Number.isSafeInteger(count)&&start>=1&&count>0&&end<=65535,'Invalid Windows dynamic range');return {start,end};
}
const daemonProbe=`for p in /proc/[0-9]*/comm; do
  IFS= read -r n < "$p" || continue
  if [ "$n" = dockerd ]; then
    pid=\${p#/proc/}; pid=\${pid%/comm}
    printf 'DOCKERD_PID=%s\\n' "$pid"
    printf 'DOCKERD_NET='; readlink "/proc/$pid/ns/net"
  fi
done`;
const rangeProbe="printf 'NS='; readlink /proc/self/ns/net; printf 'RANGE='; cat /proc/sys/net/ipv4/ip_local_port_range";
async function observe({root,output,observationId,runtime,remainingMs,runner=runOwned,exists=fs.existsSync,clock=()=>performance.now()}){
 const target=path.resolve(output),allowed=path.resolve(root,'.local-tools')+path.sep;
 assert.ok(target.startsWith(allowed)&&fs.statSync(target).isDirectory(),'Fresh local observation directory required');
 const resultPath=path.join(target,'observation.json');assert.ok(!fs.existsSync(resultPath),'Existing observation cannot be reused');
 const start=clock(),deadline=start+remainingMs,result={schemaVersion:1,status:'failed',mode:runner===runOwned?'live-current':'guard-synthetic',observationId,runtime,started:new Date().toISOString(),remainingBudgetMs:remainingMs,commands:[],historicalCollisionOwner:'unresolved'};
 async function command(name,program,args){
  const remaining=deadline-clock();assert.ok(Number.isFinite(remaining)&&remaining>3000,'Shared port observation preparation budget exhausted');
  const timeout=Math.min(5000,Math.floor(remaining-3000)),prefix=path.join(target,String(result.commands.length+1).padStart(2,'0')+'-'+name);let value,error;
  try{value=await runner(program,args,{cwd:root,env:{...process.env},timeout,maxBuffer:1024*1024})}catch(failure){error=failure;value=failure.result||{out:'',err:failure.message,code:null,timedOut:false}}
  fs.writeFileSync(prefix+'.stdout.log',value.out||'',{flag:'wx'});fs.writeFileSync(prefix+'.stderr.log',value.err||'',{flag:'wx'});
  result.commands.push({name,program,args,limitMs:timeout,exitCode:value.code,timedOut:!!value.timedOut,stdout:prefix+'.stdout.log',stderr:prefix+'.stderr.log',error:error?.message});
  assert.ok(!error&&value.code===0&&!value.timedOut,'Release port observation child failed: '+name);return clean(value.out);
 }
 async function running(){const value=await command('running-docker-desktop',tools.wsl,['--list','--running','--quiet']);assert.ok(alreadyRunning(value),'docker-desktop must be already running; no VM startup or fallback')}
 try{
  assert.ok(typeof observationId==='string'&&/^[a-f\d-]{36}$/i.test(observationId),'Explicit observation identity required');
  assert.ok(Number.isInteger(remainingMs)&&remainingMs>0&&remainingMs<=60000,'Finite shared port observation budget required');
  assert.ok(runtime?.accepted===true&&runtime.version==='7.6.9'&&runtime.edition==='Core','Exact accepted observation runtime required');
  assertPrerequisites(root,exists);
  const ipv4=netshRange(await command('windows-ipv4-range',tools.netsh,['interface','ipv4','show','dynamicport','tcp']));
  const ipv6=netshRange(await command('windows-ipv6-range',tools.netsh,['interface','ipv6','show','dynamicport','tcp']));
  await running();const daemon=await command('dockerd-host-identity',tools.wsl,['-d','docker-desktop','--exec','sh','-c',daemonProbe]);
  const pids=[...daemon.matchAll(/^DOCKERD_PID=(\d+)\r?$/gm)].map(match=>Number(match[1])),namespaces=[...daemon.matchAll(/^DOCKERD_NET=(net:\[\d+\])\r?$/gm)].map(match=>match[1]);
  assert.ok(pids.length===1&&namespaces.length===1&&Number.isSafeInteger(pids[0])&&pids[0]>0,'Unique actual dockerd host daemon/namespace required');
  await running();const observed=await command('verified-dockerd-host-range',tools.wsl,['-d','docker-desktop','--exec','nsenter','--target',String(pids[0]),'--net','sh','-c',rangeProbe]);
  const actual=[...observed.matchAll(/^NS=(net:\[\d+\])\r?$/gm)],ranges=[...observed.matchAll(/^RANGE=(\d+)\s+(\d+)\r?$/gm)];
  assert.ok(actual.length===1&&actual[0][1]===namespaces[0],'Actual dockerd host namespace mismatch');
  assert.equal(ranges.length,1,'Docker host dynamic range required');const low=Number(ranges[0][1]),high=Number(ranges[0][2]);
  assert.ok(Number.isSafeInteger(low)&&Number.isSafeInteger(high)&&low>=1&&high<=65535&&low<=high,'Invalid Docker host dynamic range');
  assert.ok(clock()<=deadline,'Shared port observation budget exceeded');
  result.windows={ipv4,ipv6};result.dockerHost={alreadyRunning:true,pid:pids[0],namespace:namespaces[0],observedNamespace:actual[0][1],start:low,end:high};result.status='passed';return result;
 }catch(error){result.error=error.message;throw error}finally{result.elapsedMs=clock()-start;result.finished=new Date().toISOString();fs.writeFileSync(resultPath,JSON.stringify(result,null,2),{flag:'wx'})}
}
module.exports={observe,prerequisiteFiles,assertPrerequisites,alreadyRunning,tools};
if(require.main===module){const root=path.resolve(__dirname,'..'),[output,observationId,deadlineEpoch,runtime64]=process.argv.slice(2),remainingMs=Number(deadlineEpoch)-Date.now();let runtime;
 try{runtime=JSON.parse(Buffer.from(runtime64,'base64').toString('utf8'));}catch(error){console.error('Invalid observation runtime input: '+error.message);process.exitCode=1;}
 if(runtime)observe({root,output,observationId,runtime,remainingMs}).then(()=>console.log('Fresh host port ranges observed; historical collision owner unresolved')).catch(error=>{console.error(error.message);process.exitCode=1});
}
