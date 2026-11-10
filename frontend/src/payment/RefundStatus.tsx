import {useEffect,useMemo,useSyncExternalStore} from 'react'
import {useAuthStore} from '../auth/AuthContext'
import {formatMoney} from '../promotion/QuoteStore'
import {RefundStatusStore,refundStatusText} from './RefundStatusStore'
export function RefundStatus({number,guest=false}:{number:string;guest?:boolean}){
 const auth=useAuthStore(),store=useMemo(()=>new RefundStatusStore(auth,number,guest),[auth,number,guest]),view=useSyncExternalStore(store.subscribe,store.getSnapshot)
 useEffect(()=>{const disconnect=store.connect();window.addEventListener('focus',store.load);return()=>{window.removeEventListener('focus',store.load);disconnect()}},[store])
 const {rows,error,busy}=view
 if(!view.available)return null
 return <section aria-label="제공자 환불 상태"><h2>제공자 환불 상태</h2>{busy&&<p role="status">환불 상태를 확인하고 있습니다.</p>}{error&&<p role="alert">환불 상태를 확인하지 못했습니다. 현재 조회 자격을 확인하고 다시 조회해 주세요.</p>}{rows.length>0&&<><p>확인 중인 환불은 금액 예약을 유지합니다. 이 조회는 새 환불을 요청하지 않습니다.</p><ul>{rows.map(r=><li key={r.attemptId} data-testid={'refund-status-'+r.attemptId}><p>{r.purpose==='ITEM'?'상품':'원배송비'} 환불 · 순번 {r.sequence} · {formatMoney(r.amount)} · {r.mode==='DEMO'?'모의':'테스트'}</p><p>{refundStatusText(r)}</p>{r.nextDue&&<p>다음 자동 확인 <time dateTime={r.nextDue}>{new Date(r.nextDue).toLocaleString('ko-KR')}</time></p>}{r.reviewRequired&&<p>담당자가 원결제와 거래 근거를 확인해야 합니다.</p>}</li>)}</ul><p>제공자 환불 완료와 반품·교환의 최종 완료는 별도 상태입니다.</p></>}<button disabled={busy} onClick={()=>void store.load()}>환불 상태 다시 조회</button></section>
}
