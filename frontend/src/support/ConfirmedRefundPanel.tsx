import {useEffect,useState} from 'react'
import {requestJson} from '../api/http'
import {useInquiryCommand} from '../content/useInquiryCommand'

type Entry={collection:string;item:string;ordinal:number;inspection:string|null;responsibility:string|null;
  approval:string|null;amount:string|null;execution:string|null;attempt:string|null;result:string|null;
  shippingAttempt:string|null;shippingState:string|null}
type Props={id:string;version:number;onAccessLost:()=>void}

export function ConfirmedRefundPanel({id,version,onAccessLost}:Props){
  const base='/api/admin/support/cases/'+id+'/confirmed-refunds'
  const [opened,open]=useState(false),[revision,refresh]=useState(0)
  const [entries,setEntries]=useState<Entry[]|null>(null),[error,setError]=useState('')
  const [item,setItem]=useState(''),[ordinal,setOrdinal]=useState('0'),[reference,setReference]=useState('')
  const [reason,setReason]=useState(''),[responsibility,setResponsibility]=useState('')
  const [confirm,setConfirm]=useState(false)
  const cmd=useInquiryCommand(()=>{
    setEntries(null);setReference('');setReason('');setResponsibility('');setConfirm(false);refresh(n=>n+1)
  },status=>{
    setEntries(null);setConfirm(false)
    if(status===401||status===403||status===404)onAccessLost()
    else refresh(n=>n+1)
  })
  useEffect(()=>{
    if(!opened)return
    const controller=new AbortController()
    void requestJson(base,{signal:controller.signal}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&Array.isArray(r.data)){setEntries(r.data);setError('')}
      else if(r.kind==='http'&&[401,403,404].includes(r.status)){onAccessLost()}
      else{setEntries(null);setError('담당 권한과 문의 상태를 확인해 주세요.')}
    })
    return ()=>controller.abort()
  },[base,opened,revision,onAccessLost])
  const row=entries?.find(r=>r.item===item&&r.ordinal===Number(ordinal))
  const send=(action:string,fields:object)=>void cmd.send({path:base+'/'+action,
    data:{key:crypto.randomUUID(),caseVersion:version,item,ordinal:Number(ordinal),...fields}})
  const valid=/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(item)&&/^\d+$/.test(ordinal)&&Number.isSafeInteger(Number(ordinal))
  function selectQuantity(nextItem:string,nextOrdinal:string){
    setItem(nextItem);setOrdinal(nextOrdinal);setConfirm(false);setReason('');setReference('');setResponsibility('')
  }
  return <section aria-label="구매확정 후 하자 환불" style={{overflowWrap:'anywhere'}}><h3>구매확정 후 하자 환불</h3>
    {!opened&&<button onClick={()=>open(true)}>회수·검수 기록 열기</button>}
    {entries&&<fieldset disabled={!!cmd.pending} style={{minInlineSize:0}}><legend>원 주문 수량</legend>
      {entries.map(r=><button key={r.collection} onClick={()=>selectQuantity(r.item,String(r.ordinal))}>{r.item} · 수량 번호 {r.ordinal}</button>)}
      <label>원 주문 상품 ID <input value={item} onChange={e=>selectQuantity(e.target.value,ordinal)}/></label>
      <label>수량 번호 <input inputMode="numeric" value={ordinal} onChange={e=>selectQuantity(item,e.target.value)}/></label>
      {!row?.inspection&&<label>{row?'검수 기록 번호':'회수 기록 번호'} <input maxLength={200} value={reference} onChange={e=>setReference(e.target.value)}/></label>}
      {!row&&<button disabled={!valid||!reference.trim()} onClick={()=>send('COLLECT',{reference})}>실제 회수 기록</button>}
      {row&&!row.approval&&<label>{row.inspection?'승인 사유':'검수 근거'} <textarea value={reason} maxLength={row.inspection?500:1000} onChange={e=>setReason(e.target.value)}/></label>}
      {row&&!row.inspection&&<><label>검수 책임 <select value={responsibility} onChange={e=>setResponsibility(e.target.value)}>
        <option value="">선택</option><option value="SELLER">판매자 책임</option><option value="BUYER">구매자 책임</option>
      </select></label><button disabled={!responsibility||!reason.trim()||!reference.trim()}
        onClick={()=>send('INSPECT',{previous:row.collection,reference,responsibility,reason})}>검수 결과 기록</button></>}
      {row?.inspection&&!row.approval&&row.responsibility==='SELLER'&&<button disabled={!reason.trim()}
        onClick={()=>send('APPROVE',{previous:row.inspection,reason})}>예외 환불 승인</button>}
      {row?.approval&&<p>승인 금액 {row.amount}원</p>}
      {row?.approval&&!row.execution&&<><label><input type="checkbox" checked={confirm} onChange={e=>setConfirm(e.target.checked)}/>승인한 수량과 금액으로 환불 실행</label>
        <button disabled={!confirm} onClick={()=>send('EXECUTE',{previous:row.approval})}>환불 실행</button></>}
      {row?.execution&&<p role="status">환불 실행 결과 {row.result??'처리 대기'}</p>}
      {row?.shippingAttempt&&<p>최초 배송비 환불 {row.shippingState}</p>}
      {(row?.result==='FAILED'||row?.shippingState==='FAILED')&&<>
        <label>재시도 사유 <textarea value={reason} maxLength={500} onChange={e=>setReason(e.target.value)}/></label>
        {row.result==='FAILED'&&<button disabled={!reason.trim()} onClick={()=>send('RETRY_FAILED',{previous:row.attempt,reason})}>실패한 상품 환불 재시도</button>}
        {row.shippingState==='FAILED'&&<button disabled={!reason.trim()} onClick={()=>send('RETRY_FAILED',{previous:row.shippingAttempt,reason})}>실패한 배송비 환불 재시도</button>}
      </>}
      <button onClick={()=>{setEntries(null);setConfirm(false);refresh(n=>n+1)}}>결과 조회</button>
    </fieldset>}
    {(cmd.error||error)&&<p role="alert">{cmd.error||error}</p>}
    {cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 요청으로 결과 확인</button>}
  </section>
}
