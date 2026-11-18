'use strict';
// Content-addressed local operational files. Source identity is provenance, not
// part of the content digest; labels, timestamps and target paths cannot make a
// different operational payload.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
// Historical release runtime bytes retain their original closure. New execution
// helpers belong to the current controller that actually launches host commands.
const runtimeFiles=[
  'infra/p12-c/Dockerfile','infra/p12-c/ProxyNetwork.ps1','infra/p12-c/Start-LocalProxy.ps1',
  'infra/p12-c/compose.yaml','infra/p12-c/nginx.conf.template','infra/p12-c/package-proxy.conf.template',
  'infra/postgres/init-app.sh'
].sort();
const controllerFiles=[...runtimeFiles,
  'verification/PowerShellRuntime.cjs','verification/PowerShellRuntime.ps1',
  'infra/p12-d/Invalidate-RestoredCredentials.sql','infra/p12-d/Invoke-ReleaseOperation.ps1',
  'infra/p12-d/New-ReleaseNetworks.ps1','infra/p12-d/Start-LocalReleaseProxy.ps1',
  'infra/p12-d/create-release-manifest.cjs','infra/p12-d/host-runtime.cjs',
  'infra/p12-d/payload.cjs','infra/p12-d/process-runner.cjs','infra/p12-d/release-operations.cjs'
].sort();
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function assert(ok,message){if(!ok)throw new Error(message);}
function safe(p,parent,exists=true){
  assert(typeof p==='string' && path.isAbsolute(p),'Absolute payload path required');
  const full=path.resolve(p),relative=path.relative(parent,full);
  assert(relative && !relative.startsWith('..') && !path.isAbsolute(relative),'Payload path escape rejected');
  for(let current=full;;current=path.dirname(current)){
    if(fs.existsSync(current))assert(!fs.lstatSync(current).isSymbolicLink(),'Linked payload path rejected');
    if(current===path.dirname(current))break;
  }
  if(exists)assert(fs.existsSync(full),'Payload file missing');return full;
}
function mandatory(kind){assert(['p12-c-runtime','p12-d-operations'].includes(kind),'Unknown operational payload kind');return kind==='p12-c-runtime'?runtimeFiles:controllerFiles;}
function files(root){
  const result=[];
  function visit(folder){for(const name of fs.readdirSync(folder).sort()){
    const p=path.join(folder,name),info=fs.lstatSync(p);assert(!info.isSymbolicLink(),'Linked payload entry rejected');
    if(info.isDirectory())visit(p);else{assert(info.isFile(),'Nonregular payload entry rejected');result.push({path:path.relative(root,p).replaceAll('\\','/'),size:info.size,sha256:sha(fs.readFileSync(p))});}
  }}visit(root);return result.sort((a,b)=>a.path.localeCompare(b.path));
}
function contentDigest(entries){return sha(JSON.stringify(entries.map(f=>({path:f.path,size:f.size,sha256:f.sha256})).sort((a,b)=>a.path.localeCompare(b.path))));}
function validatePayload(payload){
  assert(payload && /^[0-9a-f]{64}$/.test(payload.sha256 || ''),'Operational payload digest missing');
  assert(path.isAbsolute(payload.root || '') && path.basename(payload.root)===payload.sha256,'Content-addressed payload root required');
  safe(payload.root,path.parse(payload.root).root);
  const expected=mandatory(payload.kind);
  assert(Array.isArray(payload.files) && JSON.stringify(payload.files.map(f=>f.path).sort())===JSON.stringify(expected),'Operational dependency closure incomplete');
  const actual=files(payload.root);
  const declared=payload.files.map(f=>({path:f.path,size:f.size,sha256:f.sha256})).sort((a,b)=>a.path.localeCompare(b.path));
  assert(contentDigest(actual)===payload.sha256 && JSON.stringify(actual)===JSON.stringify(declared),'Operational payload hash mismatch');
  assert(/^[0-9a-f]{40}$/.test(payload.sourceHead || '') && /^[0-9a-f]{64}$/.test(payload.sourceInputsSha256 || ''),'Operational payload source identity missing');
  assert(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(payload.sourceReleaseId || ''),'Operational payload source release missing');
  if(payload.kind==='p12-d-operations')assert(payload.entrypoint==='infra/p12-d/release-operations.cjs','Operational entrypoint mismatch');
  else assert(!payload.entrypoint,'Historical runtime has no P12-D controller entrypoint');
  return payload;
}
function createPayload(definition){
  const {sourceRoot,outputRoot,kind,sourceReleaseId,sourceHead,sourceInputsSha256}=definition;
  assert(path.isAbsolute(sourceRoot || '') && path.isAbsolute(outputRoot || ''),'Explicit payload source/output roots required');
  safe(sourceRoot,path.parse(sourceRoot).root);
  safe(outputRoot,path.parse(outputRoot).root,false);
  const selected=mandatory(kind),entries=selected.map(relative=>{
    const p=safe(path.join(sourceRoot,relative),sourceRoot);assert(fs.statSync(p).isFile(),'Operational dependency is not a regular file');
    return {path:relative,size:fs.statSync(p).size,sha256:sha(fs.readFileSync(p))};
  }).sort((a,b)=>a.path.localeCompare(b.path));
  const digest=contentDigest(entries),root=path.join(outputRoot,digest);
  const result={root,files:entries,sha256:digest,kind,sourceReleaseId,sourceHead,sourceInputsSha256,...(kind==='p12-d-operations'?{entrypoint:'infra/p12-d/release-operations.cjs'}:{})};
  if(fs.existsSync(root))return validatePayload(result);
  fs.mkdirSync(root,{recursive:true});
  for(const entry of entries){const to=path.join(root,entry.path);fs.mkdirSync(path.dirname(to),{recursive:true});fs.copyFileSync(path.join(sourceRoot,entry.path),to,fs.constants.COPYFILE_EXCL);fs.chmodSync(to,0o444);}
  return validatePayload(result);
}
function fileEvidence(payload,relative){
  validatePayload(payload);const actual=safe(path.join(payload.root,relative),payload.root);
  const entry=payload.files.find(f=>f.path===relative);assert(entry,'File outside declared operational payload');
  assert(sha(fs.readFileSync(actual))===entry.sha256,'Loaded operational dependency changed');
  return {path:actual,relativePath:relative,sha256:entry.sha256,size:entry.size,sourceReleaseId:payload.sourceReleaseId,payloadSha256:payload.sha256};
}
module.exports={createPayload,validatePayload,fileEvidence,contentDigest,runtimeFiles,controllerFiles};
if(require.main===module){try{
  const definition=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
  const result=createPayload(definition);fs.writeFileSync(process.argv[3],JSON.stringify(result,null,2),{flag:'wx'});
  process.stdout.write(JSON.stringify({root:result.root,sha256:result.sha256})+'\n');
}catch(error){process.stderr.write(error.message+'\n');process.exitCode=1;}}
