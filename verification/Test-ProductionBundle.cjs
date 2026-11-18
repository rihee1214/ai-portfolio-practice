'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {prepare,validate,copy,verifyCopy}=require('./ProductionBundle.cjs');
const workspace=path.resolve(__dirname,'..');
function fixture(){
 const root=path.join(workspace,'.local-tools','production-bundle-guard-'+crypto.randomUUID());
 const write=(file,text)=>{fs.mkdirSync(path.dirname(path.join(root,file)),{recursive:true});fs.writeFileSync(path.join(root,file),text,'utf8')};
 for(const file of ['src/main.ts','vite/apiProxy.ts','vite.config.ts','tsconfig.json','index.html','package.json','package-lock.json'])write('frontend/'+file,'fixture '+file);
 write('frontend/node_modules/typescript/bin/tsc','process.exit(0)');
 write('frontend/node_modules/vite/bin/vite.js',`const fs=require('fs'),path=require('path');const out=process.argv[process.argv.indexOf('--outDir')+1];fs.mkdirSync(path.join(out,'assets'),{recursive:true});fs.mkdirSync(path.join(out,'.vite'));for(const [file,text]of [['index.html','current html'],['assets/index-current.js','current js'],['.vite/manifest.json','{"index.html":{"isEntry":true,"file":"assets/index-current.js"}}']])fs.writeFileSync(path.join(out,file),text)`);
 for(const p of ['typescript','vite'])write('frontend/node_modules/'+p+'/package.json',JSON.stringify({name:p,version:'fixture'}));
 write('verification/ProductionBundle.cjs','fixture producer');write('infra/p12-d/process-runner.cjs','fixture runner');
 const bundle=path.join(root,'.local-tools/bundle'),manifest=path.join(bundle,'manifest.json'),target=path.join(root,'.local-tools/consumer'),receipt=path.join(root,'.local-tools/copy.json');
 return {root,bundle,manifest,target,receipt,write};
}
test('shared production producer and fresh consumers retain exact source tool mode and bytes',async()=>{
 const f=fixture();await prepare(f.root,f.bundle);const proof=validate(f.root,f.manifest);assert.equal(proof.context.mode,'production');assert.equal(proof.context.nodeEnv,'production');assert.equal(proof.testResultsReused,false);
 copy(f.root,f.manifest,f.target,f.receipt);assert.equal(verifyCopy(f.root,f.manifest,f.target,f.receipt).status,'passed');assert.equal(fs.readFileSync(path.join(f.target,'assets/index-current.js'),'utf8'),'current js');
});
test('shared production consumers refuse source config tool and mode drift',async()=>{
 for(const field of ['source','config','tool','mode']){
  const f=fixture();await prepare(f.root,f.bundle);
  if(field==='source')f.write('frontend/src/main.ts','changed');
  if(field==='config')f.write('frontend/vite.config.ts','changed');
  if(field==='tool')f.write('frontend/node_modules/vite/bin/vite.js','changed');
  if(field==='mode'){const m=JSON.parse(fs.readFileSync(f.manifest));m.context.mode='development';fs.writeFileSync(f.manifest,JSON.stringify(m));}
  assert.throws(()=>copy(f.root,f.manifest,f.target,f.receipt),/context|production/);assert.equal(fs.existsSync(f.target),false);
 }
});
test('shared production consumers reject missing extra changed and traversing artifact inventory',async()=>{
 for(const field of ['missing','extra','changed','traversal']){
  const f=fixture();await prepare(f.root,f.bundle);
  if(field==='missing')fs.renameSync(path.join(f.bundle,'dist/index.html'),path.join(f.bundle,'dist/index.absent'));
  if(field==='extra')fs.writeFileSync(path.join(f.bundle,'dist/old.js'),'old');
  if(field==='changed')fs.appendFileSync(path.join(f.bundle,'dist/index.html'),'changed');
  if(field==='traversal'){const m=JSON.parse(fs.readFileSync(f.manifest));m.files[0].file='../outside';fs.writeFileSync(f.manifest,JSON.stringify(m));}
  assert.throws(()=>copy(f.root,f.manifest,f.target,f.receipt),/inventory|artifact|path/);assert.equal(fs.existsSync(f.target),false);
 }
});
test('shared production copies are independent and existing output or changed receipt cannot pass',async()=>{
 const f=fixture();await prepare(f.root,f.bundle);copy(f.root,f.manifest,f.target,f.receipt);
 assert.throws(()=>copy(f.root,f.manifest,f.target,f.receipt),/fresh|exists/i);
 fs.appendFileSync(path.join(f.target,'assets/index-current.js'),'changed');assert.equal(validate(f.root,f.manifest).context.mode,'production');assert.throws(()=>verifyCopy(f.root,f.manifest,f.target,f.receipt),/copy|inventory/);
 const second=path.join(f.root,'.local-tools/second'),receipt=path.join(f.root,'.local-tools/second.json');copy(f.root,f.manifest,second,receipt);const r=JSON.parse(fs.readFileSync(receipt));r.manifestSha256='0'.repeat(64);fs.writeFileSync(receipt,JSON.stringify(r));assert.throws(()=>verifyCopy(f.root,f.manifest,second,receipt),/receipt/);
});
test('shared production bytes cannot be imported into another run even with identical source',async()=>{
 const f=fixture(),bundle=path.join(f.root,'.local-tools/run-A/db/current-production');await prepare(f.root,bundle);
 const target=path.join(f.root,'.local-tools/run-B/db/groups/package-runtime/browser-app');
 assert.throws(()=>copy(f.root,path.join(bundle,'manifest.json'),target,f.receipt),/Same-run/);assert.equal(fs.existsSync(target),false);
});
