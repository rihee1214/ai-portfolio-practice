'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const powershell=require('./PowerShellRuntime.cjs');
const root=path.resolve(__dirname,'..'),evidence=path.join(root,'.local-tools','perf2-node-gate-tests-'+crypto.randomUUID());fs.mkdirSync(evidence);
const names=[
  'public shipment readiness requires the tested order and its exact completed consumer',
  'public shipment readiness rejects unfinished jobs and absent or mismatched durable claim attempts',
  'public shipment readiness rejects the preserved failed BEFORE state and accepts its completed AFTER state',
  'public shipment readiness polling retains the original ten-second commerce deadline',
  'public shipment consumer polling stays inside commerce before the unchanged exact guest boundary',
];
const file=path.join(root,'frontend/tests/release-shipment-consumer.test.mjs');
const escape=value=>value.replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
const cases=()=>names.map(name=>`<testcase name="${escape(name)}" classname="test" file="${escape(file)}"/>`);
function check(label,xml){
  const report=path.join(evidence,label+'.xml');if(xml!==null)fs.writeFileSync(report,xml);
  const q=value=>"'"+value.replaceAll("'","''")+"'";
  const helper=path.join(__dirname,'FrontendNodeCoverage.ps1');
  const script=`if(Test-Path -LiteralPath ${q(helper)}){. ${q(helper)}}; Assert-FrontendNodeCoverage -Report ${q(report)} -Root ${q(root)} | ConvertTo-Json -Depth 8`;
  const r=cp.spawnSync(powershell.executable,powershell.commandArguments(script),{encoding:'utf8',windowsHide:true,shell:false,timeout:15000});
  fs.writeFileSync(path.join(evidence,label+'.log'),r.stdout+r.stderr);assert.equal(r.error,undefined);return r;
}
const xml=items=>`<?xml version="1.0" encoding="utf-8"?><testsuites>${items.join('')}</testsuites>`;
test('frontend Node coverage accepts the exact registered consumer file and five names once',()=>{
  const mapping=JSON.parse(fs.readFileSync(path.join(__dirname,'required-frontend-node.json'),'utf8'));
  assert.deepEqual(mapping,[{id:'PERF2-IR1-public-shipment-consumer-readiness',file:'frontend/tests/release-shipment-consumer.test.mjs',tests:names}]);
  const rootScript=fs.readFileSync(path.join(root,'verify.ps1'),'utf8');
  assert.equal((rootScript.match(/& node --test .*'tests\/\*\.test\.mjs'/g)||[]).length,1,'Existing Node test glob executes exactly once');
  assert.match(rootScript,/--test-reporter=junit .*--test-reporter-destination=stdout .*frontend-node-tests\.xml/);
  assert.match(rootScript,/Assert-FrontendNodeCoverage -Report "\$output\/frontend-node-tests\.xml" -Root \$PSScriptRoot/);
  assert.ok(!'frontend-node-tests.xml'.startsWith('TEST-'),'Native Node cases never inflate Vitest TEST XML count');
  const r=check('valid',xml([...cases(),'<testcase name="other existing assertion" classname="test" file="other.test.mjs"/>']));assert.equal(r.status,0,r.stderr);
  const result=JSON.parse(r.stdout);assert.equal(result.status,'passed');assert.equal(result.tests,6);assert.equal(result.requiredTests,5);
  const windows=check('normalized-file',xml(cases().map(item=>item.replace(escape(file),escape(file.toUpperCase().replaceAll('\\','/'))))));assert.equal(windows.status,0,windows.stderr);
});
test('frontend Node coverage rejects a missing required consumer assertion',()=>{
  const r=check('missing',xml(cases().slice(1)));assert.notEqual(r.status,0);assert.match(r.stderr,/Missing or duplicate required frontend Node assertion/);
});
test('frontend Node coverage rejects a duplicate required consumer assertion',()=>{
  const r=check('duplicate',xml([...cases(),cases()[0]]));assert.notEqual(r.status,0);assert.match(r.stderr,/Missing or duplicate required frontend Node assertion/);
  const foreign=check('duplicate-foreign-file',xml([...cases(),cases()[0].replace(escape(file),'other.test.mjs')]));assert.notEqual(foreign.status,0);assert.match(foreign.stderr,/Missing or duplicate required frontend Node assertion/);
});
test('frontend Node coverage rejects a required name attributed to a different file',()=>{
  const r=check('wrong-file',xml(cases().map(item=>item.replace(escape(file),'other.test.mjs'))));assert.notEqual(r.status,0);assert.match(r.stderr,/Missing or duplicate required frontend Node assertion/);
});
test('frontend Node coverage rejects failed error skipped and todo actual report cases',()=>{
  for(const status of ['failure','error','skipped']){
    const r=check(status,xml([...cases(),`<testcase name="other assertion"><${status}/></testcase>`]));assert.notEqual(r.status,0);assert.match(r.stderr,/Failed, errored or skipped frontend Node assertion/);
  }
  // Node represents TODO cases with <skipped type="todo"/> in its JUnit reporter.
  const r=check('todo',xml([...cases(),'<testcase name="todo"><skipped type="todo"/></testcase>']));assert.notEqual(r.status,0);assert.match(r.stderr,/Failed, errored or skipped frontend Node assertion/);
});
test('frontend Node coverage rejects missing empty malformed and foreign XML reports',()=>{
  for(const [label,report] of [['absent',null],['empty',''],['malformed','<testsuites><testcase>'],['foreign','<something/>'],['no-cases','<testsuites/>']]){
    const r=check(label,report);assert.notEqual(r.status,0,label);assert.match(r.stderr,/Missing or malformed frontend Node report/);
  }
});
test('frontend Node coverage rejects failed suite metadata despite passing case nodes',()=>{
  const r=check('suite-failure',`<testsuites><testsuite failures="1" errors="0" skipped="0">${cases().join('')}</testsuite></testsuites>`);assert.notEqual(r.status,0);assert.match(r.stderr,/Failed, errored or skipped frontend Node assertion/);
});
