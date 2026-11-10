import {useCallback,useEffect,useState,useSyncExternalStore} from 'react'
import {Link,useSearchParams} from 'react-router'
import {useAuthStore} from '../auth/AuthContext'
import {useInquiryCommand} from '../content/useInquiryCommand'
import {requestJson} from '../api/http'

const base='/api/admin/content/reports'
const states:Record<string,string>={RECEIVED:'접수',INVESTIGATING:'조사 중',CLOSED:'종결'}
const actions:Record<string,string>={ASSIGN:'담당 배정',START:'조사 시작',CLOSE:'종결',REOPEN:'재개'}
const decisions:Record<string,string>={VIOLATION:'위반 확인',NO_VIOLATION:'위반 미확인',INCONCLUSIVE:'확인 불가'}
type Row={reportId:string;state:string;mine:boolean;available:boolean;version:number}
type Detail=Row&{kind:string;targetId:string;decision:string|null;evidence:{key:string;reason:string;assetId:string|null;version:number;outcome:string}[];
  history:{version:number;command:string;before:string;after:string;actor:string;reason:string;decision:string|null;occurredAt:string;reopenBasis:string|null;evidenceKey:string|null}[]}

export function ReportQueue(){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  const [q,setQ]=useSearchParams()
  const state=q.get('state')??'',page=Number(q.get('page')??0)
  return <section><h1>신고 접수 목록</h1><label>상태 필터 <select value={state} onChange={e=>setQ(e.target.value?{state:e.target.value}:{})}>
    <option value="">전체</option>{Object.entries(states).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
    {session.checked&&!session.busy&&session.member && <Queue key={[session.member.id,state,page].join(':')}
      member={session.member.id} state={state} page={page} changePage={n=>setQ(state?{state,page:String(n)}:{page:String(n)})}/>}
  </section>
}
function Queue({member,state,page,changePage}:{member:string;state:string;page:number;changePage:(page:number)=>void}){
  const [rows,setRows]=useState<Row[]>([]),[selected,select]=useState(''),[revision,reload]=useState(0),[notice,setNotice]=useState('')
  const [failure,setFailure]=useState('')
  const reset=useCallback(()=>{select('');setRows([]);reload(n=>n+1)},[])
  const recheckSource=useCallback(()=>{
    setFailure('현재 열 수 있는 원문이 없습니다.')
    reload(n=>n+1)
  },[])
  const denied=useCallback((status?:number)=>{
    setFailure(status===409?'사건 상태가 변경됐습니다. 다시 조회한 뒤 처리해 주세요.':'사건 열람·처리 자격을 확인해 주세요.')
    reset()
  },[reset])
  const cmd=useInquiryCommand(()=>{setFailure('');reset()},denied)
  useEffect(()=>{
    const controller=new AbortController()
    const params=new URLSearchParams({page:String(page)});if(state)params.set('state',state)
    void requestJson(base+'?'+params,{signal:controller.signal,cache:'no-store'}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&Array.isArray(r.data)){setRows(r.data);setNotice(r.data.length?'':'접수된 신고가 없습니다.')}
      else{select('');setRows([]);setNotice('신고 목록을 열 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[page,state,revision])
  return <>{notice&&<p role="status">{notice}</p>}{rows.map(row=><article key={row.reportId}>
    <p>{row.reportId} · {states[row.state]}</p>
    {row.mine?<><button onClick={()=>{setFailure('');select(row.reportId)}}>사건 열기</button>
      <Reassign key={row.version} disabled={!!cmd.pending} move={operator=>void cmd.send({path:base+'/'+row.reportId+'/commands',
        data:{key:crypto.randomUUID(),version:row.version,action:'ASSIGN',operator}})}/></>:
      row.available?<button disabled={!!cmd.pending} onClick={()=>void cmd.send({path:base+'/'+row.reportId+'/commands',
        data:{key:crypto.randomUUID(),version:row.version,action:'ASSIGN',operator:member}})}>내가 담당</button>:<span>다른 담당자 처리 중</span>}
  </article>)}
    <nav aria-label="신고 목록 페이지"><button disabled={page<=0} onClick={()=>changePage(page-1)}>이전</button>
      <button disabled={rows.length<20} onClick={()=>changePage(page+1)}>다음</button></nav>
    {selected&&<Investigation key={selected+':'+revision} id={selected} reset={reset} denied={denied} recheckSource={recheckSource}/>}
    {(failure||cmd.error)&&<p role="alert">{failure||cmd.error}</p>}{cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 배정 요청 확인</button>}
  </>
}
function Reassign({disabled,move}:{disabled:boolean;move:(operator:string)=>void}){
  const [operator,setOperator]=useState('')
  return <fieldset disabled={disabled}><label>새 담당자 ID <input value={operator} onChange={e=>setOperator(e.target.value)}/></label>
    <button disabled={!operator.trim()} onClick={()=>move(operator.trim())}>재배정</button></fieldset>
}
function Investigation({id,reset,denied,recheckSource}:{id:string;reset:()=>void;denied:(status?:number)=>void;recheckSource:()=>void}){
  const path=base+'/'+id
  const [detail,setDetail]=useState<Detail|null>(null),[source,setSource]=useState<string|null>(null)
  const [reason,setReason]=useState(''),[decision,choose]=useState('VIOLATION')
  const [look,read]=useState(0),[error,setError]=useState('')
  const [basis,setBasis]=useState('JUDGMENT_ERROR'),[evidenceKey,setEvidenceKey]=useState('')
  const cmd=useInquiryCommand(reset,denied)
  useEffect(()=>{
    const c=new AbortController()
    void requestJson(path,{signal:c.signal,cache:'no-store'}).then(r=>{
      if(c.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'reportId' in r.data && r.data.reportId===id){setDetail(r.data as Detail)}
      else{setDetail(null);setError('사건 열람 권한을 확인해 주세요.')}
    })
    return ()=>c.abort()
  },[path,id])
  useEffect(()=>{
    if(!look)return
    const c=new AbortController()
    void requestJson(path+'/source',{signal:c.signal,cache:'no-store'}).then(r=>{
      if(c.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'body' in r.data&&typeof r.data.body==='string'){setSource(r.data.body);return}
      // Remount the case, dropping every draft, receipt and outstanding read before loading authorized history.
      recheckSource()
    })
    return ()=>c.abort()
  },[look,path,recheckSource])
  function send(action:string){
    if(!detail)return
    void cmd.send({path:path+'/commands',data:{key:crypto.randomUUID(),version:detail.version,action,
      reason,decision:action==='CLOSE'?decision:null,
      reopenBasis:action==='REOPEN'?basis:null,evidenceKey:action==='REOPEN'&&basis==='NEW_EVIDENCE'?evidenceKey:null}})
  }
  return <section aria-label="신고 조사">
    {detail&&<><h2>신고 조사</h2><p>{states[detail.state]} {detail.decision&&decisions[detail.decision]}</p>
      {['PRODUCT','REVIEW','COMMENT','SELLER_REPLY'].includes(detail.kind)&&<Link to={'/admin/moderation?report='+id}>콘텐츠 조치 확인</Link>}
      {detail.evidence.map(e=><div key={e.key}><p style={{overflowWrap:'anywhere'}}>{e.reason}</p>
        {e.assetId&&<a href={path+'/evidence/'+e.key+'/image'}>신고한 첨부 보기</a>}</div>)}
      <button onClick={()=>{setSource(null);setError('');read(n=>n+1)}}>현재 원문 확인</button>{source!==null&&<p style={{overflowWrap:'anywhere'}}>{source}</p>}
      <ol>{detail.history.map(h=><li key={h.version}>{actions[h.command]} · {states[h.before]} → {states[h.after]} · {h.actor} · {h.occurredAt}
        {h.decision&&' · '+decisions[h.decision]} {h.reopenBasis==='NEW_EVIDENCE'?'새 근거 '+h.evidenceKey:h.reopenBasis==='JUDGMENT_ERROR'?'판단 오류 정정':''}
        <span style={{overflowWrap:'anywhere'}}>{h.reason}</span></li>)}</ol>
      <fieldset disabled={!!cmd.pending}>
        <label>판단 사유 <textarea value={reason} onChange={e=>setReason(e.target.value)}/></label>
        {detail.state==='RECEIVED'&&<button onClick={()=>send('START')}>조사 시작</button>}
        {detail.state==='INVESTIGATING'&&<><label>조사 결과 <select value={decision} onChange={e=>choose(e.target.value)}>
          {Object.entries(decisions).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
          <button disabled={!reason.trim()} onClick={()=>send('CLOSE')}>사건 종결</button></>}
        {detail.state==='CLOSED'&&<><label>재개 근거 <select value={basis} onChange={e=>{setBasis(e.target.value);setEvidenceKey('')}}>
          <option value="JUDGMENT_ERROR">판단 오류 정정</option><option value="NEW_EVIDENCE">종결 후 새 근거</option></select></label>
          {basis==='NEW_EVIDENCE'&&<label>확인한 근거 <select value={evidenceKey} onChange={e=>setEvidenceKey(e.target.value)}>
            <option value="">선택</option>{detail.evidence.filter(e=>e.outcome==='REOPEN_REQUESTED'&&e.version>(detail.history.filter(h=>h.command==='CLOSE').at(-1)?.version??Infinity))
              .map(e=><option key={e.key} value={e.key}>{e.reason}</option>)}</select></label>}
          <button disabled={!reason.trim()||basis==='NEW_EVIDENCE'&&!evidenceKey} onClick={()=>send('REOPEN')}>사건 재개</button></>}
      </fieldset></>}
    {(error||cmd.error)&&<p role="alert">{error||cmd.error}</p>}
    {cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 조사 요청 확인</button>}
  </section>
}
