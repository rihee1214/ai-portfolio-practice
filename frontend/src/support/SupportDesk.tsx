import {useEffect,useState,useSyncExternalStore} from 'react'
import {requestJson} from '../api/http'
import {useAuthStore} from '../auth/AuthContext'
import {guestAccessGeneration,onGuestAccessChange} from '../guest/GuestAccessLifecycle'
import {InquiryImages} from '../content/InquiryInputs'
import {useInquiryCommand} from '../content/useInquiryCommand'
import {ConfirmedRefundPanel} from './ConfirmedRefundPanel'

type Reader='CUSTOMER'|'SELLER'|'OPERATOR'
type Props={reader?:Reader;number?:string;sellerOrder?:string}
type Entry={id:string;kind:string;version:number}
type QueueEntry={targetId:string;kind:string;assigned:boolean;version:number}
type Message={id:string;sender:Reader;body:string;version:number;mine:boolean}
type Detail=Entry&{orderNumber:string|null;sellerOrderId:string|null;messages:Message[]}
const valid=(value:string)=>!!value.trim()&&[...value.trim()].length<=2000

export function SupportDesk(props:Props) {
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  const gas=useSyncExternalStore(onGuestAccessChange,guestAccessGeneration)
  return <Desk key={[session.member?.id,auth.getGeneration(),gas,props.number,props.sellerOrder,props.reader].join(':')}
    {...props} member={session.member?.id}/>
}
function Desk({reader='CUSTOMER',number,sellerOrder,member}:Props&{member?:string}){
  const candidate=new URLSearchParams(window.location.search).get('case') ?? ''
  const initialCase=/^[0-9a-f-]{36}$/i.test(candidate)?candidate:''
  const [open,setOpen]=useState(!!initialCase)
  const [generation,reset]=useState(0)
  return <section aria-label={sellerOrder?'주문 문의':'고객지원'}><h2>{sellerOrder?'주문 문의':'고객지원'}</h2>
    {open?<CaseList key={generation} initialCase={generation===0?initialCase:''} reader={reader} number={number} sellerOrder={sellerOrder} member={member} onAccessLost={()=>reset(n=>n+1)}/>:
      <button onClick={()=>setOpen(true)}>{sellerOrder?'주문 문의 열기':'지원 접수함 열기'}</button>}
  </section>
}
function CaseList({reader='CUSTOMER',number,sellerOrder,member,onAccessLost,initialCase}:Props&{member?:string;initialCase:string;onAccessLost:()=>void}){
  const [list,setList]=useState<Entry[]>([]),[queue,setQueue]=useState<QueueEntry[]>([])
  const [detail,setDetail]=useState<Detail|null>(null),[selected,select]=useState(initialCase)
  const [body,setBody]=useState(''),[next,setNext]=useState(''),[error,setError]=useState('')
  const [revision,reload]=useState(0)
  const clear=()=>{setList([]);setQueue([]);setDetail(null);select('');setBody('');setNext('');reload(n=>n+1)}
  const cmd=useInquiryCommand(request=>{
    if(request.path.startsWith('/api/admin/support/assignments/')){clear();return}
    setBody('');setNext('');reload(n=>n+1)
  },clear)
  useEffect(()=>{
    const controller=new AbortController()
    const q=new URLSearchParams({reader})
    if(number)q.set('number',number)
    if(sellerOrder)q.set('sellerOrder',sellerOrder)
    const path=reader==='OPERATOR'?'/api/admin/support/queue':'/api/support/cases?'+q
    void requestJson(path,{signal:controller.signal}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&Array.isArray(r.data)){
        if(reader==='OPERATOR')setQueue(r.data);else setList(r.data)
        setError('')
      }else{setList([]);setQueue([]);setDetail(null);select('');setBody('');setError('현재 자격으로 접수함을 열 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[reader,number,sellerOrder,revision])
  useEffect(()=>{
    if(!selected)return
    const controller=new AbortController()
    void requestJson('/api/support/cases/'+selected+'?reader='+reader,{signal:controller.signal}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'messages' in r.data)setDetail(r.data as Detail)
      else{setDetail(null);select('');setBody('');setError('접수 내용을 열 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[selected,reader,revision])
  const assign=(target:QueueEntry,operator:string)=>void cmd.send({path:'/api/admin/support/assignments/'+target.targetId,
    data:{key:crypto.randomUUID(),expectedVersion:target.version,operator}})
  function pick(id:string){select(id);setDetail(null);setBody('');setError('')}
  return <div>
    {list.map(row=><button key={row.id} onClick={()=>pick(row.id)}>{row.kind==='ORDER'?'주문 문의':'지원 접수'} {row.id.slice(-8)}</button>)}
    {queue.map(row=><article key={row.targetId}><p>{row.kind==='PRODUCT_QUESTION'?'상품 문의':row.kind==='LOST_ACCESS'?'자격 상실 지원':row.kind==='ORDER'?'주문 문의':'고객지원'} · {row.targetId.slice(-8)}</p>
      {!row.assigned?<button disabled={!!cmd.pending||!member} onClick={()=>assign(row,member!)}>내가 담당</button>:
        <>{row.kind==='PRODUCT_QUESTION'?<AssignedQuestion id={row.targetId} onAccessLost={onAccessLost}/>:<button onClick={()=>pick(row.targetId)}>본문 열기</button>}
        <label>새 담당자 ID <input value={next} onChange={e=>setNext(e.target.value)}/></label>
        <button disabled={!next||!!cmd.pending} onClick={()=>assign(row,next)}>재배정</button></>}
    </article>)}
    {reader==='OPERATOR'&&detail?.id===selected&&detail.kind==='ORDER'&&<dl>
      <dt>원 주문번호</dt><dd style={{overflowWrap:'anywhere'}}>{detail.orderNumber}</dd>
      <dt>판매자 주문 ID</dt><dd style={{overflowWrap:'anywhere'}}>{detail.sellerOrderId}</dd>
    </dl>}
    {reader==='OPERATOR'&&detail?.id===selected&&detail.kind==='ORDER'&&
      <ConfirmedRefundPanel key={detail.id+':'+detail.version} id={detail.id} version={detail.version} onAccessLost={onAccessLost}/>}
    {detail?.id===selected&&detail.messages.map(m=><article key={m.id}>
      <strong>{m.sender==='CUSTOMER'?'고객':m.sender==='SELLER'?'판매자':'담당 운영자'}</strong>
      <p style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{m.body}</p>
      <InquiryImages base={'/api/support/cases/'+detail.id+'/messages/'+m.id+'/images'} query={'reader='+reader} edit={m.mine} onAccessLost={onAccessLost}/>
    </article>)}
    {(reader==='CUSTOMER'&&!selected || detail?.id===selected&&detail) && <fieldset disabled={!!cmd.pending}><legend>{detail?'답변 작성':'문의 접수'}</legend>
      <label>메시지 본문 <textarea value={body} onChange={e=>setBody(e.target.value)}/></label>
      <button disabled={!valid(body)} onClick={()=>void cmd.send(detail?{path:'/api/support/cases/'+detail.id+'/messages?reader='+reader,
        data:{key:crypto.randomUUID(),expectedVersion:detail.version,body}}:{path:'/api/support/cases',
          data:{key:crypto.randomUUID(),body,orderNumber:number??null,sellerOrderId:sellerOrder??null}})}>{detail?'답변 보내기':'문의 접수하기'}</button>
    </fieldset>}
    {selected&&<button onClick={()=>pick('')}>목록으로</button>}
    {(error||cmd.error)&&<p role="alert">{error||cmd.error}</p>}
    {cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 요청으로 결과 확인</button>}
  </div>
}
function AssignedQuestion({id,onAccessLost}:{id:string;onAccessLost:()=>void}) {
  const [question,setQuestion]=useState<{body:string;answer:string|null;answerId:string|null}|null>(null)
  const [attempt,open]=useState(0),[error,setError]=useState('')
  useEffect(()=>{
    if(!attempt)return
    const c=new AbortController()
    void requestJson('/api/questions/'+id,{signal:c.signal}).then(r=>{
      if(c.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'body' in r.data)setQuestion(r.data as NonNullable<typeof question>)
      else{setQuestion(null);setError('담당 문의를 열 수 없습니다.')}
    })
    return ()=>c.abort()
  },[id,attempt])
  return <><button onClick={()=>open(n=>n+1)}>담당 문의 열기</button>
    {question&&<><p>{question.body}</p><InquiryImages base={'/api/questions/'+id+'/messages/'+id+'/images'} edit={false} onAccessLost={onAccessLost}/>
      {question.answerId&&<><p>{question.answer}</p><InquiryImages base={'/api/questions/'+id+'/messages/'+question.answerId+'/images'} edit={false} onAccessLost={onAccessLost}/></>}</>}
    {error&&<p role="alert">{error}</p>}</>
}
export function LostAccessForm(){
  const [body,setBody]=useState(''),[received,setReceived]=useState(false)
  const cmd=useInquiryCommand(()=>{setBody('');setReceived(true)},()=>setBody(''))
  return <section><h1>조회 자격 상실 지원</h1>{received?<p role="status">지원을 접수했습니다.</p>:<>
    <label>지원 요청 내용 <textarea value={body} disabled={!!cmd.pending} onChange={e=>setBody(e.target.value)}/></label>
    <button disabled={!!cmd.pending||!valid(body)} onClick={()=>void cmd.send({path:'/api/support/lost-access',data:{key:crypto.randomUUID(),body}})}>지원 접수</button>
    {cmd.error&&<p role="alert">{cmd.error}</p>}{cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 요청으로 확인</button>}</>}</section>
}
