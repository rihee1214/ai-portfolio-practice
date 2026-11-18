const {test}=require('node:test');
const assert=require('node:assert/strict');
const {snapshotDockerResources,assertPreserved}=require('./preservation.cjs');

// Docker is the external boundary: these fixtures are safe inspect projections,
// never live resources. Comparisons and ownership checks use the real module.
const container=()=>({Id:'old-container',Name:'/old-api',Image:'sha256:old-image',ImageReference:'old:tag',Project:'old-project',RestartCount:0,
  State:{Status:'running',Running:true,Paused:false,Restarting:false,OOMKilled:false,Dead:false,ExitCode:0},
  Mounts:[{Type:'volume',Name:'old-media',Source:'/docker/volumes/old-media/_data',Destination:'/media',Driver:'local',Mode:'rw',RW:true,Propagation:''}],
  Networks:{old:{NetworkID:'old-network',EndpointID:'old-endpoint',Gateway:'172.30.0.1',IPAddress:'172.30.0.2',IPPrefixLen:24,IPv6Gateway:'',GlobalIPv6Address:'',GlobalIPv6PrefixLen:0,MacAddress:'02:42:ac:1e:00:02',Aliases:['old-api','api']}}});
const volume=()=>({Name:'old-media',Driver:'local',Mountpoint:'/docker/volumes/old-media/_data',CreatedAt:'2026-11-01T00:00:00Z',Scope:'local',Project:'old-project'});
const network=()=>({Id:'old-network',Name:'old',Driver:'bridge',Scope:'local',Internal:false,Attachable:false,Ingress:false,EnableIPv6:false,ConfigOnly:false,Project:'old-project',IPAM:{Driver:'default',Config:[{Subnet:'172.30.0.0/24',Gateway:'172.30.0.1'}]}});
const inventory=()=>({containers:[container()],volumes:[volume()],networks:[network()]});
function fakeDocker(resources,seen=[]){
  return async args=>{
    seen.push(args);
    const kind={container:'containers',volume:'volumes',network:'networks'}[args[0]];
    assert.ok(kind,'only the three read-only resource commands are accepted');
    if(args[1]==='ls')return resources[kind].map(x=>x.Id||x.Name).join('\n');
    assert.equal(args[1],'inspect','no Docker mutation is allowed');
    assert.equal(args[2],'--format','raw inspect output can contain secrets');
    assert.doesNotMatch(args[3],/\.Env|\.Options|json\s+\.\s*}}|json\s+\.Config\s*}}|json\s+\.State\s*}}/,'only safe explicit fields may be emitted');
    const ids=args.slice(4);
    return {stdout:resources[kind].filter(x=>ids.includes(x.Id||x.Name)).map(x=>JSON.stringify(x)).join('\n'),status:0};
  };
}
const snapshot=resources=>snapshotDockerResources(fakeDocker(resources));

test('safe snapshot carries container image/state/mount/network identity, volume creation and network IPAM',async()=>{
  const s=await snapshot(inventory());
  assert.equal(s.schemaVersion,1);
  assert.equal(s.containers[0].imageId,'sha256:old-image');
  assert.equal(s.containers[0].state.running,true);
  assert.equal(s.containers[0].mounts[0].source,'/docker/volumes/old-media/_data');
  assert.equal(s.containers[0].networks[0].networkId,'old-network');
  assert.equal(s.volumes[0].createdAt,'2026-11-01T00:00:00Z');
  assert.equal(s.networks[0].ipam.config[0].subnet,'172.30.0.0/24');
});

test('volatile timestamps, uptime and unrelated secret fields do not enter the snapshot',async()=>{
  const before=inventory(),after=inventory();
  before.containers[0].State.StartedAt='yesterday';after.containers[0].State.StartedAt='today';
  after.containers[0].Config={Env:['PASSWORD=secret']};after.volumes[0].Options={password:'secret'};
  after.networks[0].IPAM.Options={password:'secret'};
  after.containers[0].Status='Up 3 minutes';
  const a=await snapshot(before),b=await snapshot(after);
  assert.deepEqual(b,a);
  assert.doesNotMatch(JSON.stringify(b),/secret|StartedAt|Up 3/);
  assert.equal(assertPreserved(a,b).preserved,true);
});

for(const [name,mutate] of [
  ['container image',r=>{r.containers[0].Image='sha256:replacement'}],
  ['container state',r=>{r.containers[0].State.Status='exited';r.containers[0].State.Running=false}],
  ['container restart',r=>{r.containers[0].RestartCount=1}],
  ['container mount source',r=>{r.containers[0].Mounts[0].Source='/replacement'}],
  ['container network identity',r=>{r.containers[0].Networks.old.NetworkID='replacement-network'}],
  ['volume recreation',r=>{r.volumes[0].CreatedAt='2026-11-16T00:00:00Z'}],
  ['network subnet',r=>{r.networks[0].IPAM.Config[0].Subnet='10.99.0.0/24'}],
])test('preexisting '+name+' mutation blocks preservation',async()=>{
  const changed=inventory();mutate(changed);
  const before=await snapshot(inventory()),after=await snapshot(changed);
  assert.throws(()=>assertPreserved(before,after),/changed/);
});

