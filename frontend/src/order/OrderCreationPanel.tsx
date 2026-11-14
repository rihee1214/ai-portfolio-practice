import {useEffect,useState,useSyncExternalStore} from 'react'
import type {AuthStore} from '../auth/AuthStore'
import {AccountStore,type Address} from '../auth/AccountStore'
import type {CartStore,PurchaseCart} from '../cart/CartStore'
import type {Quote} from '../promotion/QuoteStore'
import {formatMoney} from '../promotion/QuoteStore'
import {GuestStore} from '../guest/GuestStore'
import {OrderCreationStore,type Contact} from './OrderCreationStore'
const initial:Contact={buyerName:'',buyerPhone:'',recipientName:'',recipientPhone:'',postalCode:'',addressLine1:'',addressLine2:''}
function purchaseWorkflow(auth:AuthStore,cart:CartStore){
 let submitted:PurchaseCart|null=null
 return {store:new OrderCreationStore(auth,async result=>{if(submitted)await cart.reconcilePurchase(result.id,submitted)}),approve:(snapshot:PurchaseCart|null)=>{submitted=snapshot}}
}
function SavedDeliveryAddresses({auth,select}:{auth:AuthStore;select:(address:Address)=>void}){
 const [addresses]=useState(()=>new AccountStore(auth,'addresses'))
 const state=useSyncExternalStore(addresses.subscribe,addresses.getSnapshot)
 useEffect(()=>addresses.connect(),[addresses])
 return <section aria-label="저장 배송지">{state.busy&&<p role="status">배송지를 불러오고 있습니다.</p>}
 {state.error&&<p role="alert">{state.error.message}</p>}
 {state.addresses?.length===0&&<p>저장된 배송지가 없습니다. 직접 입력해 주세요.</p>}
 {!!state.addresses?.length&&<label>저장 배송지 선택 <select style={{maxWidth:'100%'}} value="" disabled={state.busy} onChange={event=>{const address=state.addresses?.find(row=>row.id===event.target.value);if(address)select(address)}}><option value="">배송지를 선택해 주세요</option>{state.addresses.map(address=><option key={address.id} value={address.id}>{address.label}{address.isDefault?' · 기본 배송지':''}</option>)}</select></label>}
 <button type="button" disabled={state.busy} onClick={()=>void addresses.load()}>배송지 다시 조회</button></section>
}
export function OrderCreationPanel({auth,cart,quote,refresh}:{auth:AuthStore;cart:CartStore;quote:Quote|null;refresh:()=>Promise<void>}){
 const [{store,approve}]=useState(()=>purchaseWorkflow(auth,cart))
 const [guest]=useState(()=>new GuestStore()),[contact,setContact]=useState(initial),[confirmed,setConfirmed]=useState('')
 const [showAddresses,setShowAddresses]=useState(false)
 const state=useSyncExternalStore(store.subscribe,store.getSnapshot),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot),verification=useSyncExternalStore(guest.subscribe,guest.getSnapshot)
 useEffect(()=>{if(!session.member)void guest.load();return()=>guest.dispose()},[guest,session.member])
 useEffect(()=>auth.subscribe(()=>{setContact(initial);setConfirmed('');setShowAddresses(false);approve(null)}),[auth,approve])
 const ready=!!session.member||(verification.state==='VERIFIED'&&verification.linkKnown&&(verification.passwordReady||verification.linkReady))
 const labels:Record<keyof Contact,string>={buyerName:'주문 구매자 이름',buyerPhone:'주문 구매자 전화번호',recipientName:'수령인 이름',recipientPhone:'수령인 전화번호',postalCode:'우편번호',addressLine1:'기본 주소',addressLine2:'상세 주소'}
 if(state.result)return <section aria-label="생성된 주문"><h2>주문이 생성되었습니다</h2><p data-testid="created-order-number">{state.result.orderNumber}</p><p>결제 대기 · {state.result.stores}개 매대 · {formatMoney(state.result.total)}</p><p>예약 기한: {new Date(state.result.expiresAt).toLocaleString('ko-KR')}</p><p>아직 결제가 완료되지 않았습니다.</p><a href={"/payments/"+state.result.orderNumber}>결제로 이동</a>{state.cleanupError&&<p role="alert">주문은 생성됐지만 장바구니 반영을 확인하지 못했습니다. 주문을 다시 만들지 말고 장바구니를 확인해 주세요.</p>}</section>
 return <section aria-label="주문 생성"><h2>배송 정보와 주문 확인</h2>{!ready&&<p>비회원 구매 인증과 주문 묶음 준비를 완료해 주세요.</p>}
 {session.member&&<button type="button" disabled={state.busy||state.retry} onClick={()=>setShowAddresses(true)}>저장 배송지 불러오기</button>}
 {session.member&&showAddresses&&!state.busy&&!state.retry&&<SavedDeliveryAddresses auth={auth} select={address=>{setContact(previous=>({...previous,recipientName:address.recipient,recipientPhone:address.phone,postalCode:address.postalCode,addressLine1:address.addressLine1,addressLine2:address.addressLine2}));setConfirmed('');approve(null);setShowAddresses(false)}}/>}
 <form onSubmit={event=>{event.preventDefault();if(!quote||confirmed!==quote.fingerprint||!ready)return;void store.submit({lines:quote.stores.flatMap(s=>s.lines.map(l=>({skuId:l.skuId,quantity:l.quantity}))),coupons:quote.stores.filter(s=>s.coupon).map(s=>({storeId:s.storeId,couponId:s.coupon!.id})),fingerprint:quote.fingerprint,contact:session.member?contact:{...contact,buyerName:'비회원',buyerPhone:contact.recipientPhone},guestAttemptId:session.member?null:verification.attempt,linkRevision:session.member?null:verification.linkRevision})}}>
 {(Object.keys(labels) as (keyof Contact)[]).filter(key=>!!session.member||!['buyerName','buyerPhone'].includes(key)).map(key=><p key={key}><label>{labels[key]} <input value={contact[key]} disabled={state.busy||state.retry} required={key!=='addressLine2'} maxLength={key.includes('Name')?80:key.includes('Phone')?32:key==='postalCode'?20:300} onChange={event=>{setContact({...contact,[key]:event.target.value});setConfirmed('')}}/></label></p>)}
 <label><input type="checkbox" checked={!!quote&&confirmed===quote.fingerprint} disabled={state.busy||state.retry||!quote} onChange={event=>{if(event.target.checked&&quote){const snapshot=cart.capturePurchase(),lines=quote.stores.flatMap(s=>s.lines);approve({owner:snapshot.owner,items:snapshot.items.filter(i=>lines.some(l=>l.skuId===i.skuId&&l.quantity===i.quantity)).map(i=>({...i}))});setConfirmed(quote.fingerprint)}else{setConfirmed('');approve(null)}}}/> 현재 상품·수량·견적과 배송 정보를 확인했습니다</label>
 <p><button disabled={!quote||!ready||confirmed!==quote.fingerprint||state.busy||state.retry||state.conflict}>주문 생성</button></p></form>
 {state.error&&<p role="alert">{state.error}</p>}{state.busy&&<p role="status">주문 결과를 확인하고 있습니다.</p>}
 {state.retry&&<button disabled={state.busy} onClick={()=>void store.retry()}>같은 요청으로 주문 결과 확인</button>}
 {state.conflict&&<button onClick={()=>{setConfirmed('');void refresh().then(()=>store.confirmAgain())}}>새 견적을 확인하고 다시 주문 준비</button>}
 </section>
}
