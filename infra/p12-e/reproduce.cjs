// Explicit local prerequisites only; never downloads, reuses an existing project, or deletes resources.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process'),net=require('node:net');
const root=path.resolve(__dirname,'../..');
const {snapshotDockerResources,assertPreserved}=require('./preservation.cjs');
const {preflightCachedImages,buildFromCachedImage,assertBuiltImageBase}=require('./cached-images.cjs');
const powershell=require('../../verification/PowerShellRuntime.cjs');
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const roleInspectTemplate='{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},"User":{{json .Config.User}},"Mounts":{{json .Mounts}},"Networks":{{json .NetworkSettings.Networks}},"PortBindings":{{json .HostConfig.PortBindings}},"State":{"Status":{{json .State.Status}},"StartedAt":{{json .State.StartedAt}}}}';
function freshDirectory(p){if(fs.existsSync(p))throw Error('Evidence directory already exists');fs.mkdirSync(p,{recursive:true});}
function validate(o){for(const k of ['evidence','jar','dist','jdk','node','browsers','jarProvenance','distProvenance'])if(!o[k]||!path.isAbsolute(o[k]))throw Error('Explicit absolute prerequisite required: '+k);
  if(!fs.existsSync(path.join(o.dist,'index.html'))||!fs.existsSync(o.jar))throw Error('Declared JAR/dist absent');}
