import {useEffect,useState,useSyncExternalStore} from 'react'
import {useParams,Link} from 'react-router'
import {useAuthStore} from '../auth/AuthContext'
import {PaymentStore} from './PaymentStore'
import {formatMoney} from '../promotion/QuoteStore'
import {TossPaymentAdapter,readTossRedirect} from './TossPaymentAdapter'
export function PaymentPage(){const {number=''}=useParams();return <PaymentPanel key={number} number={number}/>}
function PaymentPanel({number}:{number:string}){
 const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot),[store]=useState(()=>new PaymentStore(auth,number)),state=useSyncExternalStore(store.subscribe,store.getSnapshot)
 const [redirect]=useState(()=>window.location.search),[sdkBusy,setSdkBusy]=useState(false),[sdkError,setSdkError]=useState('')
 useEffect(()=>()=>store.dispose(),[store])
 useEffect(()=>{if(new URLSearchParams(redirect).has('paymentKey'))window.history.replaceState(null,'',window.location.pathname)},[redirect])
 useEffect(()=>{if(session.checked&&!session.error)void store.poll()},[store,session.checked,session.error])
 const pending=!!state.view&&!state.view.reviewRequired&&(state.view.paymentState==='PROCESSING'||state.view.paymentState==='UNKNOWN'||['PENDING','UNKNOWN'].includes(state.view.compensation))
 useEffect(()=>{if(!pending||state.blocked)return;const id=setInterval(()=>void store.poll(),2000);return()=>clearInterval(id)},[pending,state.blocked,store])
 const view=state.view
 const confirmation=view?.mode==='SANDBOX'&&view.providerOrderId?readTossRedirect(redirect,view.providerOrderId,view.amount):null
 async function sandbox(){if(sdkBusy)return;setSdkBusy(true);setSdkError('');try{const setup=await store.sandbox();if(setup)await new TossPaymentAdapter().request(setup,number,window.location.origin)}catch{setSdkError('결제 인증을 완료하지 못했습니다. 서버 결과를 확인한 뒤 다시 시도해 주세요.')}finally{setSdkBusy(false)}}
 return <section aria-label="결제"><h1>{view?.mode==='SANDBOX'?'테스트 결제':'모의 결제'}</h1><p>{number}</p>{view&&<><p>{formatMoney(view.amount)} · {view.mode==='DEMO'?'모의 결제':'테스트 결제'}</p>{view.orderState==='PAID'?<h2>결제가 완료되었습니다</h2>:view.reviewRequired?<p role="status">운영 확인이 필요합니다. 새로 결제하지 말고 결과를 기다려 주세요.</p>:pending?<p role="status">결제 확인 중입니다. 예약을 유지하고 서버 결과를 확인합니다.</p>:view.compensation==='CANCELED'?<p>늦게 확인된 승인을 취소했습니다.</p>:['CANCELED','EXPIRED'].includes(view.orderState)?<p>결제가 완료되지 않아 주문이 종료되었습니다.</p>:<p>결제 대기 중입니다.</p>}{view.paymentState==='NONE'&&view.orderState==='PENDING_PAYMENT'&&(view.mode==='DEMO'||view.amount==='0')&&<button disabled={state.busy||state.blocked} onClick={()=>void store.approve()}>{view.mode==='DEMO'?'모의 결제 승인':'0원 결제 확정'}</button>}{view.mode==='SANDBOX'&&view.amount!=='0'&&view.paymentState==='NONE'&&view.orderState==='PENDING_PAYMENT'&&<>{confirmation?<><p>카드 인증 결과를 서버에서 검증한 후 결제를 승인합니다.</p><button disabled={state.busy||state.blocked} onClick={()=>void store.confirm(confirmation)}>테스트 결제 승인 확인</button></>:<button disabled={state.busy||state.blocked||sdkBusy} onClick={()=>void sandbox()}>테스트 결제창 열기</button>}</>}</>}{state.error&&<p role="alert">{state.error}</p>}{sdkError&&<p role="alert">{sdkError}</p>}<button disabled={state.busy||state.blocked} onClick={()=>void store.poll()}>서버 결과 확인</button>{state.blocked&&<Link to="/login">로그인</Link>}</section>
}
