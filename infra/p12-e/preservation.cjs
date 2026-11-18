// Read-only Docker evidence. The caller owns execution/logging; never pass raw
// inspect, environment, driver options, health output or secret contents to it.
const {isDeepStrictEqual}=require('node:util');
const field=(name,expression)=>JSON.stringify(name)+':'+(expression.startsWith('{{')?expression:'{{json '+expression+'}}');
const object=fields=>'{'+Object.entries(fields).map(([k,v])=>field(k,v)).join(',')+'}';
const project=labels=>'{{if '+labels+'}}{{json (index '+labels+' "com.docker.compose.project")}}{{else}}""{{end}}';
const stateFormat=object({Status:'.State.Status',Running:'.State.Running',Paused:'.State.Paused',Restarting:'.State.Restarting',OOMKilled:'.State.OOMKilled',Dead:'.State.Dead',ExitCode:'.State.ExitCode'});
const mountFormat=object(Object.fromEntries(['Type','Name','Source','Destination','Driver','Mode','RW','Propagation'].map(k=>[k,'(index . "'+k+'")'])));
const endpointFormat=object({Name:'$name',NetworkID:'$n.NetworkID',EndpointID:'$n.EndpointID',Gateway:'$n.Gateway',IPAddress:'$n.IPAddress',IPPrefixLen:'$n.IPPrefixLen',IPv6Gateway:'$n.IPv6Gateway',GlobalIPv6Address:'$n.GlobalIPv6Address',GlobalIPv6PrefixLen:'$n.GlobalIPv6PrefixLen',MacAddress:'$n.MacAddress',Aliases:'$n.Aliases'});
const containerFormat=object({Id:'.Id',Name:'.Name',Image:'.Image',ImageReference:'.Config.Image',Project:project('.Config.Labels'),RestartCount:'.RestartCount'}).slice(0,-1)+',"State":'+stateFormat+',"Mounts":[{{range $i, $m := .Mounts}}{{if $i}},{{end}}{{with $m}}'+mountFormat+'{{end}}{{end}}],"Networks":[{{$first := true}}{{range $name, $n := .NetworkSettings.Networks}}{{if not $first}},{{end}}{{$first = false}}'+endpointFormat+'{{end}}]}';
const volumeFormat=object({Name:'.Name',Driver:'.Driver',Mountpoint:'.Mountpoint',CreatedAt:'.CreatedAt',Scope:'.Scope',Project:project('.Labels')});
const networkFormat=object({Id:'.Id',Name:'.Name',Driver:'.Driver',Scope:'.Scope',Internal:'.Internal',Attachable:'.Attachable',Ingress:'.Ingress',EnableIPv6:'.EnableIPv6',ConfigOnly:'.ConfigOnly',Project:project('.Labels')}).slice(0,-1)+',"IPAM":'+object({Driver:'.IPAM.Driver',Config:'.IPAM.Config'})+'}';
const specs=[['containers','container','Id',containerFormat],['volumes','volume','Name',volumeFormat],['networks','network','Id',networkFormat]];
const text=x=>x==null?'':String(x);
const sorted=values=>values.sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));
const strings=values=>[...new Set((values||[]).map(text))].sort();
function requiredString(value,context){if(typeof value!=='string'||!value)throw Error('Invalid Docker inspect '+context);return value;}
function bool(value,context){if(typeof value!=='boolean')throw Error('Invalid Docker inspect '+context);return value;}
function number(value,context){if(typeof value!=='number'||!Number.isFinite(value))throw Error('Invalid Docker inspect '+context);return value;}
function normalize(kind,x){
  if(!x||typeof x!=='object'||Array.isArray(x))throw Error('Invalid Docker inspect '+kind);
  if(kind==='containers'){
    const s=x.State||{},endpoints=Array.isArray(x.Networks)?x.Networks:Object.entries(x.Networks||{}).map(([Name,n])=>({Name,...n}));
    return {id:requiredString(x.Id,'container ID'),name:requiredString(x.Name,'container name'),imageId:requiredString(x.Image,'container image'),imageReference:requiredString(x.ImageReference,'image reference'),project:text(x.Project),restartCount:number(x.RestartCount,'restart count'),
      state:{status:requiredString(s.Status,'container state'),running:bool(s.Running,'running'),paused:bool(s.Paused,'paused'),restarting:bool(s.Restarting,'restarting'),oomKilled:bool(s.OOMKilled,'OOMKilled'),dead:bool(s.Dead,'dead'),exitCode:number(s.ExitCode,'exit code')},
      mounts:sorted((x.Mounts||[]).map(m=>({type:text(m.Type),name:text(m.Name),source:text(m.Source),destination:text(m.Destination),driver:text(m.Driver),mode:text(m.Mode),rw:bool(m.RW,'mount RW'),propagation:text(m.Propagation)}))),
      networks:sorted(endpoints.map(n=>({name:text(n.Name),networkId:text(n.NetworkID),endpointId:text(n.EndpointID),gateway:text(n.Gateway),ipAddress:text(n.IPAddress),ipPrefixLen:number(n.IPPrefixLen,'IP prefix'),ipv6Gateway:text(n.IPv6Gateway),globalIPv6Address:text(n.GlobalIPv6Address),globalIPv6PrefixLen:number(n.GlobalIPv6PrefixLen,'IPv6 prefix'),macAddress:text(n.MacAddress),aliases:strings(n.Aliases)})))};
  }
  if(kind==='volumes')return {name:requiredString(x.Name,'volume name'),driver:requiredString(x.Driver,'volume driver'),mountpoint:text(x.Mountpoint),createdAt:text(x.CreatedAt),scope:text(x.Scope),project:text(x.Project)};
  const ipam=x.IPAM||{};
  return {id:requiredString(x.Id,'network ID'),name:requiredString(x.Name,'network name'),driver:requiredString(x.Driver,'network driver'),scope:text(x.Scope),internal:bool(x.Internal,'network internal'),attachable:bool(x.Attachable,'network attachable'),ingress:bool(x.Ingress,'network ingress'),enableIPv6:bool(x.EnableIPv6,'network IPv6'),configOnly:bool(x.ConfigOnly,'network configOnly'),project:text(x.Project),
    ipam:{driver:text(ipam.Driver),config:sorted((ipam.Config||[]).map(c=>({subnet:text(c.Subnet),ipRange:text(c.IPRange),gateway:text(c.Gateway),auxiliaryAddresses:Object.fromEntries(Object.entries(c.AuxiliaryAddresses||{}).sort(([a],[b])=>a.localeCompare(b)))})))}};
}
/** execDocker(argv) may be sync/async and return stdout or {stdout,status}.
 * It must throw on execution errors (or return nonzero status). No mutation is
 * requested. A non-atomic inventory change fails closed; it is not retried.
 */
