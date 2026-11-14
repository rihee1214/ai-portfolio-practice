import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, useParams } from 'react-router'
import { requestJson } from '../api/http'
import { useAuthStore } from '../auth/AuthContext'
import { useInquiryCommand } from '../content/useInquiryCommand'
import './site.css'

type Kind = 'NOTICE' | 'FAQ' | 'BANNER'
type Post = { id: string; version: number; kind: Kind; state: string; title: string; body: string; alt: string; destination: string | null; priority: number; startsAt: string | null; endsAt: string | null; asset: string | null }
type Info = { version: number; siteName: string; supportGuide: string }
type Policy = { version: number; baseFee: number; freeThreshold: number }
type Job = { id: string; type: string; status: string; attempts: number; scheduledAt: string; nextRunAt: string; error: string | null }
const kinds: Kind[] = ['NOTICE', 'FAQ', 'BANNER']
const names = { NOTICE: '공지', FAQ: 'FAQ', BANNER: '배너' }
function object(v: unknown): v is Record<string, unknown> { return typeof v === 'object' && v !== null }
function nullable(v: unknown) { return v === null || typeof v === 'string' }
function post(v: unknown): v is Post {
  return object(v) && typeof v.id === 'string' && typeof v.version === 'number' && kinds.some(k => k === v.kind)
    && typeof v.state === 'string' && typeof v.title === 'string' && typeof v.body === 'string' && typeof v.alt === 'string'
    && typeof v.priority === 'number' && [v.asset,v.startsAt,v.endsAt,v.destination].every(nullable)
}
function info(v: unknown): v is Info { return object(v) && typeof v.version === 'number' && typeof v.siteName === 'string' && typeof v.supportGuide === 'string' }
function policy(v: unknown): v is Policy { return object(v) && typeof v.version === 'number' && typeof v.baseFee === 'number' && typeof v.freeThreshold === 'number' }
function job(v: unknown): v is Job { return object(v) && ['id','type','status','scheduledAt','nextRunAt'].every(k => typeof v[k] === 'string') && typeof v.attempts === 'number' && nullable(v.error) }
function useRead(path: string, generation = 0) {
  const [response,setResponse] = useState<{ key: string; data?: unknown; failed?: boolean }>()
  const key = path + '#' + generation
  useEffect(() => {
    const controller = new AbortController()
    void requestJson(path,{signal:controller.signal,cache:'no-store'}).then(r => {
      if (!controller.signal.aborted) setResponse(r.kind === 'success' ? { key,data:r.data } : { key,failed:true })
    })
    return () => controller.abort()
  },[key,path])
  return response?.key === key ? response : undefined
}
export function PublicPosts({ kind }: { kind: Kind }) {
  const { id } = useParams()
  const [page,setPage] = useState(0)
  const result = useRead(id ? '/api/site/posts/'+encodeURIComponent(id) : '/api/site/posts?kind='+kind+'&page='+page)
  if (id) {
    const p = result?.data
    return post(p) && p.kind === kind ? <article className="site-admin"><h1>{p.title}</h1><p>{p.body}</p><Link to="/notices">공지 목록</Link></article>
      : <p role="status">{result ? '공개된 게시물을 찾을 수 없습니다.' : '불러오는 중입니다.'}</p>
  }
  const rows = result?.data
  if (kind === 'BANNER') return Array.isArray(rows) && rows.every(post) ? <div className="site-banners">{rows.filter(p=>p.asset).map(p=>{
    const image=<img src={'/api/site/posts/'+p.id+'/images/'+p.asset} alt={p.alt}/>
    return p.destination ? <Link key={p.id} to={p.destination}>{image}</Link> : <span key={p.id}>{image}</span>
  })}</div> : null
  return <section className="site-admin"><h1>{names[kind]}</h1>{!Array.isArray(rows)||!rows.every(post)
    ? <p role="status">{result ? '게시물을 불러올 수 없습니다.' : '불러오는 중입니다.'}</p>
    : <>{!rows.length && <p>공개된 게시물이 없습니다.</p>}{rows.map(p=>kind==='FAQ'
      ? <details key={p.id}><summary>{p.title}</summary><p>{p.body}</p></details>
      : <p key={p.id}><Link to={'/notices/'+p.id}>{p.title}</Link></p>)}
      <Pager page={page} next={rows.length===30} set={setPage}/></>}</section>
}
function Pager({page,next,set}:{page:number;next:boolean;set:(n:number)=>void}) {
  return <nav aria-label="목록 페이지">{page>0&&<button onClick={()=>set(page-1)}>이전</button>}{next&&<button onClick={()=>set(page+1)}>다음</button>}</nav>
}
export function SiteGuide() {
  const site=useRead('/api/site/info')?.data
  const ship=useRead('/api/site/shipping-policy')?.data
  return <section className="site-admin">{info(site)&&<><h2>{site.siteName}</h2><p>{site.supportGuide}</p></>}
    {policy(ship)&&<p>기본 배송비 {ship.baseFee.toLocaleString('ko-KR')}원 · {ship.freeThreshold.toLocaleString('ko-KR')}원 이상 무료배송</p>}
    <nav><Link to="/notices">공지사항</Link> · <Link to="/faq">자주 묻는 질문</Link> · <Link to="/support">고객지원</Link></nav></section>
}
export function SiteAdmin({ area }: { area:'posts'|'settings'|'jobs' }) {
  const auth=useAuthStore()
  const session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return session.member?.roles.includes('ADMIN') ? <div key={area+':'+auth.getGeneration()} className="site-admin">
    {area==='posts'?<PostAdmin/>:area==='settings'?<Settings/>:<Jobs/>}</div> : <p>운영 권한이 필요합니다.</p>
}
function blank(kind:Kind):Post {return {id:crypto.randomUUID(),version:0,kind,state:'DRAFT',title:'',body:'',alt:'',destination:null,priority:0,startsAt:null,endsAt:null,asset:null}}
function koreanTime(value:string|null) {return value?new Date(new Date(value).getTime()+9*3600000).toISOString().slice(0,19):''}
function utc(value:string) {return value?new Date(value+'+09:00').toISOString():null}
function PostAdmin(){
  const [kind,setKind]=useState<Kind>('NOTICE')
  const [page,setPage]=useState(0)
  const [generation,reload]=useState(0)
  const response=useRead('/api/admin/content/site-posts?kind='+kind+'&page='+page,generation)
  const [selected,select]=useState<Post|null>(null)
  const rows=response?.data
  const clear=()=>{select(null);reload(n=>n+1)}
  return <section><h2>사이트 게시 관리</h2><label>종류<select value={kind} onChange={e=>{setKind(e.target.value as Kind);setPage(0);select(null)}}>{kinds.map(k=><option key={k} value={k}>{names[k]}</option>)}</select></label>
    {Array.isArray(rows)&&rows.every(post)?<><button onClick={()=>select(blank(kind))}>새 {names[kind]} 작성</button><ul>{rows.map(p=><li key={p.id}><button onClick={()=>select(p)}>{p.title} · {p.state}</button></li>)}</ul>
      <Pager page={page} set={n=>{select(null);setPage(n)}} next={rows.length===30}/>
      {selected&&<PostEditor key={selected.id+':'+selected.version} initial={selected} clear={clear}/>}</>:<p role="status">{response?'게시 관리 권한이나 연결 상태를 확인해 주세요.':'불러오는 중입니다.'}</p>}</section>
}
function PostEditor({initial,clear}:{initial:Post;clear:()=>void}){
  const [p,set]=useState(initial)
  const [reason,setReason]=useState('')
  const [file,setFile]=useState<File>()
  const cmd=useInquiryCommand(clear,clear)
  const history=useRead('/api/admin/content/site-posts/'+initial.id+'/history')
  if(history?.failed)return <p role="alert">편집 권한을 다시 확인해 주세요.</p>
  return <form onSubmit={e=>{e.preventDefault();void cmd.send({path:'/api/admin/content/site-posts/'+p.id,file,data:{key:crypto.randomUUID(),expectedVersion:p.version,kind:p.kind,state:p.state,
    title:p.title,body:p.body,alt:p.alt,destination:p.destination,priority:p.priority,startsAt:p.startsAt,endsAt:p.endsAt,reason}})}}>
    <fieldset disabled={!!cmd.pending||p.state==='ARCHIVED'&&initial.state==='ARCHIVED'}><legend>{names[p.kind]} 편집</legend>
      <label>제목<input required value={p.title} onChange={e=>set({...p,title:e.target.value})}/></label>
      <label>내용<textarea required={p.kind!=='BANNER'} value={p.body} onChange={e=>set({...p,body:e.target.value})}/></label>
      {p.kind==='BANNER'&&<><label>배너 이미지<input type="file" accept="image/png,image/jpeg,image/webp" onChange={e=>setFile(e.target.files?.[0])}/></label>
        {p.asset&&<img alt={p.alt} src={'/api/admin/content/site-posts/'+p.id+'/images/'+p.asset}/>}
        <label>대체 텍스트<input value={p.alt} onChange={e=>set({...p,alt:e.target.value})}/></label><label>내부 이동 주소<input value={p.destination??''} onChange={e=>set({...p,destination:e.target.value||null})}/></label></>}
      <label>게시 상태<select value={p.state} onChange={e=>set({...p,state:e.target.value})}><option value="DRAFT">초안·공개 중단</option><option value="PUBLISHED">게시</option><option value="ARCHIVED">보관</option></select></label>
      <label>정렬 순서<input type="number" value={p.priority} onChange={e=>set({...p,priority:Number(e.target.value)})}/></label>
      <label>시작 시각 (한국)<input type="datetime-local" step="1" value={koreanTime(p.startsAt)} onChange={e=>set({...p,startsAt:utc(e.target.value)})}/></label>
      <label>종료 시각 (한국)<input type="datetime-local" step="1" value={koreanTime(p.endsAt)} onChange={e=>set({...p,endsAt:utc(e.target.value)})}/></label>
      <label>변경 사유<input required value={reason} onChange={e=>setReason(e.target.value)}/></label><button>게시 설정 저장</button></fieldset>
    <p role="status">{cmd.error}</p>{cmd.pending&&!cmd.busy&&<button type="button" onClick={()=>cmd.pending&&void cmd.send(cmd.pending)}>같은 요청 다시 확인</button>}
    {Array.isArray(history?.data)&&<ul aria-label="게시 변경 이력">{history.data.map(v=>object(v)&&post(v.post)&&typeof v.reason==='string'?<li key={v.post.version}>버전 {v.post.version} · {v.reason}</li>:null)}</ul>}</form>
}
function Settings(){
  const [rev,reload]=useState(0)
  const shipping=useRead('/api/admin/system/shipping-policy',rev)
  const site=useRead('/api/admin/system/site-info',rev)
  if(!Array.isArray(site?.data)||!Array.isArray(shipping?.data))
    return <p role="status">{site?.failed||shipping?.failed?'설정을 조회할 수 없습니다.':'불러오는 중입니다.'}</p>
  const latest=site.data[0],ship=shipping.data[0]
  if(!object(latest)||!info(latest.info)||!policy(ship))return <p role="alert">설정 버전을 확인할 수 없습니다.</p>
  return <section><h2>사이트 설정</h2><SettingForm key={'form'+rev} site={latest.info} ship={ship} clear={()=>reload(v=>v+1)}/>
    <h3>변경 이력</h3>{[shipping.data,site.data].map((items,i)=><ul key={i} aria-label={i?'사이트 안내 이력':'배송 정책 이력'}>{items.map(row=>{
      if(!object(row)||typeof row.reason!=='string')return null
      const version=info(row.info)?row.info.version:row.version
      return typeof version==='number'?<li key={version}>버전 {version} · {row.reason}</li>:null
    })}</ul>)}</section>
}
function SettingForm({site,ship,clear}:{site:Info;ship:Policy;clear:()=>void}){
  const [name,setName]=useState(site.siteName),[guide,setGuide]=useState(site.supportGuide)
  const [fee,setFee]=useState(String(ship.baseFee)),[threshold,setThreshold]=useState(String(ship.freeThreshold))
  const [reason,setReason]=useState(''),[target,setTarget]=useState('shipping-policy')
  const cmd=useInquiryCommand(clear,clear)
  return <form onSubmit={e=>{e.preventDefault();const common={key:crypto.randomUUID(),reason}
    void cmd.send({path:'/api/admin/system/'+target,data:target==='shipping-policy'
      ?{...common,expectedVersion:ship.version,baseFee:Number(fee),freeThreshold:Number(threshold)}
      :{...common,expectedVersion:site.version,siteName:name,supportGuide:guide}})}}>
    <fieldset disabled={!!cmd.pending}><legend>정책 변경</legend><label>설정 종류<select value={target} onChange={e=>setTarget(e.target.value)}><option value="shipping-policy">신규 견적 배송 정책</option><option value="site-info">사이트 이름·지원 안내</option></select></label>
    {target==='shipping-policy'?<><label>기본 배송비 (원)<input type="number" min="0" max="2147483647" step="1" required value={fee} onChange={e=>setFee(e.target.value)}/></label>
    <label>무료배송 기준 (원)<input type="number" min="0" max="2147483647" step="1" required value={threshold} onChange={e=>setThreshold(e.target.value)}/></label><p>저장 후 생성되는 견적에 적용됩니다.</p></>
    :<><label>사이트 이름<input required value={name} onChange={e=>setName(e.target.value)}/></label><label>고객지원 안내<textarea required value={guide} onChange={e=>setGuide(e.target.value)}/></label></>}
    <label>변경 사유<input required value={reason} onChange={e=>setReason(e.target.value)}/></label><button>설정 저장</button></fieldset>
    <p role="status">{cmd.error}</p>{cmd.pending&&!cmd.busy&&<button type="button" onClick={()=>cmd.pending&&void cmd.send(cmd.pending)}>같은 요청 다시 확인</button>}</form>
}
function Jobs(){
  const [rev,reload]=useState(0)
  const [status,setStatus]=useState('FAILED'),[cursor,setCursor]=useState<string[]>([]),[selected,select]=useState('')
  const list=useRead('/api/admin/system/site-jobs?status='+status+(cursor.length?'&after='+cursor[cursor.length-1]:''),rev)
  const rows=list?.data
  return <section><h2>작업 관리</h2><label>상태<select value={status} onChange={e=>{setStatus(e.target.value);setCursor([]);select('')}}>
    <option value="FAILED">실패</option><option value="READY">대기</option><option value="RUNNING">진행</option><option value="SUCCEEDED">성공</option></select></label>
    {Array.isArray(rows)&&rows.every(job)?<>{!rows.length&&<p>표시할 작업이 없습니다.</p>}<ul>{rows.map(row=><li key={row.id}><button onClick={()=>select(row.id)}>{row.type} · {row.id}</button></li>)}</ul>
    {cursor.length>0&&<button onClick={()=>{setCursor(cursor.slice(0,-1));select('')}}>이전</button>}{rows.length===40&&<button onClick={()=>{setCursor([...cursor,rows[39].id]);select('')}}>다음</button>}
    {selected&&<JobDetail key={selected} id={selected} clear={()=>{select('');setCursor([]);reload(n=>n+1)}}/>}</>:<p role="status">{list?'작업을 조회할 수 없습니다.':'불러오는 중입니다.'}</p>}</section>
}
function JobDetail({id,clear}:{id:string;clear:()=>void}){
  const r=useRead('/api/admin/system/site-jobs/'+id)
  if(!job(r?.data))return <p role="status">{r?'작업 상세를 조회할 수 없습니다.':'불러오는 중입니다.'}</p>
  const j=r.data
  return <section><h3>작업 상세</h3><p>{j.id} · {j.type} · {j.status}</p><p>시도 {j.attempts}회 · 예약 {koreanTime(j.scheduledAt)} · 다음 실행 {koreanTime(j.nextRunAt)} (한국)</p>
    {j.error&&<p>오류 코드 {j.error}</p>}{j.status==='FAILED'&&j.type==='NOTIFICATION_DELIVER.v1'?<Link to="/admin/notifications">실패한 알림 재처리</Link>:
      j.status==='FAILED'&&j.type==='DASHBOARD_REFRESH.v1'?<DashboardFollowup id={id} clear={clear}/>:j.type==='REFUND_SUBMIT.v1'?<Link to="/admin/refunds">환불 조회·운영 대사</Link>:<p>조회 전용입니다. 이 상태와 유형에는 운영 후속 명령이 없습니다.</p>}</section>
}
function DashboardFollowup({id,clear}:{id:string;clear:()=>void}){
  const [reason,setReason]=useState(''),[done,setDone]=useState(false)
  const cmd=useInquiryCommand(()=>{setReason('');setDone(true)},clear)
  return <>{done?<Link to="/admin/dashboard">현재 대시보드 확인</Link>:<><label>재집계 사유<input value={reason} disabled={!!cmd.pending} maxLength={300} onChange={e=>setReason(e.target.value)}/></label>
    <button disabled={!!cmd.pending||!reason.trim()} onClick={()=>void cmd.send({path:'/api/admin/system/dashboard/failed/'+id+'/refresh',
      data:{key:crypto.randomUUID(),reason:reason.trim()}})}>새 집계 요청</button></>}<p role="status">{cmd.error}</p>
    {cmd.pending&&!cmd.busy&&<button onClick={()=>cmd.pending&&void cmd.send(cmd.pending)}>같은 요청 다시 확인</button>}</>
}
