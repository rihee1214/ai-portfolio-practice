import {useCallback,useEffect,useRef,useState,useSyncExternalStore,type FormEvent} from 'react'
import {useAuthStore} from '../auth/AuthContext'
import {requestJson} from '../api/http'

const labels:Record<string,string>={ACTIVE:'활성',SUSPENDED:'정지',PENDING_EMAIL:'이메일 확인 대기',WITHDRAWN:'탈퇴'}
type Member={id:string;state:string;version:number;administrator:boolean}
type Event={id:string;actor:string;before:string;after:string;reason:string;at:string}
type Detail={member:Member;events:Event[]}
type Page={members:Pick<Member,'id'|'state'>[];next:string|null}
const base='/api/admin/accounts'
async function post(path:string,data:unknown,signal:AbortSignal){
  const token=await requestJson('/api/auth/csrf',{signal,cache:'no-store'})
  if(token.kind!=='success')return token
  if(!token.data||typeof token.data!=='object'||!('token' in token.data)||typeof token.data.token!=='string')return {kind:'invalid-response'} as const
  return requestJson(path,{method:'POST',signal,cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':token.data.token},body:JSON.stringify(data)})
}
export function ManagedAccountsPage(){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return session.checked&&!session.busy&&session.member?<AccountWorkspace key={session.member.id}/>:null
}
function AccountWorkspace(){
  const [denied,setDenied]=useState(false)
  const onDenied=useCallback(()=>setDenied(true),[])
  return denied?<section><h1>회원 관리</h1><p role="alert">현재 회원 관리 권한을 확인해 주세요.</p>
    <button onClick={()=>setDenied(false)}>권한 다시 확인</button></section>:<Directory onDenied={onDenied}/>
}
function Directory({onDenied}:{onDenied:()=>void}){
  const [term,setTerm]=useState(''),[state,setState]=useState('')
  const [query,setQuery]=useState({term:'',state:'',after:null as string|null})
  const [page,setPage]=useState<Page|null>(null),[id,select]=useState(''),[notice,setNotice]=useState(''),[revision,refresh]=useState(0)
  function search(next:typeof query){select('');setPage(null);setNotice('');setQuery(next)}
  useEffect(()=>{
    const controller=new AbortController()
    void post(base+'/search',{...query,state:query.state||null},controller.signal).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'members' in r.data&&Array.isArray(r.data.members)){
        setPage(r.data as Page);setNotice(r.data.members.length?'':'검색 결과가 없습니다.')
      }else if(r.kind==='http'&&[401,403].includes(r.status)){onDenied()}
      else{setPage(null);select('');setNotice('현재 검색 조건을 확인해 주세요.')}
    })
    return ()=>controller.abort()
  },[query,revision,onDenied])
  return <section><h1>회원 관리</h1><form onSubmit={e=>{e.preventDefault();search({term:term.trim(),state,after:null})}}>
    <label>회원 ID 또는 정확한 이메일 <input value={term} onChange={e=>setTerm(e.target.value)} maxLength={320}/></label>
    <label>계정 상태 <select value={state} onChange={e=>setState(e.target.value)}><option value="">전체</option>
      {Object.entries(labels).map(([k,name])=><option key={k} value={k}>{name}</option>)}</select></label>
    <button>회원 검색</button></form>
    {page?.members.map(m=><p key={m.id} style={{overflowWrap:'anywhere'}}>{m.id} · {labels[m.state]} <button aria-label={m.id+' 상세'} onClick={()=>select(m.id)}>상세</button></p>)}
    {query.after&&<button onClick={()=>search({...query,after:null})}>처음으로</button>}
    {page?.next&&<button onClick={()=>search({...query,after:page.next})}>다음 목록</button>}
    {notice&&<p role="status">{notice}</p>}
    {id&&<Account key={id} id={id} onDenied={onDenied} refreshList={confirmed=>{setPage(null)
      if(confirmed)refresh(n=>n+1)
      else setNotice('목록을 다시 보려면 회원 검색을 눌러 주세요.')
    }}/> }</section>
}
function Account({id,refreshList,onDenied}:{id:string;refreshList:(confirmed:boolean)=>void;onDenied:()=>void}){
  const [detail,setDetail]=useState<Detail|null>(null),[reason,setReason]=useState(''),[message,setMessage]=useState('')
  const [revision,reload]=useState(0)
  const [saving,setSaving]=useState(false)
  const request=useRef<AbortController|null>(null)
  useEffect(()=>{
    const c=new AbortController()
    void requestJson(base+'/members/'+id,{signal:c.signal,cache:'no-store'}).then(r=>{
      if(c.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'member' in r.data&&(r.data as Detail).member?.id===id&&Array.isArray((r.data as Detail).events)){
        setDetail(r.data as Detail)
      }else if(r.kind==='http'&&[401,403].includes(r.status)){onDenied()}
      else{setDetail(null);setMessage('회원 상태와 이력을 확인할 수 없습니다.')}
    })
    return ()=>c.abort()
  },[id,revision,onDenied])
  useEffect(()=>()=>{request.current?.abort()},[])
  async function change(e:FormEvent){
    e.preventDefault()
    if(!detail||!reason.trim()||request.current)return
    const c=new AbortController();request.current=c;setSaving(true)
    const action=detail.member.state==='ACTIVE'?'suspend':'restore'
    setDetail(null);setReason('');setMessage('처리 결과를 확인하고 있습니다.')
    const result=await post(base+'/'+id+'/'+action,{reason:reason.trim(),expectedVersion:detail.member.version},c.signal)
    if(c.signal.aborted)return
    request.current=null;setSaving(false)
    if(result.kind==='http'&&[401,403].includes(result.status)){onDenied();return}
    setMessage(result.kind==='success'?'계정 조치를 저장했습니다.':
      result.kind==='http'&&result.status===409?'다른 조치가 먼저 처리됐습니다. 최신 상태와 이력을 확인해 주세요.':'처리 결과를 확정할 수 없습니다. 최신 상태와 이력을 확인해 주세요.')
    reload(n=>n+1);refreshList(result.kind==='success')
  }
  return <section aria-label="회원 상세"><h2>회원 상세</h2>{message&&<p role="status">{message}</p>}
    <button disabled={saving} onClick={()=>{setReason('');setDetail(null);reload(n=>n+1)}}>상태·이력 다시 확인</button>
    {detail&&<><p style={{overflowWrap:'anywhere'}}>{id} · {labels[detail.member.state]}</p>
      {!detail.member.administrator&&['ACTIVE','SUSPENDED'].includes(detail.member.state)&&<form onSubmit={e=>void change(e)}>
        <label>계정 조치 사유 <input value={reason} onChange={e=>setReason(e.target.value)} required/></label>
        <button disabled={!reason.trim()||Array.from(reason.trim()).length>300}>{detail.member.state==='ACTIVE'?'로그인 정지':'로그인 복원'}</button></form>}
      <h3>최근 계정 조치 이력 (최대 20건)</h3>
      {!detail.events.length&&<p>계정 조치 이력이 없습니다.</p>}
      <ol>{detail.events.map(ev=><li key={ev.id} style={{overflowWrap:'anywhere'}}>{labels[ev.before]} → {labels[ev.after]} · {ev.actor} · {ev.at}<p>{ev.reason}</p></li>)}</ol>
    </>}</section>
}
