'use strict';
// Same-run production bytes only. Each consumer keeps a fresh independent copy;
// development/historical artifacts and all test results remain separate.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {runOwned}=require('../infra/p12-d/process-runner.cjs');
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const hash=file=>sha(fs.readFileSync(file));
const check=(value,message)=>{if(!value)throw Error(message)};
function owned(root,value){
 check(path.isAbsolute(value||''),'Absolute artifact path required');
 const local=path.join(root,'.local-tools'),full=path.resolve(value),relative=path.relative(local,full);
 check(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative),'Owned artifact path required');
 for(let current=full;current!==path.dirname(current);current=path.dirname(current))if(fs.existsSync(current))check(!fs.lstatSync(current).isSymbolicLink(),'Linked artifact path refused');
 return full;
}
function inventory(directory){
 const rows=[];
 function walk(folder){for(const entry of fs.readdirSync(folder,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
  const file=path.join(folder,entry.name);check(!fs.lstatSync(file).isSymbolicLink(),'Linked artifact inventory path refused');
  if(entry.isDirectory())walk(file);else{check(entry.isFile(),'Regular artifact inventory required');rows.push({file:path.relative(directory,file).replaceAll('\\','/'),bytes:fs.statSync(file).size,sha256:hash(file)})}
 }}walk(directory);return rows.sort((a,b)=>a.file.localeCompare(b.file));
}
function context(root){
 root=fs.realpathSync(root);check(/^v24\./.test(process.version),'Declared Node24 production tool required');
 const files=[];
 for(const directory of ['frontend/src','frontend/vite','frontend/public'])if(fs.existsSync(path.join(root,directory)))for(const file of inventory(path.join(root,directory)))files.push({file:directory+'/'+file.file,bytes:file.bytes,sha256:file.sha256});
 for(const name of fs.readdirSync(path.join(root,'frontend')).sort())if((/\.(?:json|[cm]?js|ts|html|css)$/.test(name)||name.startsWith('.env'))&&fs.statSync(path.join(root,'frontend',name)).isFile()){const file=path.join(root,'frontend',name);files.push({file:'frontend/'+name,bytes:fs.statSync(file).size,sha256:hash(file)})}
 for(const relative of ['verification/ProductionBundle.cjs','infra/p12-d/process-runner.cjs'])files.push({file:relative,bytes:fs.statSync(path.join(root,relative)).size,sha256:hash(path.join(root,relative))});
 const toolFiles=['frontend/node_modules/typescript/bin/tsc','frontend/node_modules/typescript/package.json','frontend/node_modules/vite/bin/vite.js','frontend/node_modules/vite/package.json'];
 for(const optional of ['frontend/node_modules/@vitejs/plugin-react/package.json','frontend/node_modules/@vitejs/plugin-react/dist/index.js'])if(fs.existsSync(path.join(root,optional)))toolFiles.push(optional);
 const tools=[{file:process.execPath,sha256:hash(process.execPath),version:process.version},...toolFiles.sort().map(relative=>({file:path.join(root,relative),sha256:hash(path.join(root,relative))}))];
 const environment=Object.fromEntries(Object.keys(process.env).filter(key=>key.startsWith('VITE_')).sort().map(key=>[key,sha(process.env[key])]));
 return {root,mode:'production',nodeEnv:'production',configuration:'frontend/vite.config.ts',manifest:true,source:files.sort((a,b)=>a.file.localeCompare(b.file)),tools,environment};
}
async function prepare(root,directory){
 root=fs.realpathSync(root);directory=owned(root,directory);check(!fs.existsSync(directory),'Fresh production bundle required');
 const before=context(root);fs.mkdirSync(directory,{recursive:true});
 const env={...process.env,NODE_ENV:'production'},frontend=path.join(root,'frontend'),dist=path.join(directory,'dist');
 const commands=[{name:'typecheck',args:[path.join(frontend,'node_modules/typescript/bin/tsc'),'--noEmit']},{name:'production-build',args:[path.join(frontend,'node_modules/vite/bin/vite.js'),'build','--mode','production','--manifest','--outDir',dist]}];
 for(const command of commands){let result;try{result=await runOwned(process.execPath,command.args,{cwd:frontend,env,timeout:60000,maxBuffer:8*1024*1024});check(result.code===0,'Production '+command.name+' failed; evidence retained')}catch(error){result=error.result||result;throw error}finally{fs.writeFileSync(path.join(directory,command.name+'.log'),(result?.out||'')+(result?.err||''),'utf8')}}
 check(JSON.stringify(before)===JSON.stringify(context(root)),'Production source/tool context changed during build');
 const files=inventory(dist);check(files.some(file=>file.file==='index.html')&&files.some(file=>file.file==='.vite/manifest.json'),'Production artifact entry/manifest missing');
 const record={kind:'same-run-current-production-bundle',schemaVersion:1,context:before,contextSha256:sha(JSON.stringify(before)),dist,files,commands,testResultsReused:false};
 const manifest=path.join(directory,'manifest.json');fs.writeFileSync(manifest,JSON.stringify(record,null,2),{encoding:'utf8',flag:'wx'});validate(root,manifest);return {manifest,manifestSha256:hash(manifest),files:files.length};
}
function validate(root,manifest){
 root=fs.realpathSync(root);manifest=owned(root,manifest);const record=JSON.parse(fs.readFileSync(manifest,'utf8'));
 check(record.kind==='same-run-current-production-bundle'&&record.schemaVersion===1&&record.testResultsReused===false,'Current production artifact contract required');
 const current=context(root);check(JSON.stringify(record.context)===JSON.stringify(current)&&record.contextSha256===sha(JSON.stringify(current)),'Production source/config/tool/mode context drift');
 check(record.dist===path.join(path.dirname(manifest),'dist'),'Production artifact path mismatch');
 check(Array.isArray(record.files)&&record.files.length>0&&record.files.every(file=>!path.isAbsolute(file.file)&&!file.file.split(/[\\/]/).includes('..')),'Safe production artifact inventory path required');
 check(JSON.stringify(inventory(record.dist))===JSON.stringify(record.files),'Exact production artifact inventory/bytes mismatch');return record;
}
function copy(root,manifest,directory,receipt){
 root=fs.realpathSync(root);const record=validate(root,manifest);directory=owned(root,directory);receipt=owned(root,receipt);
 check(!fs.existsSync(directory)&&!fs.existsSync(receipt),'Fresh production consumer copy and receipt required');
 sameRun(manifest,directory);
 fs.mkdirSync(path.dirname(directory),{recursive:true});fs.cpSync(record.dist,directory,{recursive:true,errorOnExist:true,force:false});
 check(JSON.stringify(inventory(directory))===JSON.stringify(record.files),'Production copy inventory mismatch');
 const proof={kind:'same-run-production-copy',manifest:path.resolve(manifest),manifestSha256:hash(manifest),directory,files:record.files,contextSha256:record.contextSha256,testResultsReused:false};
 fs.writeFileSync(receipt,JSON.stringify(proof,null,2),{encoding:'utf8',flag:'wx'});return {status:'passed',directory,receipt};
}
function verifyCopy(root,manifest,directory,receipt){
 const record=validate(root,manifest),proof=JSON.parse(fs.readFileSync(owned(root,receipt),'utf8'));
 directory=owned(root,directory);sameRun(manifest,directory);check(proof.kind==='same-run-production-copy'&&proof.testResultsReused===false&&proof.manifest===path.resolve(manifest)&&proof.manifestSha256===hash(manifest)&&proof.directory===directory&&proof.contextSha256===record.contextSha256,'Production copy receipt mismatch');
 check(JSON.stringify(proof.files)===JSON.stringify(record.files)&&JSON.stringify(inventory(directory))===JSON.stringify(record.files),'Production copy inventory/bytes changed');return {status:'passed',directory,manifestSha256:proof.manifestSha256};
}
function sameRun(manifest,directory){
 const scope=path.dirname(path.dirname(path.resolve(manifest))),relative=path.relative(scope,directory);
 check((relative&&!relative.startsWith('..')&&!path.isAbsolute(relative))||path.dirname(directory)===path.dirname(scope),'Same-run production consumer path required');
}
module.exports={prepare,validate,copy,verifyCopy,context,inventory};
if(require.main===module)(async()=>{const [action,root,...args]=process.argv.slice(2);let result;if(action==='prepare')result=await prepare(root,...args);else if(action==='verify')result=validate(root,...args);else if(action==='copy')result=copy(root,...args);else if(action==='verify-copy')result=verifyCopy(root,...args);else throw Error('Unknown production bundle action');process.stdout.write(JSON.stringify(result)+'\n')})().catch(error=>{console.error(error.message);process.exitCode=1});
