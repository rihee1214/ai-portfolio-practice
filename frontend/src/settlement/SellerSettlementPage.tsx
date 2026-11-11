import {useEffect,useState,useSyncExternalStore} from 'react'
import {requestJson} from '../api/http'
import {useAuthStore} from '../auth/AuthContext'

type Line={sellerOrderId:string;causeId:string;itemId:string|null;ordinal:number|null;kind:string;amount:string;fee:string}
type Week={weekStart:string;state:string;net:string;opening:string;payout:string;closing:string;
  held:{sellerOrderId:string;reason:string}[];lines:Line[]}
const labels:Record<string,string>={PRODUCT:'상품',COMMISSION:'수수료',REFUND:'상품 환불',
  COMMISSION_REVERSAL:'원 수수료 반환',SHIPPING:'최초 배송비',SHIPPING_REFUND:'배송비 환불',RECOVERY_FEE:'회수비',
  HELD_UNIT:'수량 보류',UNRESOLVED_REFUND:'환불 결과 대기',CLAIM_RESERVATION:'회수비 정산 대기',
  INCOMPLETE_ORIGINAL_GRAPH:'원 거래 귀속 확인 중',SHIPPING_OUTCOME_PENDING:'배송비 결과 대기',DEFERRED_AFTER_HOLD:'보류 해제 주로 이월',
  PAYMENT_EVIDENCE:'원 결제 확인 중',DRAFT:'초안',REVIEWED:'검토 완료',PAID_MOCK:'모의 지급 완료'}
const money=(n:string)=>BigInt(n).toLocaleString('ko-KR')+'원'
const object=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null
const integer=(v:unknown):v is string=>typeof v==='string'&&/^-?\d+$/.test(v)
function week(value:unknown):value is Week {
  if(!object(value))return false
  return typeof value.weekStart==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value.weekStart)
    &&typeof value.state==='string'&&['DRAFT','REVIEWED','PAID_MOCK'].includes(value.state)
    &&[value.net,value.opening,value.payout,value.closing].every(integer)
    &&Array.isArray(value.held)&&value.held.every(h=>object(h)&&typeof h.sellerOrderId==='string'&&typeof h.reason==='string')
    &&Array.isArray(value.lines)&&value.lines.every(l=>object(l)&&typeof l.sellerOrderId==='string'
      &&typeof l.causeId==='string'&&(l.itemId===null||typeof l.itemId==='string')
      &&(l.ordinal===null||Number.isSafeInteger(l.ordinal))&&typeof l.kind==='string'&&integer(l.amount)&&integer(l.fee))
}
export function SellerSettlementPage(){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return session.member?.roles.includes('SELLER')?<Statement key={session.member.id}/>:<p>판매자 로그인이 필요합니다.</p>
}
function Statement(){
  const [rows,setRows]=useState<Week[]|null>(null),[error,setError]=useState(''),[revision,reload]=useState(0)
  useEffect(()=>{
    const controller=new AbortController()
    void requestJson('/api/seller/settlements',{signal:controller.signal,cache:'no-store'}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success'&&Array.isArray(r.data)&&r.data.every(week)){setRows(r.data);setError('')}
      else{setRows(null);setError('현재 판매자 권한으로 정산 내역을 확인할 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[revision])
  return <section style={{overflowWrap:'anywhere'}}><h1>내 매대 정산</h1>
    <button onClick={()=>{setRows(null);setError('');reload(n=>n+1)}}>다시 조회</button>
    {error&&<p role="alert">{error}</p>}{rows?.length===0&&<p>정산 내역이 없습니다.</p>}
    {rows?.map(w=><article key={w.weekStart}><h2>{w.weekStart} · {labels[w.state]}</h2><dl>
      <dt>주간 순액</dt><dd>{money(w.net)}</dd><dt>전주 미회수 조정 잔액</dt><dd>{money(w.opening)}</dd>
      <dt>모의 지급액</dt><dd>{money(w.payout)}</dd><dt>미회수 조정 잔액 (다음 주 이월)</dt><dd>{money(w.closing)}</dd></dl>
      <ul>{w.lines.map(l=><li key={[l.causeId,l.kind,l.itemId,l.ordinal].join(':')}>{labels[l.kind]} · {money(l.amount)} · 수수료 {money(l.fee)}
        <div>원인 ID {l.causeId} · 판매자 주문 {l.sellerOrderId}</div></li>)}</ul>
      {w.held.map(h=><p key={h.sellerOrderId+h.reason}>{h.sellerOrderId} · {labels[h.reason]??h.reason}</p>)}
    </article>)}</section>
}
