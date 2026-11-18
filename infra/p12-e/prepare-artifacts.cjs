// Optional fresh build producer. All tool/cache paths are operator-declared; no installation/download.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const powershell=require('../../verification/PowerShellRuntime.cjs');
const {runOwned}=require('../p12-d/process-runner.cjs');
async function runBuildCommand({name,program,args,cwd,env,output,timeout=60000}){
  if(!Number.isInteger(timeout)||timeout<=0||timeout>60000)throw Error('Finite build command limit must not exceed 60000ms');
  let result;
  try {result=await runOwned(program,args,{cwd,env,timeout,maxBuffer:8*1024*1024});}
  catch(error){result=error.result;throw Error(name+' failed; build evidence retained',{cause:error});}
  finally {fs.writeFileSync(path.join(output,name+'.log'),(result?.out||'')+(result?.err||''));}
  if(result.code!==0)throw Error(name+' failed; build evidence retained');
}
module.exports={runBuildCommand};
async function main(){
const root=path.resolve(__dirname,'../..'),o=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
for(const k of ['output','jdk','node','npmCli','mavenHome','mavenUserHome'])if(!path.isAbsolute(o[k]||''))throw Error('Explicit absolute build prerequisite: '+k);
if(fs.existsSync(o.output))throw Error('Fresh build output required');fs.mkdirSync(o.output,{recursive:true});
const env={...process.env,JAVA_HOME:o.jdk,MAVEN_HOME:o.mavenHome,MAVEN_USER_HOME:o.mavenUserHome,P12_E_MAVEN_REPOSITORY:path.join(o.mavenUserHome,'repository'),P12_E_MAVEN_CMD:path.join(o.mavenHome,'bin/mvn.cmd'),PATH:path.join(o.jdk,'bin')+path.delimiter+path.dirname(o.node)+path.delimiter+process.env.PATH};
env.VERIFY_POWERSHELL_EVIDENCE=path.join(o.output,'powershell-processes');
const run=(name,program,args,cwd)=>runBuildCommand({name,program,args,cwd,env,output:o.output});
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function files(p,base=p){return fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(p,e.name),base):[{file:path.relative(base,path.join(p,e.name)).replaceAll('\\','/'),sha256:sha(path.join(p,e.name))}])}
const sourceHead=cp.execFileSync('git',['-c','safe.directory='+root.replaceAll('\\','/'),'rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
const buildInputs=['backend/pom.xml','backend/.mvn/wrapper/maven-wrapper.properties','frontend/package.json','frontend/package-lock.json','frontend/vite.config.ts','frontend/tsconfig.json','frontend/index.html'];
const sources=()=>files(path.join(root,'backend/src/main'),root).concat(files(path.join(root,'frontend/src'),root),buildInputs.map(file=>({file,sha256:sha(path.join(root,file))})));
const sourceBefore=sources();
const backendArguments=powershell.commandArguments('& $env:P12_E_MAVEN_CMD -o "-Dmaven.repo.local=$env:P12_E_MAVEN_REPOSITORY" -DskipTests package; exit $LASTEXITCODE');
await run('backend-build',powershell.executable,backendArguments,path.join(root,'backend'));
await run('frontend-build',o.node,[o.npmCli,'run','build'],path.join(root,'frontend'));
const sourceAfter=sources();
if(JSON.stringify(sourceBefore)!==JSON.stringify(sourceAfter))throw Error('Source changed during build');
fs.copyFileSync(path.join(root,'backend/target/marketplace-api-0.0.1-SNAPSHOT.jar'),path.join(o.output,'marketplace.jar'));
fs.cpSync(path.join(root,'frontend/dist'),path.join(o.output,'dist'),{recursive:true});
const metadata={sourceHead,sources:sourceAfter,sourceUnchangedDuringBuild:true,testExecution:false,commands:[{program:powershell.executable,args:backendArguments,cwd:'backend',mavenCommand:env.P12_E_MAVEN_CMD,mavenRepository:env.P12_E_MAVEN_REPOSITORY},{program:o.node,args:[o.npmCli,'run','build'],cwd:'frontend'}],tools:o};
const hash=sha(path.join(o.output,'marketplace.jar'));
fs.writeFileSync(path.join(o.output,'package-proof.json'),JSON.stringify({...metadata,apiJarSha256:hash,workerJarSha256:hash},null,2));
fs.writeFileSync(path.join(o.output,'package-dist.json'),JSON.stringify({mode:'production',files:files(path.join(o.output,'dist')),sourceHead:metadata.sourceHead},null,2));
}
if(require.main===module)main().catch(error=>{console.error(error.message);process.exitCode=1});
