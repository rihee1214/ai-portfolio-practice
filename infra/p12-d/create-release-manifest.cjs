'use strict';
// Input is an explicit release definition, never a deployment target or defaults.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {inventory,validateManifest}=require('./release-operations.cjs');
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function create(definition,output){
  if(fs.existsSync(output))throw new Error('Existing manifest output rejected');
  const manifest={...definition,artifacts:{...definition.artifacts}};
  for(const key of ['jar','nginxConfig','proxyConfig']){
    const file=definition.artifacts[key];manifest.artifacts[key]={path:file.path,sha256:digest(file.path)};
  }
  manifest.artifacts.dist={path:definition.artifacts.dist.path,files:inventory(definition.artifacts.dist.path)};
  manifest.artifacts.migrations=definition.artifacts.migrations.map(file=>({...file,sha256:digest(file.path)}));
  validateManifest(manifest);fs.writeFileSync(output,JSON.stringify(manifest,null,2),{flag:'wx'});return manifest;
}
module.exports={create};
if(require.main===module){try{create(JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,'')),path.resolve(process.argv[3]));}catch(e){process.stderr.write(e.message+'\n');process.exitCode=1;}}
