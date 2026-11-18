// Root phase proof validation. Reads only explicit same-run artifacts and evidence.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {assertPreserved}=require('../infra/p12-e/preservation.cjs');
const {preflightCachedImages,assertBuiltImageBase}=require('../infra/p12-e/cached-images.cjs');
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const inventory=(directory,base=directory)=>fs.readdirSync(directory,{withFileTypes:true}).flatMap(e=>e.isDirectory()?inventory(path.join(directory,e.name),base):[path.relative(base,path.join(directory,e.name)).replaceAll('\\','/')]).sort();
function verifyEvidence(request,runRoot){
 const folder=path.join(runRoot,'container-reproduction'),producer=path.join(runRoot,'db/groups/package-runtime');
 for(const [key,value]of Object.entries({evidence:folder,jar:path.join(runRoot,'db/build/marketplace-api-0.0.1-SNAPSHOT.jar'),dist:path.join(producer,'browser-app'),jarProvenance:path.join(producer,'package-proof.json'),distProvenance:path.join(producer,'package-dist.json'),sourceInputs:path.join(runRoot,'inputs.json')}))assert.equal(request[key],value,'Same-run explicit path: '+key);
 assert.equal(sha(request.sourceInputs).toLowerCase(),request.sourceInputsSha256.toLowerCase(),'Source input identity');
 const sourceRoot=request.sourceRoot||runRoot;
 const sources=read(request.sourceInputs);assert.ok(sources.length>0,'Nonempty current source manifest');
 for(const source of sources){assert.ok(!path.isAbsolute(source.path)&&!source.path.split(/[\\/]/).includes('..'),'Source path');assert.equal(sha(path.join(sourceRoot,source.path)).toLowerCase(),source.sha256.toLowerCase(),'Current source changed: '+source.path)}
 const load=name=>read(path.join(folder,name+'.json')),result=load('result'),prerequisites=load('prerequisites');
 const helperFiles=['verification/PowerShellRuntime.cjs','verification/PowerShellRuntime.ps1'];
 const helperSource=load('powershell-helper-source');assert.deepEqual(helperSource.map(x=>x.file),helperFiles,'Exact external PowerShell helper dependencies');
 for(const helper of helperSource){assert.equal(helper.sha256.toLowerCase(),sha(path.join(sourceRoot,helper.file)),'Actual PowerShell helper bytes');assert.equal(sources.filter(x=>x.path===helper.file&&x.sha256.toLowerCase()===helper.sha256.toLowerCase()).length,1,'PowerShell helper linked to same-run source inputs')}
 const network=load('powershell-network'),runtime=network.runtime,requiredPowerShell=require('./PowerShellRuntime.cjs').executable;
 assert.equal(runtime.version,'7.6.9');assert.equal(runtime.edition,'Core');assert.equal(runtime.accepted,true);assert.equal(runtime.compatibilitySessions,0);
 assert.equal(path.resolve(runtime.executable).toLowerCase(),path.resolve(requiredPowerShell).toLowerCase(),'Observed actual network child executable');assert.ok(Number.isInteger(runtime.pid)&&runtime.pid>0);assert.ok(Array.isArray(network.routes)&&network.routes.length>0,'Observed native IPv4 routes');assert.match(network.nativeModule,/NetTCPIP/i);
 for(const key of Object.keys(request))assert.deepEqual(prerequisites[key],request[key],'Reproduction prerequisite: '+key);
 assert.match(result.project,/^p12e-[0-9a-f]{14}$/);assert.equal(prerequisites.project,result.project);
 assert.equal(result.status,'passed');assert.deepEqual(result.cleanupErrors,[]);assert.equal(result.mode,'public-demo');assert.equal(result.artifactOnlyReadOnly,true);assert.equal(result.existingDatabaseUsed,false);assert.equal(result.externalPayment,false);
 const jar=sha(request.jar);assert.equal(result.jarSha256.toLowerCase(),jar);assert.equal(prerequisites.jarSha256.toLowerCase(),jar);
 const jarProof=read(request.jarProvenance);assert.deepEqual(load('jar-provenance'),jarProof);assert.equal(jarProof.apiJarSha256.toLowerCase(),jar);assert.equal(jarProof.workerJarSha256.toLowerCase(),jar);
 const dist=read(request.distProvenance);assert.deepEqual(load('dist-provenance'),dist);assert.equal(dist.mode,'production');assert.ok(dist.files.length>0);
 assert.deepEqual(inventory(request.dist),dist.files.map(x=>x.file).sort(),'Exact production dist inventory');assert.ok(dist.files.some(x=>x.file==='index.html'));
 for(const file of dist.files){assert.ok(!path.isAbsolute(file.file)&&!file.file.split(/[\\/]/).includes('..'),'Dist path');const actual=path.join(request.dist,file.file);assert.equal(sha(actual),file.sha256.toLowerCase());if(file.bytes!=null)assert.equal(fs.statSync(actual).size,file.bytes)}
 const copied=load('proof-source');assert.ok(copied.length>0);assert.deepEqual(inventory(path.join(folder,'proof-source')),copied.map(x=>x.file).sort());
 assert.deepEqual(fs.readdirSync(path.join(sourceRoot,'infra/p12-e')).filter(file=>fs.statSync(path.join(sourceRoot,'infra/p12-e',file)).isFile()).sort(),copied.map(x=>x.file).sort(),'Complete current reproduction source snapshot');
 for(const file of copied){assert.ok(!path.isAbsolute(file.file)&&!file.file.split(/[\\/]/).includes('..'));assert.equal(sha(path.join(folder,'proof-source',file.file)),file.sha256.toLowerCase());assert.equal(sha(path.join(sourceRoot,'infra/p12-e',file.file)),file.sha256.toLowerCase())}
 const database='e_'+result.project.replaceAll('-','_'),migration=load('migration');assert.deepEqual(migration,{database,migrations:131,failed:0,latest:132});
 const media=load('media-init');assert.equal(media.expectedOwner,'10001:10001');assert.equal(media.observed.trim(),'10001:10001');assert.equal(media.nocopy,true);
 const containers=load('containers');assert.equal(containers.length,4);assert.equal(new Set(containers.map(x=>x.Id)).size,4);
 const role=name=>{const found=containers.filter(x=>x.Name==='/'+result.project+'-'+name+'-1');assert.equal(found.length,1,'Exact owned '+name+' container');assert.equal(found[0].State.Status,'running');return found[0]};
 const pg=role('postgres'),api=role('api'),worker=role('worker'),proxy=role('proxy');assert.equal(api.Image,worker.Image);
 const imageProof=load('image-prerequisites'),sourceDockerfile=fs.readFileSync(path.join(sourceRoot,'infra/p12-c/Dockerfile'),'utf8');
 const cached=preflightCachedImages({dockerfile:sourceDockerfile,compose:fs.readFileSync(path.join(sourceRoot,'infra/p12-c/compose.yaml'),'utf8'),inspect:reference=>{const found=imageProof.images.filter(x=>x.reference===reference);assert.equal(found.length,1,'Exact cached image prerequisite');return {Id:found[0].id,RepoDigests:found[0].repoDigests,RootFS:{Layers:found[0].layers}}}});
 assert.deepEqual(load('cached-images'),cached,'Observed image cache preflight');
 const effective=sourceDockerfile.replace(/^\s*FROM\s+[^\r\n]+/mi,'FROM '+cached.base.id);assert.equal(fs.readFileSync(path.join(folder,'image/Dockerfile'),'utf8'),effective,'Build constrained to immutable local base');
 assert.deepEqual(imageProof,{...cached,build:{builder:'legacy-local-image-id',fromImage:cached.base.id,pull:false,network:'none',effectiveDockerfileSha256:sha(path.join(folder,'image/Dockerfile'))}},'Exact cache and build evidence');
 const applicationImage=load('application-image');assert.equal(applicationImage.length,1);assert.equal(applicationImage[0].Id,api.Image,'Services consume verified built image');assertBuiltImageBase(cached,applicationImage[0]);
 const volume=(container,name,destination)=>assert.equal(container.Mounts.filter(x=>x.Type==='volume'&&x.Name===result.project+'_'+name&&x.Destination===destination&&x.RW===true).length,1,'Fresh owned '+name+' mount');
 volume(pg,'package-db','/var/lib/postgresql');volume(api,'package-media','/media');assert.equal(worker.Mounts.length,0,'Worker owns no media or DB volume');
 assert.ok(!proxy.Mounts.some(x=>x.Type==='volume'||x.Destination==='/media'||x.Destination==='/var/lib/postgresql'));
 const before=load('resources-before'),after=load('resources-after');
 const ownedContainers=after.containers.filter(x=>x.name.startsWith('/'+result.project+'-')).map(x=>x.id),ownedVolumes=after.volumes.filter(x=>x.project===result.project).map(x=>x.name),ownedNetworks=after.networks.filter(x=>x.project===result.project).map(x=>x.id);
 for(const container of containers){assert.ok(!before.containers.some(x=>x.id===container.Id),'Fresh container absence');assert.equal(after.containers.filter(x=>x.id===container.Id&&x.project===result.project&&x.name===container.Name).length,1,'Observed exact owned container')}
 for(const container of after.containers.filter(x=>ownedContainers.includes(x.id))){const state=container.state;assert.equal(state.status,'exited','Owned container shutdown observed');for(const flag of ['running','paused','restarting','oomKilled','dead'])assert.equal(state[flag],false,'Owned container shutdown '+flag);assert.ok((containers.some(x=>x.Id===container.id)?[0,143]:[0]).includes(state.exitCode),'Owned container shutdown exit code')}
 assert.deepEqual(ownedVolumes.sort(),[result.project+'_package-db',result.project+'_package-media'].sort());assert.ok(!before.volumes.some(x=>ownedVolumes.includes(x.name)),'Fresh volumes absence');
 assert.equal(ownedNetworks.length,2);assert.ok(!before.networks.some(x=>ownedNetworks.includes(x.id)),'Fresh networks absence');
 const preservation=assertPreserved(before,after,{ownedContainerIds:ownedContainers,ownedVolumeNames:ownedVolumes,ownedNetworkIds:ownedNetworks});assert.deepEqual(load('preservation'),preservation);
 const businesses=['desktop','mobile'].map(project=>{const business=load(project+'-business');assert.equal(business.project,project);for(const key of ['application','buyer','seller','operator','product','sku','sellerOrder'])assert.ok(business[key],'Missing business identity: '+key);assert.notEqual(business.buyer,business.seller);assert.notEqual(business.buyer,business.operator);assert.notEqual(business.seller,business.operator);assert.deepEqual(business.applicationDatabase,{id:business.application,applicant:business.seller,state:'APPROVED',events:[{actor:business.seller,state:'PENDING',reason:'모의 판매 신청'},{actor:business.operator,state:'APPROVED',reason:'독립 컨테이너 합성 승인'}]},'Exact same-ID application and approval audit');assert.match(business.order,/^O[0-9a-f]{32}$/);assert.equal(business.total,13000);assert.equal(business.externalPayment,false);assert.deepEqual(business.database,{order:{state:'PAID',amount:13000},inventory:['COMMITTED'],shipments:['SHIPPED'],attempts:[{approval_effects:1,success_events:1}]});return business});
 assert.notEqual(businesses[0].order,businesses[1].order,'Project-isolated producer orders');
 return {status:'passed',project:result.project,expectedDatabaseCount:1,database,postgresContainer:pg.Id,databaseVolume:result.project+'_package-db',mediaVolume:result.project+'_package-media',jarSha256:jar,sourceInputsSha256:request.sourceInputsSha256,preservation,closure:{status:'passed',stoppedContainerIds:ownedContainers},cachedBase:{reference:cached.base.reference,imageId:cached.base.id,builder:imageProof.build.builder},projects:['desktop','mobile']};
}
module.exports={verifyEvidence};
if(require.main===module){try{const request=read(process.argv[2]),proof=verifyEvidence(request,path.dirname(request.evidence));process.stdout.write(JSON.stringify(proof,null,2)+'\n')}catch(error){process.stderr.write(error.message+'\n');process.exitCode=1}}
