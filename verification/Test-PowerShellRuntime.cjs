'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const powershell=require('./PowerShellRuntime.cjs');
const root=path.resolve(__dirname,'..');
const evidence=path.join(root,'.local-tools','pwsh769-runtime-tests-'+crypto.randomUUID());fs.mkdirSync(evidence);
const env={...process.env,VERIFY_POWERSHELL_EVIDENCE:path.join(evidence,'processes')};
function run(script,extra={}){
  const r=cp.spawnSync(powershell.executable,powershell.commandArguments(script),{encoding:'utf8',windowsHide:true,shell:false,timeout:15000,env,...extra});
  assert.equal(r.error,undefined);return r;
}
test('runtime rejects PATH Windows PowerShell alternate paths and missing executable declarations',()=>{
  for(const value of [undefined,'pwsh','powershell.exe','C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe','C:/other/pwsh.exe'])assert.throws(()=>powershell.assertExecutable(value),/Exact explicit/);
  assert.equal(powershell.assertExecutable(powershell.executable),powershell.executable);
  assert.throws(()=>powershell.checkedArguments(['-Version']),/declare/);
});
test('actual child records exact 7.6.9 Core executable and emits UTF8 without compatibility session',()=>{
  const r=run('Assert-VerificationPowerShellRuntime | ConvertTo-Json -Depth 5');assert.equal(r.status,0,r.stderr);
  const actual=JSON.parse(r.stdout);assert.equal(actual.version,'7.6.9');assert.equal(actual.edition,'Core');assert.equal(actual.accepted,true);assert.equal(actual.compatibilitySessions,0);
  assert.equal(path.resolve(actual.executable).toLowerCase(),path.resolve(powershell.executable).toLowerCase());
  fs.writeFileSync(path.join(evidence,'observed-child.json'),r.stdout);
});
test('child invocation preserves nonASCII quoted empty and long arguments without shell evaluation',()=>{
  const file=path.join(evidence,"한글 인수 ' probe.ps1");
  fs.writeFileSync(file,'param([string]$Value,[string]$Empty,[string]$Long)\n@{value=$Value;empty=$Empty;long=$Long}|ConvertTo-Json -Compress','utf8');
  const value='한글 "quoted" $HOME & literal `tick` \\ end',long='길'.repeat(10000);
  const r=cp.spawnSync(powershell.executable,powershell.checkedArguments(['-NoProfile','-File',file,'-Value',value,'-Empty','','-Long',long]),{encoding:'utf8',shell:false,windowsHide:true,timeout:15000,env});
  assert.equal(r.error,undefined);assert.equal(r.status,0,r.stderr);const actual=JSON.parse(r.stdout);assert.equal(actual.value,value);assert.equal(actual.empty,'');assert.equal(actual.long.length,long.length);assert.ok(actual.long===long,'Complete long argument bytes');
});
test('runtime refuses older and prerelease versions before target command',()=>{
  for(const version of ['7.6.8','7.6.9-preview.1']){
    const r=run(`$PSVersionTable.PSVersion='${version}'; Assert-VerificationPowerShellRuntime; 'TARGET-RAN'`);
    assert.notEqual(r.status,0);assert.doesNotMatch(r.stdout,/TARGET-RAN/);assert.match(r.stderr,/Exact PowerShell 7.6.9/);
  }
});
test('actual legacy 5.1 process is rejected before target command',()=>{
  const helper=path.join(__dirname,'PowerShellRuntime.ps1').replaceAll("'","''");
  const r=cp.spawnSync('C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe',['-NoProfile','-NonInteractive','-Command',`. '${helper}'; Assert-VerificationPowerShellRuntime; 'TARGET-RAN'`],{encoding:'utf8',windowsHide:true,shell:false,timeout:15000,env});
  assert.equal(r.error,undefined);assert.notEqual(r.status,0);assert.doesNotMatch(r.stdout,/TARGET-RAN/);assert.match(r.stderr,/Exact PowerShell 7.6.9/);
});
test('existing Windows compatibility session blocks child work',()=>{
  const r=run("function Get-PSSession { [pscustomobject]@{Name='WinPSCompatSession'} }; Assert-VerificationPowerShellRuntime; 'TARGET-RAN'");
  assert.notEqual(r.status,0);assert.doesNotMatch(r.stdout,/TARGET-RAN/);assert.match(r.stderr,/compatibility sessions forbidden/);
});
test('native network module imports in 7.6.9 without spawning compatibility session',()=>{
  const r=run('Import-VerificationNetworkModule; @{runtime=(Assert-VerificationPowerShellRuntime);module=(Get-Module NetTCPIP).Path;commands=@((Get-Command Get-NetRoute,Get-NetTCPConnection).ModuleName)}|ConvertTo-Json -Depth 6');
  assert.equal(r.status,0,r.stderr);const actual=JSON.parse(r.stdout);assert.equal(actual.runtime.compatibilitySessions,0);assert.deepEqual(actual.commands,['NetTCPIP','NetTCPIP']);assert.match(actual.module,/NetTCPIP/);
  fs.writeFileSync(path.join(evidence,'native-module.json'),r.stdout);
});
test('artifact build runner preserves partial UTF8 output and terminates owned PowerShell descendants',async()=>{
  const {runBuildCommand}=require('../infra/p12-e/prepare-artifacts.cjs');
  const pidFile=path.join(evidence,'build-descendant.json'),leaf=path.join(evidence,'build-leaf.cjs');
  fs.writeFileSync(leaf,"require('fs').writeFileSync(process.argv[2],JSON.stringify({pid:process.pid}));console.log('부분 출력');setInterval(()=>{},1000)",'utf8');
  const q=value=>"'"+value.replaceAll("'","''")+"'";
  const script=`& ${q(process.execPath)} ${q(leaf)} ${q(pidFile)}`;
  const started=Date.now();
  await assert.rejects(runBuildCommand({name:'owned-build',program:powershell.executable,args:powershell.commandArguments(script),cwd:root,env,output:evidence,timeout:2000}),/build evidence retained/);
  assert.ok(Date.now()-started<7000,'Finite child termination and output drain');
  assert.match(fs.readFileSync(path.join(evidence,'owned-build.log'),'utf8'),/부분 출력/);
  const pid=JSON.parse(fs.readFileSync(pidFile,'utf8')).pid;
  const observed=await require('../infra/p12-d/host-runtime.cjs').identity(pid,powershell.executable);
  assert.equal(observed,null,'Timed-out build descendant must be absent');
  await assert.rejects(runBuildCommand({name:'forbidden-expanded-timeout',program:powershell.executable,args:[],cwd:root,env,output:evidence,timeout:60001}),/must not exceed/);
});
