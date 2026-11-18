const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
const {verifyEvidence}=require('./ContainerReproduction.cjs');
const {preflightCachedImages,references}=require('../infra/p12-e/cached-images.cjs');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function fixture(){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'p12e-integrated-')),folder=path.join(root,'container-reproduction'),producer=path.join(root,'db/groups/package-runtime');
 fs.mkdirSync(folder,{recursive:true});fs.mkdirSync(producer,{recursive:true});fs.mkdirSync(path.join(root,'db/build'),{recursive:true});fs.mkdirSync(path.join(root,'infra/p12-e'),{recursive:true});
 const write=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,JSON.stringify(value))};
 const project='p12e-0123456789abcd',database='e_'+project.replaceAll('-','_'),jar=path.join(root,'db/build/marketplace-api-0.0.1-SNAPSHOT.jar'),dist=path.join(producer,'browser-app');
 fs.writeFileSync(jar,'current JAR');write(path.join(dist,'index.html'),'current bundle');write(path.join(root,'infra/p12-e/reproduce.cjs'),'current driver');
 const jarProvenance=path.join(producer,'package-proof.json'),distProvenance=path.join(producer,'package-dist.json');
 write(jarProvenance,{jar,apiJarSha256:hash(jar),workerJarSha256:hash(jar)});write(distProvenance,{mode:'production',files:[{file:'index.html',bytes:fs.statSync(path.join(dist,'index.html')).size,sha256:hash(path.join(dist,'index.html'))}]});
 const helpers=['verification/PowerShellRuntime.cjs','verification/PowerShellRuntime.ps1'];for(const file of helpers)write(path.join(root,file),'current helper '+file);
 const inputs=path.join(root,'inputs.json');write(inputs,['infra/p12-e/reproduce.cjs',...helpers].map(file=>({path:file,sha256:hash(path.join(root,file))})));
 const request={evidence:folder,jar,dist,jarProvenance,distProvenance,jdk:root,node:path.join(root,'node.exe'),browsers:root,sourceInputs:inputs,sourceInputsSha256:hash(inputs),sourceHead:'source-commit'};
 const save=(name,value)=>write(path.join(folder,name+'.json'),value),read=name=>JSON.parse(fs.readFileSync(path.join(folder,name+'.json'),'utf8'));
 save('powershell-helper-source',helpers.map(file=>({file,sha256:hash(path.join(root,file))})));
 save('powershell-network',{runtime:{executable:require('./PowerShellRuntime.cjs').executable,version:'7.6.9',edition:'Core',accepted:true,compatibilitySessions:0,pid:12345},routes:['127.0.0.0/8'],nativeModule:'NetTCPIP.psd1'});
 save('prerequisites',{...request,sourceHead:request.sourceHead,project,jarSha256:hash(jar)});
 save('jar-provenance',JSON.parse(fs.readFileSync(jarProvenance)));save('dist-provenance',JSON.parse(fs.readFileSync(distProvenance)));
 save('result',{status:'passed',cleanupErrors:[],project,mode:'public-demo',jarSha256:hash(jar),artifactOnlyReadOnly:true,existingDatabaseUsed:false,externalPayment:false});
 save('migration',{database,migrations:131,failed:0,latest:132});save('media-init',{expectedOwner:'10001:10001',observed:'10001:10001',nocopy:true});
 const dockerfile=fs.readFileSync(path.join(__dirname,'../infra/p12-c/Dockerfile'),'utf8'),compose='image: '+references.nginx+'\nimage: '+references.postgres;
 fs.mkdirSync(path.join(root,'infra/p12-c'));fs.writeFileSync(path.join(root,'infra/p12-c/Dockerfile'),dockerfile);fs.writeFileSync(path.join(root,'infra/p12-c/compose.yaml'),compose);
 const images=Object.entries(references).map(([role,reference],i)=>({reference,Id:'sha256:'+String(i+1).repeat(64),RepoDigests:[reference],RootFS:{Layers:['sha256:'+String(i+4).repeat(64)]}}));
 const cache=preflightCachedImages({dockerfile,compose,inspect:reference=>images.find(x=>x.reference===reference)}),effective=dockerfile.replace(/^\s*FROM\s+[^\r\n]+/mi,'FROM '+cache.base.id);
 save('cached-images',cache);fs.mkdirSync(path.join(folder,'image'));fs.writeFileSync(path.join(folder,'image/Dockerfile'),effective);
 save('image-prerequisites',{...cache,build:{builder:'legacy-local-image-id',fromImage:cache.base.id,pull:false,network:'none',effectiveDockerfileSha256:hash(path.join(folder,'image/Dockerfile'))}});
 save('application-image',[{Id:'sha256:same',RootFS:{Layers:[...cache.base.layers,'sha256:application']},User:'10001:10001'}]);
 const ids=['postgres','api','worker','proxy'].map((role,i)=>({Id:'container-'+i,Name:'/'+project+'-'+role+'-1',Image:role==='api'||role==='worker'?'sha256:same':'sha256:'+role,Mounts:role==='api'?[{Type:'volume',Name:project+'_package-media',Destination:'/media',RW:true}]:role==='postgres'?[{Type:'volume',Name:project+'_package-db',Destination:'/var/lib/postgresql',RW:true}]:[],Networks:{[project+'_'+(role==='proxy'?'edge':'data')]:{}},State:{Status:'running'}}));save('containers',ids);
 const before={schemaVersion:1,containers:[{id:'old',name:'/existing',state:{running:true}}],volumes:[],networks:[]};
 const after={schemaVersion:1,containers:[...before.containers,...ids.map(x=>({id:x.Id,name:x.Name,project,state:{status:'exited',running:false,paused:false,restarting:false,oomKilled:false,dead:false,exitCode:x.Name.includes('-api-')||x.Name.includes('-worker-')?143:0}}))],volumes:['package-db','package-media'].map(x=>({name:project+'_'+x,project})),networks:['edge','data'].map(x=>({id:x,name:project+'_'+x,project}))};
 save('resources-before',before);save('resources-after',after);save('preservation',{preserved:true,preexisting:{containers:1,volumes:0,networks:0},additions:{containers:ids.map(x=>x.Id).sort(),volumes:after.volumes.map(x=>x.name).sort(),networks:['data','edge']}});
 save('proof-source',[{file:'reproduce.cjs',sha256:hash(path.join(root,'infra/p12-e/reproduce.cjs'))}]);fs.mkdirSync(path.join(folder,'proof-source'));fs.copyFileSync(path.join(root,'infra/p12-e/reproduce.cjs'),path.join(folder,'proof-source/reproduce.cjs'));
 for(const [i,p]of ['desktop','mobile'].entries())save(p+'-business',{project:p,application:'application-'+i,applicationDatabase:{id:'application-'+i,applicant:'seller-'+i,state:'APPROVED',events:[{actor:'seller-'+i,state:'PENDING',reason:'모의 판매 신청'},{actor:'operator',state:'APPROVED',reason:'독립 컨테이너 합성 승인'}]},buyer:'buyer-'+i,seller:'seller-'+i,operator:'operator',product:'product-'+i,sku:'sku-'+i,order:'O'+String(i+1).repeat(32),sellerOrder:'seller-order-'+i,total:13000,database:{order:{state:'PAID',amount:13000},inventory:['COMMITTED'],shipments:['SHIPPED'],attempts:[{approval_effects:1,success_events:1}]},externalPayment:false});
 return {root,folder,request,save,read};
}
test('same-run artifacts source migration exact ownership money and preservation are required',()=>{const f=fixture();assert.equal(verifyEvidence(f.request,f.root).status,'passed')});
for(const [name,mutate]of [
 ['failed child',f=>{const r=f.read('result');r.status='failed';f.save('result',r)}],
 ['missing PowerShell helper dependency',f=>{const r=f.read('powershell-helper-source');r.pop();f.save('powershell-helper-source',r)}],
 ['changed PowerShell helper dependency',f=>{const r=f.read('powershell-helper-source');r[0].sha256='0'.repeat(64);f.save('powershell-helper-source',r)}],
 ['legacy network child runtime',f=>{const r=f.read('powershell-network');r.runtime.version='5.1';f.save('powershell-network',r)}],
 ['compatibility network child runtime',f=>{const r=f.read('powershell-network');r.runtime.compatibilitySessions=1;f.save('powershell-network',r)}],
 ['empty native host routes',f=>{const r=f.read('powershell-network');r.routes=[];f.save('powershell-network',r)}],
 ['cleanup failure',f=>{const r=f.read('result');r.cleanupErrors=['stop failed'];f.save('result',r)}],
 ['old database',f=>{const r=f.read('migration');r.database='existing';f.save('migration',r)}],
 ['incomplete migration',f=>{const r=f.read('migration');r.latest=131;f.save('migration',r)}],
 ['different artifact',f=>fs.appendFileSync(f.request.jar,'stale')],
 ['source mutation',f=>fs.appendFileSync(path.join(f.root,'infra/p12-e/reproduce.cjs'),'changed')],
 ['copied source mutation',f=>fs.appendFileSync(path.join(f.folder,'proof-source/reproduce.cjs'),'changed')],
 ['private stale producer',f=>{f.request.jarProvenance=path.join(f.root,'old-proof.json')}],
 ['extra unrecorded dist',f=>fs.writeFileSync(path.join(f.request.dist,'old.js'),'old')],
 ['dist traversal',f=>{const r=f.read('dist-provenance');r.files[0].file='../outside';f.save('dist-provenance',r)}],
 ['missing mobile',f=>fs.renameSync(path.join(f.folder,'mobile-business.json'),path.join(f.folder,'mobile-business.absent'))],
 ['wrong money',f=>{const r=f.read('mobile-business');r.database.order.amount=1;f.save('mobile-business',r)}],
 ['different order state',f=>{const r=f.read('desktop-business');r.database.order.state='PENDING_PAYMENT';f.save('desktop-business',r)}],
 ['shared browser order',f=>{const r=f.read('mobile-business');r.order=f.read('desktop-business').order;f.save('mobile-business',r)}],
 ['worker media access',f=>{const r=f.read('containers');r[2].Mounts=r[1].Mounts;f.save('containers',r)}],
 ['old volume',f=>{const r=f.read('containers');r[1].Mounts[0].Name='old-media';f.save('containers',r)}],
 ['image mismatch',f=>{const r=f.read('containers');r[2].Image='sha256:other';f.save('containers',r)}],
 ['resource mutation hidden by claimed preservation',f=>{const r=f.read('resources-after');r.containers[0].state.running=false;f.save('resources-after',r)}],
 ['unowned resource addition',f=>{const r=f.read('resources-after');r.volumes.push({name:'foreign',project:'foreign'});f.save('resources-after',r)}],
 ['claimed preservation false',f=>{const r=f.read('preservation');r.preserved=false;f.save('preservation',r)}]
 ,['missing payment attempt proof',f=>{const r=f.read('desktop-business');delete r.database.attempts;f.save('desktop-business',r)}]
 ,['duplicate payment approval effect',f=>{const r=f.read('desktop-business');r.database.attempts[0].approval_effects=2;f.save('desktop-business',r)}]
 ,['missing payment success receipt',f=>{const r=f.read('desktop-business');r.database.attempts[0].success_events=0;f.save('desktop-business',r)}]
 ,['multiple payment attempts',f=>{const r=f.read('desktop-business');r.database.attempts.push({...r.database.attempts[0]});f.save('desktop-business',r)}]
 ,['unexpected payment proof field',f=>{const r=f.read('desktop-business');r.database.unregistered=true;f.save('desktop-business',r)}]
 ,['owned service still running after cleanup',f=>{const r=f.read('resources-after');r.containers[1].state.status='running';r.containers[1].state.running=true;f.save('resources-after',r)}]
 ,['owned service killed by OOM',f=>{const r=f.read('resources-after');r.containers[1].state.oomKilled=true;r.containers[1].state.exitCode=137;f.save('resources-after',r)}]
 ,['owned helper still running after cleanup',f=>{const r=f.read('resources-after');r.containers.push({id:'helper',name:'/p12e-0123456789abcd-prepare',project:'p12e-0123456789abcd',state:{status:'running',running:true,paused:false,restarting:false,oomKilled:false,dead:false,exitCode:0}});f.save('resources-after',r);const p=f.read('preservation');p.additions.containers.push('helper');p.additions.containers.sort();f.save('preservation',p)}]
 ,['missing approval database proof',f=>{const r=f.read('desktop-business');delete r.applicationDatabase;f.save('desktop-business',r)}]
 ,['approval foreign application identity',f=>{const r=f.read('desktop-business');r.applicationDatabase.id='foreign';f.save('desktop-business',r)}]
 ,['approval foreign applicant identity',f=>{const r=f.read('desktop-business');r.applicationDatabase.applicant='foreign';f.save('desktop-business',r)}]
 ,['approval foreign operator audit',f=>{const r=f.read('desktop-business');r.applicationDatabase.events[1].actor='foreign';f.save('desktop-business',r)}]
 ,['application not approved in database',f=>{const r=f.read('desktop-business');r.applicationDatabase.state='PENDING';f.save('desktop-business',r)}]
 ,['approval audit event missing',f=>{const r=f.read('desktop-business');r.applicationDatabase.events.pop();f.save('desktop-business',r)}]
 ,['approval reason differs from UI command',f=>{const r=f.read('desktop-business');r.applicationDatabase.events[1].reason='different';f.save('desktop-business',r)}]
 ,['approval extra audit event',f=>{const r=f.read('desktop-business');r.applicationDatabase.events.push({...r.applicationDatabase.events[1]});f.save('desktop-business',r)}]
 ,['missing cached image proof',f=>fs.renameSync(path.join(f.folder,'image-prerequisites.json'),path.join(f.folder,'image-prerequisites.absent'))]
 ,['cached image foreign digest',f=>{const r=f.read('image-prerequisites');r.images[0].repoDigests=['foreign'];f.save('image-prerequisites',r)}]
 ,['mutable build base identity',f=>{const r=f.read('image-prerequisites');r.build.fromImage='temurin:latest';f.save('image-prerequisites',r)}]
 ,['source Dockerfile changed after image preflight',f=>fs.appendFileSync(path.join(f.root,'infra/p12-c/Dockerfile'),'#changed')]
 ,['built image foreign base layers',f=>{const r=f.read('application-image');r[0].RootFS.Layers=['sha256:foreign'];f.save('application-image',r)}]
])test(name+' cannot certify integrated AC16',()=>{const f=fixture();mutate(f);assert.throws(()=>verifyEvidence(f.request,f.root))});