for(const kind of ['containers','volumes','networks'])test('preexisting '+kind+' removal blocks preservation even when marked owned',async()=>{
  const changed=inventory();changed[kind]=[];
  const a=await snapshot(inventory()),b=await snapshot(changed);
  assert.throws(()=>assertPreserved(a,b,{ownedContainerIds:['old-container'],ownedVolumeNames:['old-media'],ownedNetworkIds:['old-network']}),/removed/);
});

test('new additions pass only with exact explicit ownership and never exempt old resource changes',async()=>{
  const changed=inventory();changed.containers.push({...container(),Id:'new-container',Name:'/new-api'});
  changed.volumes.push({...volume(),Name:'new-media'});changed.networks.push({...network(),Id:'new-network',Name:'new'});
  const a=await snapshot(inventory()),b=await snapshot(changed);
  assert.throws(()=>assertPreserved(a,b),/Unowned/);
  const owned={ownedContainerIds:['new-container'],ownedVolumeNames:['new-media'],ownedNetworkIds:['new-network']};
  assert.deepEqual(assertPreserved(a,b,owned),{preserved:true,preexisting:{containers:1,volumes:1,networks:1},additions:{containers:['new-container'],volumes:['new-media'],networks:['new-network']}});
  b.containers.find(x=>x.id==='old-container').imageId='sha256:changed';
  assert.throws(()=>assertPreserved(a,b,{...owned,ownedContainerIds:['new-container','old-container']}),/changed/);
});

for(const kind of ['containers','volumes','networks'])test('a new unowned '+kind+' identity cannot hide behind another owned resource',async()=>{
  const changed=inventory();
  if(kind==='containers')changed.containers.push({...container(),Id:'new-container',Name:'/new-api'});
  if(kind==='volumes')changed.volumes.push({...volume(),Name:'new-media'});
  if(kind==='networks')changed.networks.push({...network(),Id:'new-network',Name:'new'});
  const a=await snapshot(inventory()),b=await snapshot(changed);
  assert.throws(()=>assertPreserved(a,b,{ownedContainerIds:['other'],ownedVolumeNames:['other'],ownedNetworkIds:['other']}),new RegExp('Unowned '+kind));
});

test('resource order, mount order, aliases and IPAM object order do not cause false mutation failures',async()=>{
  const before=inventory();before.containers[0].Mounts.push({Type:'bind',Source:'/config',Destination:'/config',Mode:'ro',RW:false,Propagation:'rprivate'});
  before.networks[0].IPAM.Config.push({Subnet:'10.1.0.0/24',Gateway:'10.1.0.1'});
  const after=structuredClone(before);after.containers[0].Mounts.reverse();after.containers[0].Networks.old.Aliases.reverse();after.networks[0].IPAM.Config.reverse();
  assert.equal(assertPreserved(await snapshot(before),await snapshot(after)).preserved,true);
});

test('empty inventory performs no inspect and still produces a valid preservation proof',async()=>{
  const seen=[],s=await snapshotDockerResources(fakeDocker({containers:[],volumes:[],networks:[]},seen));
  assert.equal(seen.length,3);assert.equal(assertPreserved(s,s).preserved,true);
});

test('large preexisting inventories retain every identity without exceeding bounded inspect argument batches',async()=>{
  const resources={containers:Array.from({length:45},(_,i)=>({...container(),Id:'c'.repeat(60)+String(i).padStart(4,'0'),Name:'/api-'+i})),volumes:[],networks:[]};
  const seen=[],s=await snapshotDockerResources(fakeDocker(resources,seen));
  assert.equal(s.containers.length,45);
  assert.equal(s.containers[0].id,'c'.repeat(60)+'0000');
  assert.equal(s.containers[44].id,'c'.repeat(60)+'0044');
  const batches=seen.filter(args=>args[1]==='inspect');
  assert.equal(batches.length,3);
  assert.ok(batches.every(args=>args.slice(4).length<=20),'Windows-safe bounded argv is required');
  assert.equal(assertPreserved(s,s).preexisting.containers,45);
});

test('incomplete, duplicate and malformed inspect results fail closed without dumping raw output',async()=>{
  await assert.rejects(snapshotDockerResources(async args=>args[1]==='ls'?'old-container':''),/inspect/);
  await assert.rejects(snapshotDockerResources(async args=>args[1]==='ls'?'one':'PASSWORD=private-invalid-json'),/inspect/);
  try{await snapshotDockerResources(async args=>args[1]==='ls'?'one':'PASSWORD=private-invalid-json')}catch(e){assert.doesNotMatch(e.message,/PASSWORD|private/)}
  const dup=inventory();dup.containers.push(container());
  await assert.rejects(snapshot(dup),/duplicate/i);
});

test('nonzero callback command status cannot become an empty successful snapshot',async()=>{
  await assert.rejects(snapshotDockerResources(async()=>({status:1,stdout:'',stderr:'private'})),/failed/);
});

test('missing or unsupported snapshots cannot produce a preservation claim',()=>{
  assert.throws(()=>assertPreserved({},{}),/snapshot/i);
  assert.throws(()=>assertPreserved({schemaVersion:1,containers:[],volumes:[],networks:[]},{schemaVersion:2,containers:[],volumes:[],networks:[]}),/snapshot/i);
});
