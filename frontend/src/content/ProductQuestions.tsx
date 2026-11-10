import {useCallback,useEffect,useRef,useState,useSyncExternalStore} from 'react'
import {useAuthStore} from '../auth/AuthContext'
import {requestJson} from '../api/http'
import {InquiryImages} from './InquiryInputs'
import {ContentReport} from '../reports/ProductReport'

type Question = {id:string;visibility:'PUBLIC'|'PRIVATE';body:string;answer:string|null;answerId:string|null;version:number;canAnswer:boolean;ownQuestion:boolean;ownAnswer:boolean;canReport:boolean}
type Pending = {path:string;data:string}

export function ProductQuestions({slug,defaultOpen=false}:{slug:string;defaultOpen?:boolean}) {
  const auth=useAuthStore()
  const session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  const [generation,reset]=useState(0)
  const accessLost=useCallback(()=>reset(n=>n+1),[])
  const [open,setOpen]=useState(defaultOpen)
  return <section aria-label="상품 문의"><h2>상품 문의</h2>
    <button aria-expanded={open} onClick={()=>setOpen(!open)}>{open?'상품 문의 접기':'상품 문의 열기'}</button>
    {open && session.checked && !session.busy && <Questions key={[slug,session.member?.id,generation].join(':')}
      slug={slug} member={!!session.member} onAccessLost={accessLost}/>}
  </section>
}

function Questions({slug,member,onAccessLost}:{slug:string;member:boolean;onAccessLost:()=>void}) {
  const path='/api/products/'+encodeURIComponent(slug)+'/questions'
  const [rows,setRows]=useState<Question[]>([])
  const [text,setText]=useState('')
  const [answer,setAnswer]=useState<Record<string,string>>({})
  const [visibility,setVisibility]=useState('PRIVATE')
  const [revision,refresh]=useState(0)
  const [pending,setPending]=useState<Pending|null>(null)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const active=useRef(true)
  useEffect(()=>{
    active.current=true
    const controller=new AbortController()
    void requestJson(path,{signal:controller.signal}).then(result=>{
      if(controller.signal.aborted)return
      if(result.kind==='success' && Array.isArray(result.data))setRows(result.data)
      else {setRows([]);setAnswer({});setText('');setError('상품 문의를 조회할 수 없습니다.')}
    })
    return ()=>{active.current=false;controller.abort()}
  },[path,revision])
  async function send(cmd:Pending){
    setBusy(true);setPending(cmd)
    const csrf=await requestJson('/api/auth/csrf')
    if(!active.current)return
    if(csrf.kind!=='success'||!csrf.data||typeof csrf.data!=='object'||!('token' in csrf.data)||typeof csrf.data.token!=='string'){
      if(csrf.kind==='http'&&[401,403,404].includes(csrf.status)){onAccessLost();return}
      setPending(null);setBusy(false);setRows([]);setText('');setAnswer({});
      setError('로그인 상태를 확인해 주세요.');refresh(n=>n+1);return
    }
    const r=await requestJson(cmd.path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:cmd.data})
    if(!active.current)return
    setBusy(false)
    if(r.kind==='success'){setPending(null);setText('');setAnswer({});setError('');refresh(n=>n+1)}
    else if(r.kind==='http'&&r.status<500){
      if([401,403,404].includes(r.status)){onAccessLost();return}
      setPending(null);setRows([]);setText('');setAnswer({});setError('현재 권한과 문의 상태를 다시 확인해 주세요.');refresh(n=>n+1)
    }else setError('저장 결과를 확인하지 못했습니다.')
  }
  const valid=(value:string)=>!!value.trim()&&[...value.trim()].length<=2000
  return <div>
    {member && <fieldset disabled={!!pending}><legend>문의 작성</legend>
      <label>공개 범위 <select value={visibility} onChange={e=>setVisibility(e.target.value)}>
        <option value="PRIVATE">비밀</option><option value="PUBLIC">공개</option></select></label>
      <p>{visibility==='PUBLIC'?'본문·답변·첨부 이미지를 누구나 볼 수 있습니다.':'작성자·해당 판매자·담당 운영자가 열람할 수 있습니다.'} 등록 후 공개 범위는 고정됩니다.</p>
      <label>문의 본문 <textarea value={text} onChange={e=>setText(e.target.value)}/></label>
      <button disabled={!valid(text)} onClick={()=>void send({path,data:JSON.stringify({key:crypto.randomUUID(),visibility,body:text})})}>문의 등록</button>
    </fieldset>}
    <ul>{rows.map(q=><li key={q.id}><span>{q.visibility==='PRIVATE'?'비밀 문의':'공개 문의'}</span>
      <p style={{overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>{q.body}</p>
      <InquiryImages base={'/api/questions/'+q.id+'/messages/'+q.id+'/images'} edit={q.ownQuestion} onAccessLost={onAccessLost}/>
      {q.canReport && <ContentReport kind="QUESTION" parent={q.id} id={q.id} edition={0} onAccessLost={onAccessLost}/>}
      {q.answer && <p style={{overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>판매자 답변 · {q.answer}</p>}
      {q.answerId && <InquiryImages base={'/api/questions/'+q.id+'/messages/'+q.answerId+'/images'} edit={q.ownAnswer} onAccessLost={onAccessLost}/>}
      {q.canReport && q.answerId && <ContentReport kind="ANSWER" parent={q.id} id={q.answerId} edition={0} onAccessLost={onAccessLost}/>}
      {q.canAnswer && <fieldset disabled={!!pending}><label>답변 본문 <textarea value={answer[q.id]??''}
        onChange={e=>setAnswer({...answer,[q.id]:e.target.value})}/></label>
        <button disabled={!valid(answer[q.id]??'')} onClick={()=>void send({path:'/api/questions/'+q.id+'/answer',
          data:JSON.stringify({key:crypto.randomUUID(),expectedVersion:q.version,body:answer[q.id]})})}>답변 등록</button></fieldset>}
    </li>)}</ul>
    {error&&<p role="alert">{error}</p>}
    {pending&&!busy&&<button onClick={()=>void send(pending)}>같은 요청으로 결과 확인</button>}
  </div>
}