function range(cidr){const [ip,bits]=cidr.split('/'),v=ip.split('.').reduce((n,p)=>n*256+Number(p),0),size=2**(32-Number(bits));return [Math.floor(v/size)*size,Math.floor(v/size)*size+size-1]}
function subnets(excluded,count=2){const blocked=excluded.map(range),chosen=[];for(const block of ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']){const [lo,hi]=range(block);for(let p=lo;p+15<=hi;p+=16){const hit=blocked.find(([a,b])=>p<=b&&p+15>=a);if(hit){p=Math.floor(hit[1]/16)*16;continue}chosen.push([24,16,8,0].map(b=>Math.floor(p/2**b)%256).join('.')+'/28');blocked.push([p,p+15]);if(chosen.length===count)return chosen}}throw Error('No nonoverlapping private subnet available')}
module.exports={validate,freshDirectory,subnets,roleInspectTemplate};
if(require.main===module)main().catch(e=>{console.error(e.message);process.exitCode=1});
async function main(){
 const o=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));validate(o);freshDirectory(o.evidence);
 const out=o.evidence,id='p12e-'+crypto.randomUUID().replaceAll('-','').slice(0,14),project=id;
 let n=0,started=false,passed=false,beforeResources,phaseDeadline;const records=[];
 const write=(p,v)=>fs.writeFileSync(path.join(out,p),typeof v==='string'?v:JSON.stringify(v,null,2),{flag:'wx'});
 function run(exe,args,ms=30000,opts={}){if(phaseDeadline)ms=Math.max(1,Math.min(ms,phaseDeadline-Date.now()));const at=Date.now(),r=cp.spawnSync(exe,args,{cwd:root,encoding:'utf8',timeout:ms,windowsHide:true,maxBuffer:16*1024*1024,...opts});
  records.push({exe:path.basename(exe),args:args.map(x=>String(x).includes('PASSWORD')?'[private environment reference]':x),startedAt:at,elapsedMs:Date.now()-at,limitMs:ms,status:r.status,error:r.error?.code});
  fs.writeFileSync(path.join(out,`${++n}-${path.basename(exe)}.log`),(r.stdout||'')+(r.stderr||''));
  if(r.status!==0)throw Error(`Command ${n} failed (${r.status??r.error?.code}); evidence retained`);return r.stdout.trim();}
 const docker=(...a)=>run('docker',a);
 const env={...process.env,PACKAGE_PROJECT:project,PACKAGE_DB_NAME:'e_'+id.replaceAll('-','_'),PACKAGE_DB_USER:'e_app',PACKAGE_DB_PASSWORD:crypto.randomUUID(),PACKAGE_DB_ADMIN_PASSWORD:crypto.randomUUID(),PACKAGE_MEDIA:path.join(out,'unused-media'),PACKAGE_DIST:path.join(out,'dist'),PACKAGE_CONFIG:path.join(out,'config'),PACKAGE_SECRETS:path.join(out,'secrets'),E_BOOTSTRAP:path.join(out,'bootstrap'),E_ARTIFACT:path.join(out,'artifact'),E_OUTPUT:out};
 const composeArgs=(a)=>['compose','-f',path.join(root,'infra/p12-c/compose.yaml'),'-f',path.join(root,'infra/p12-e/compose.override.yaml'),'-f',path.join(out,'networks.json'),...a];
 const compose=(...a)=>run('docker',composeArgs(a),30000,{env});
 try {
  const sourceDockerfile=fs.readFileSync(path.join(root,'infra/p12-c/Dockerfile'),'utf8');
  const inspectImage=reference=>JSON.parse(docker('image','inspect','--format','{"Id":{{json .Id}},"RepoDigests":{{json .RepoDigests}},"RootFS":{"Layers":{{json .RootFS.Layers}}}}',reference));
  const cachedImages=preflightCachedImages({dockerfile:sourceDockerfile,compose:fs.readFileSync(path.join(root,'infra/p12-c/compose.yaml'),'utf8'),inspect:inspectImage});
  write('cached-images.json',cachedImages);
  const owner=cp.execFileSync('whoami',[],{encoding:'utf8',windowsHide:true,timeout:5000}).trim();run('icacls',[out,'/inheritance:r','/grant:r',owner+':(OI)(CI)F']);write('privacy.json',{ownerOnlyRoot:true,childrenInherit:true,privateFiles:['accounts.private.json','access.private.json','secrets/','*.private.png'],rawEvidenceNotForPublication:true});
  fs.mkdirSync(path.join(out,'proof-source'));const source=fs.readdirSync(__dirname).filter(f=>fs.statSync(path.join(__dirname,f)).isFile()).map(file=>{fs.copyFileSync(path.join(__dirname,file),path.join(out,'proof-source',file));return {file,sha256:sha(path.join(__dirname,file))}});write('proof-source.json',source);
  write('powershell-helper-source.json',['verification/PowerShellRuntime.cjs','verification/PowerShellRuntime.ps1'].map(file=>({file,sha256:sha(path.join(root,file))})));
  beforeResources=await snapshotDockerResources(args=>docker(...args));write('resources-before.json',beforeResources);
  const provenance=JSON.parse(fs.readFileSync(o.jarProvenance,'utf8')),distProof=JSON.parse(fs.readFileSync(o.distProvenance,'utf8'));
  if(provenance.apiJarSha256!==sha(o.jar)||provenance.workerJarSha256!==sha(o.jar))throw Error('JAR differs from accepted provenance');
  for(const file of distProof.files)if(sha(path.join(o.dist,file.file))!==file.sha256)throw Error('Dist differs from accepted provenance');
  write('jar-provenance.json',provenance);write('dist-provenance.json',distProof);
  write('prerequisites.json',{...o,sourceHead:run('git',['rev-parse','HEAD']),project,jarSha256:sha(o.jar),inputs:['explicit JAR','explicit production dist','JDK21','Node24+','installed frontend lockfile dependencies','Chromium cache','Docker Compose 2.24+','cached pinned postgres/nginx/Temurin images']});
  write('existing-before.json',{containers:docker('ps','-a','--no-trunc','--format','{{.ID}} {{.Names}} {{.Status}}'),volumes:docker('volume','ls','--format','{{.Name}}'),networks:docker('network','ls','--no-trunc','--format','{{.ID}} {{.Name}}')});
  const networkIds=docker('network','ls','-q').split(/\s+/);const networks=JSON.parse(docker('network','inspect',...networkIds));
  const routeProof=JSON.parse(run(powershell.executable,powershell.commandArguments('Import-VerificationNetworkModule; $routes=@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix); $runtime=Assert-VerificationPowerShellRuntime; @{runtime=$runtime;routes=$routes;nativeModule=(Get-Module NetTCPIP).Path}|ConvertTo-Json -Depth 6'),15000,{env:{...env,VERIFY_POWERSHELL_EVIDENCE:path.join(out,'powershell-processes')}}));
  write('powershell-network.json',routeProof);const hostRoutes=routeProof.routes;if(!Array.isArray(hostRoutes)||!hostRoutes.length)throw Error('Native IPv4 host route inventory missing');
  const excluded=[...hostRoutes.filter(x=>x!=='0.0.0.0/0'),...networks.flatMap(x=>(x.IPAM.Config||[]).map(c=>c.Subnet).filter(Boolean))];const selected=subnets(excluded);write('network-inventory.json',{hostRoutes,networks:networks.map(x=>({Id:x.Id,Name:x.Name,IPAM:x.IPAM})),excluded,selected});
  write('networks.json',{networks:{edge:{ipam:{config:[{subnet:selected[0]}]}},data:{ipam:{config:[{subnet:selected[1]}]}}}});
  if(JSON.parse(docker('ps','-a','--filter','label=com.docker.compose.project='+project,'--format','json')||'null'))throw Error('Project collision');
  if(docker('volume','ls','--filter','label=com.docker.compose.project='+project,'-q'))throw Error('Volume collision');
  for(const p of ['artifact','bootstrap','config','secrets','image'])fs.mkdirSync(path.join(out,p));
  fs.cpSync(o.dist,env.PACKAGE_DIST,{recursive:true});fs.copyFileSync(o.jar,path.join(out,'image/marketplace.jar'));fs.copyFileSync(path.join(root,'infra/p12-c/Dockerfile'),path.join(out,'image/Dockerfile'));
  run(path.join(o.jdk,'bin/jar.exe'),['xf',o.jar],30000,{cwd:env.E_ARTIFACT});
  run(path.join(o.jdk,'bin/javac.exe'),['-encoding','UTF-8','-cp',path.join(env.E_ARTIFACT,'BOOT-INF/classes')+';'+path.join(env.E_ARTIFACT,'BOOT-INF/lib/*'),'-d',env.E_BOOTSTRAP,path.join(__dirname,'PrepareAccounts.java')]);
  buildFromCachedImage({dockerfile:sourceDockerfile,preflight:cachedImages,inspect:inspectImage,build:request=>{fs.writeFileSync(path.join(out,'image/Dockerfile'),request.dockerfile);write('image-prerequisites.json',{...cachedImages,build:request.record});run('docker',[...request.arguments,'-t',id,path.join(out,'image')],60000,{env:{...env,...request.environment}})}});
  assertBuiltImageBase(cachedImages,inspectImage(id));
  env.PACKAGE_APP_IMAGE=docker('image','inspect','--format','{{.Id}}',id);write('application-image.json',JSON.parse(docker('image','inspect',env.PACKAGE_APP_IMAGE)).map(x=>({Id:x.Id,RootFS:x.RootFS,User:x.Config.User})));
  const port=await new Promise((resolve,reject)=>{const s=net.createServer();s.on('error',reject);s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})});
  env.PACKAGE_HTTPS_PORT=String(port);env.PACKAGE_PUBLIC_ORIGIN='https://localhost';
  const gate=crypto.randomUUID();write('access.private.json',{baseURL:'https://localhost:'+port,gateUser:'reviewer',gatePassword:gate,project});
  const pg='postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6';
  // Certificate/password generation has no network and exposes no credential in command arguments.
  run('docker',['run','--name',id+'-tls','--network','none','--mount',`type=bind,source=${env.PACKAGE_SECRETS},target=/secrets`,'--entrypoint','sh',env.PACKAGE_APP_IMAGE,'-c','openssl req -x509 -newkey rsa:2048 -nodes -keyout /secrets/tls.key -out /secrets/tls.crt -days 2 -subj /CN=localhost -addext subjectAltName=DNS:localhost; chmod 644 /secrets/tls.key'],30000);
  // Same synthetic local gate encoding as the existing C proxy producer.
  const hash='{SHA}'+crypto.createHash('sha1').update(gate).digest('base64');fs.writeFileSync(path.join(env.PACKAGE_SECRETS,'reviewers'),'reviewer:'+hash+'\n');
  for(const file of ['nginx.conf','package-proxy.conf'])fs.writeFileSync(path.join(env.PACKAGE_CONFIG,file),fs.readFileSync(path.join(root,'infra/p12-c/'+file+'.template'),'utf8').replaceAll('__HOST__','localhost').replaceAll('__UPSTREAM__','api:8080').replaceAll('__PORT__',String(port)));
  started=true;compose('up','-d','postgres');
  const pgid=compose('ps','-q','postgres');
  await readiness('database readiness',()=>{try{return docker('exec',pgid,'pg_isready','-U','postgres','-d',env.PACKAGE_DB_NAME).includes('accepting connections')}catch{return false}},30000);
  run('docker',composeArgs(['run','--name',id+'-prepare','prepare']),60000,{env});
  const mediaOwner=compose('run','--name',id+'-media-init','--user','0:0','--entrypoint','sh','api','-c','chown 10001:10001 /media && stat -c %u:%g /media');if(!mediaOwner.includes('10001:10001'))throw Error('Fresh media ownership preparation failed');write('media-init.json',{expectedOwner:'10001:10001',observed:mediaOwner,nocopy:true});
  compose('up','-d','api');
  const api=compose('ps','-q','api');await readiness('API migration and startup',()=>{const log=docker('logs',api);if(log.includes('Application run failed'))throw Error('API fatal startup error; original log retained');return log.includes('Tomcat started on port')},60000);
  if(docker('exec',api,'stat','-c','%u:%g','/media')!=='10001:10001')throw Error('API media ownership changed');
  compose('up','-d','worker','proxy');const worker=compose('ps','-q','worker');await readiness('worker startup',()=>docker('logs',worker).includes('Started MarketplaceApplication'),60000);
  await readiness('TLS proxy readiness and invitation boundary',async()=>{let valid;try{valid=await health(gate)}catch{return false}if(valid.status!==200||valid.body!=='{"status":"UP"}')throw Error('Valid invitation did not reach exact application health');const none=await health(),bad=await health('invalid-'+gate);if(none.status!==401||bad.status!==401)throw Error('Invitation boundary refusal failed');write('proxy-readiness.json',{valid:200,uninvited:401,wrongPassword:401,health:'UP'});return true},10000);
  const containers=docker('inspect','--format',roleInspectTemplate,pgid,api,worker,compose('ps','-q','proxy')).split(/\r?\n/).map(x=>JSON.parse(x));write('containers.json',containers);
  const [dbContainer,apiContainer,workerContainer,proxyContainer]=containers;
  if(apiContainer.Image!==env.PACKAGE_APP_IMAGE||workerContainer.Image!==env.PACKAGE_APP_IMAGE||apiContainer.User!=='10001:10001'||workerContainer.User!=='10001:10001')throw Error('Actual API/worker immutable role assertion failed');
  if(!dbContainer.Mounts.some(m=>m.Name===project+'_package-db')||!apiContainer.Mounts.some(m=>m.Name===project+'_package-media'&&m.Destination==='/media')||workerContainer.Mounts.length||proxyContainer.Mounts.some(m=>m.Type==='volume'))throw Error('Fresh DB/media and role mount isolation failed');
  if([dbContainer,apiContainer,workerContainer].some(c=>Object.keys(c.PortBindings||{}).length)||!Object.values(proxyContainer.PortBindings||{}).flat().every(p=>p.HostIp==='127.0.0.1'&&p.HostPort===String(port)))throw Error('Actual loopback-only network exposure failed');
  const migration=JSON.parse(docker('exec',pgid,'psql','-U','postgres','-d',env.PACKAGE_DB_NAME,'-Atc',"SELECT json_build_object('database',current_database(),'migrations',count(*) FILTER(WHERE version IS NOT NULL),'failed',count(*) FILTER(WHERE NOT success),'latest',max(version::integer)) FROM marketplace.flyway_schema_history"));write('migration.json',migration);
  if(migration.database!==env.PACKAGE_DB_NAME||migration.migrations!==131||migration.latest!==132||migration.failed!==0)throw Error('Fresh migration assertion failed');
  const benv={...process.env,A3_EVIDENCE:out,P12_E_ACCESS:path.join(out,'access.private.json'),P12_E_ACCOUNTS:path.join(out,'accounts.private.json'),PLAYWRIGHT_BROWSERS_PATH:o.browsers,P12_E_POSTGRES:pgid,P12_E_DATABASE:env.PACKAGE_DB_NAME};
  // No total/scenario cap: deadline lifecycle reporter and every business operation enforce finite phases.
  await new Promise((resolve,reject)=>{const child=cp.spawn(o.node,[path.join(root,'frontend/node_modules/@playwright/test/cli.js'),'test','--config',path.join(root,'infra/p12-e/browser.config.ts')],{cwd:path.join(root,'frontend'),env:benv,windowsHide:true,stdio:['ignore',fs.openSync(path.join(out,'browser.log'),'wx'),fs.openSync(path.join(out,'browser-error.log'),'wx')]});
   let gap=Date.now()+30000;const active=new Map();let consumed=0,watchFailure;
   const timer=setInterval(()=>{try{const f=path.join(out,'browser-progress.ndjson');const rows=fs.existsSync(f)?fs.readFileSync(f,'utf8').split('\n').slice(0,-1):[];for(const line of rows.slice(consumed)){const e=JSON.parse(line);if(e.event==='start')active.set(e.id,e);else {active.delete(e.id);gap=Date.now()+10000}}consumed=rows.length;if([...active.values()].some(e=>Date.now()>e.deadlineAt+1000)||(!active.size&&Date.now()>gap)){watchFailure=Error('Owned browser phase/progress watchdog expired');cp.spawnSync('taskkill',['/PID',String(child.pid),'/T','/F'],{timeout:5000,windowsHide:true});}}catch(e){watchFailure=e;child.kill()}},100);
   child.on('error',e=>{clearInterval(timer);reject(e)});child.on('exit',c=>{clearInterval(timer);c===0&&!watchFailure?resolve():reject(watchFailure||Error('Browser proof failed: '+c))});});
  passed=true;
 } catch(e){write('failure.json',{status:'failed',message:e.message});throw e}
 finally {const cleanupErrors=[];function attempt(work){try{work()}catch(e){cleanupErrors.push(e.message);passed=false}}if(started){attempt(()=>{const prep=docker('ps','-aq','--filter','name=^/'+id+'-prepare$');if(prep){docker('logs',prep);docker('stop','--timeout','10',prep)}});attempt(()=>compose('logs','--no-color'));attempt(()=>compose('stop','--timeout','10'))}
  try{const after=await snapshotDockerResources(args=>docker(...args));write('resources-after.json',after);if(beforeResources)write('preservation.json',assertPreserved(beforeResources,after,{ownedContainerIds:after.containers.filter(x=>x.name.startsWith('/'+id)).map(x=>x.id),ownedVolumeNames:after.volumes.filter(x=>x.project===project).map(x=>x.name),ownedNetworkIds:after.networks.filter(x=>x.project===project).map(x=>x.id)}))}catch(e){cleanupErrors.push(e.message);passed=false}
  write('existing-after.json',{containers:docker('ps','-a','--no-trunc','--format','{{.ID}} {{.Names}} {{.Status}}'),volumes:docker('volume','ls','--format','{{.Name}}'),networks:docker('network','ls','--no-trunc','--format','{{.ID}} {{.Name}}')});
  write('result.json',{status:passed?'passed':'failed',cleanupErrors,project,mode:'public-demo',jarSha256:sha(o.jar),artifactOnlyReadOnly:true,existingDatabaseUsed:false,externalPayment:false});write('commands.json',records);if(cleanupErrors.length)throw Error(cleanupErrors.join('; '));}
 async function readiness(name,check,ms){const at=Date.now();phaseDeadline=at+ms;try{while(Date.now()<phaseDeadline){if(await check()){records.push({name,elapsedMs:Date.now()-at,limitMs:ms,status:0});return}await new Promise(r=>setTimeout(r,Math.min(200,Math.max(1,phaseDeadline-Date.now()))));}throw Error(name+' failed')}finally{phaseDeadline=undefined}}
 function health(password){return new Promise((resolve,reject)=>{const request=require('node:https').get('https://localhost:'+env.PACKAGE_HTTPS_PORT+'/actuator/health',{rejectUnauthorized:false,headers:password?{Authorization:'Basic '+Buffer.from('reviewer:'+password).toString('base64')}:{},signal:AbortSignal.timeout(Math.max(1,Math.min(3000,phaseDeadline-Date.now())))},response=>{let body='';response.on('data',x=>body+=x);response.on('end',()=>resolve({status:response.statusCode,body}))});request.on('error',reject)})}
}