async function snapshotDockerResources(execDocker){
  if(typeof execDocker!=='function')throw Error('Explicit Docker execution callback required');
  async function run(args){
    const result=await execDocker(args);
    if(result&&typeof result==='object'&&((result.status!=null&&result.status!==0)||(result.exitCode!=null&&result.exitCode!==0)||result.error))throw Error('Docker preservation command failed');
    const stdout=typeof result==='string'?result:result?.stdout;
    if(typeof stdout!=='string')throw Error('Docker preservation command returned no stdout');
    return stdout.trim();
  }
  const snapshot={schemaVersion:1};
  for(const [kind,command,identity,format] of specs){
    const listing=await run([command,'ls',...(command==='container'?['--all']:[]),'--quiet',...(command!=='volume'?['--no-trunc']:[])]);
    const ids=listing?listing.split(/\s+/):[];
    if(new Set(ids).size!==ids.length)throw Error('Duplicate Docker resource identity in '+kind);
    let rows=[];
    if(ids.length){
      // This host deliberately preserves many prior runs. Bound argv below the
      // Windows process limit without dropping any of those resource IDs.
      for(let offset=0;offset<ids.length;offset+=20){
        const output=await run([command,'inspect','--format',format,...ids.slice(offset,offset+20)]);
        try{rows.push(...(output?output.split(/\r?\n/).map(line=>JSON.parse(line)):[]))}catch{throw Error('Invalid Docker inspect JSON for '+kind)}
      }
      const actual=rows.map(x=>x?.[identity]);
      if(actual.length!==ids.length||new Set(actual).size!==actual.length||actual.some(id=>!ids.includes(id)))throw Error('Incomplete or duplicate Docker inspect identities for '+kind);
    }
    snapshot[kind]=rows.map(x=>normalize(kind,x)).sort((a,b)=>(a.id||a.name).localeCompare(b.id||b.name));
  }
  return snapshot;
}
function indexSnapshot(snapshot,kind){
  if(snapshot?.schemaVersion!==1||!Array.isArray(snapshot[kind]))throw Error('Invalid preservation snapshot: '+kind);
  const entries=snapshot[kind].map(x=>[requiredString(x?.[kind==='volumes'?'name':'id'],'snapshot identity'),x]);
  const map=new Map(entries);
  if(map.size!==entries.length)throw Error('Duplicate preservation snapshot identity: '+kind);
  return map;
}
/** Additions must be explicitly identified by the new run. Being owned never
 * exempts an identity that existed in the before snapshot from comparison.
 */
function assertPreserved(before,after,{ownedContainerIds=[],ownedVolumeNames=[],ownedNetworkIds=[]}={}){
  const owned={containers:new Set(ownedContainerIds),volumes:new Set(ownedVolumeNames),networks:new Set(ownedNetworkIds)},preexisting={},additions={};
  for(const [kind] of specs){
    const old=indexSnapshot(before,kind),now=indexSnapshot(after,kind);preexisting[kind]=old.size;additions[kind]=[];
    for(const [id,resource] of old){
      if(!now.has(id))throw Error('Preexisting '+kind+' resource removed: '+id);
      if(!isDeepStrictEqual(resource,now.get(id)))throw Error('Preexisting '+kind+' resource changed: '+id);
    }
    for(const id of now.keys())if(!old.has(id)){
      if(!owned[kind].has(id))throw Error('Unowned '+kind+' addition: '+id);
      additions[kind].push(id);
    }
    additions[kind].sort();
  }
  return {preserved:true,preexisting,additions};
}
module.exports={snapshotDockerResources,assertPreserved};
