import {useEffect,useId,useRef,useState,useSyncExternalStore} from 'react'
import {Link,useParams} from 'react-router'
import {useAuthStore} from '../auth/AuthContext'
import {ShipmentStore} from '../fulfillment/ShipmentStore'
import {ShipmentRows} from '../fulfillment/ShipmentViews'
import {formatMoney} from '../promotion/QuoteStore'
import {orderState} from '../order/orderLabels'
export function SellerShipmentsPage(){const {id}=useParams();return <SellerShipmentsPanel key={id??'list'} id={id}/>}
export function SellerShipmentsPanel({id}:{id?:string}){
 const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot),[store]=useState(()=>new ShipmentStore(auth,'seller')),view=useSyncExternalStore(store.subscribe,store.getSnapshot),error=useRef<HTMLParagraphElement>(null),errorId=useId()
 useEffect(()=>{if(session.checked&&!session.busy&&!session.error){if(id)void store.openSeller(id);else void store.list()}},[store,id,session.checked,session.busy,session.error,session.member?.id])
 useEffect(()=>{const reload=()=>{if(!store.getSnapshot().shipping&&!store.getSnapshot().uncertain){if(id)void store.openSeller(id);else void store.list()}};window.addEventListener('focus',reload);return()=>{window.removeEventListener('focus',reload);store.dispose()}},[store,id])
 useEffect(()=>{if(view.error)error.current?.focus()},[view.error])
 const order=view.order
 return <section className="shipment-content"><h1>판매 주문·모의 배송</h1><p>본인 매대의 기존 주문을 확인하고 준비된 배송을 발송합니다. 이후 진행은 내부 모의 배송 제공자가 기록합니다.</p>
 {view.busy&&<p role="status">판매 주문과 배송을 확인하고 있습니다.</p>}{view.error&&<p role="alert" tabIndex={-1} id={errorId} ref={error}>{view.error}</p>}
 {!id&&<><ul aria-label="판매 주문 목록">{view.orders.map(o=><li key={o.sellerOrderId}><Link to={'/seller/orders/'+o.sellerOrderId}>{o.orderNumber}</Link><p>{orderState(o.orderState)} · 본인 매대 {formatMoney(o.total)}</p><time dateTime={o.createdAt}>{new Date(o.createdAt).toLocaleString('ko-KR')}</time></li>)}</ul>{view.loaded&&!view.busy&&!view.error&&view.orders.length===0&&<p>조회할 판매 주문이 없습니다.</p>}{view.nextCursor&&<button disabled={view.busy} onClick={()=>void store.next()}>다음 판매 주문</button>}</>}
 {!order&&view.uncertain&&<form onSubmit={e=>{e.preventDefault();void store.ship(view.uncertain!)}}><p>현재 배송 결과를 확인하지 못했습니다. 처음 제출한 동일 요청의 결과를 확인합니다.</p><label><input type="checkbox" checked={!!view.confirmed[view.uncertain]} disabled aria-describedby={view.error?errorId:undefined}/>원 항목과 수량을 확인했습니다</label><button disabled={view.shipping||view.blocked} aria-describedby={view.error?errorId:undefined}>{view.shipping?'발송 결과 확인 중':'같은 요청으로 발송 결과 확인'}</button></form>}
 {order&&<><p data-testid="seller-order-number">{order.orderNumber}</p><h2>{orderState(order.orderState)}</h2><p>본인 매대 금액 {formatMoney(order.total)}</p><h2>배송 수령 정보</h2><p>{order.recipient.name} · {order.recipient.phone}</p><p>{order.recipient.postalCode} {order.recipient.addressLine1} {order.recipient.addressLine2}</p><ul>{order.items.map(i=><li key={i.id}>{i.title} · {i.options.join(' / ')} · 수량 {i.quantity}</li>)}</ul>
 {['CANCELED','EXPIRED'].includes(order.orderState)&&<p>취소·만료된 주문은 새로 발송할 수 없습니다. 저장된 배송 이력만 확인합니다.</p>}{view.shipments.length===0?<p role="status">{order.orderState==='PAID'?'배송 생성 대기: 결제된 주문의 배송을 준비하고 있습니다.':order.orderState==='PENDING_PAYMENT'?'미결제: 결제 확인 후 배송을 준비합니다.':'진행 중인 배송이 없습니다.'}</p>:view.shipments.map(s=><section key={s.id} aria-label={'배송 '+s.id}><ShipmentRows shipments={[s]} items={order.items}/>{((s.state==='PREPARING'&&order.orderState==='PAID')||view.uncertain===s.id||view.confirmed[s.id])&&<form onSubmit={e=>{e.preventDefault();void store.ship(s.id)}}><label><input type="checkbox" checked={!!view.confirmed[s.id]} disabled={view.shipping||view.uncertain===s.id} onChange={e=>store.confirm(s.id,e.target.checked)} aria-describedby={view.error?errorId:undefined}/>원 항목과 수량을 확인했습니다</label><button disabled={view.shipping||view.blocked||!view.confirmed[s.id]||(!view.uncertain&&(s.state!=='PREPARING'||order.orderState!=='PAID'))} aria-describedby={view.error?errorId:undefined}>{view.shipping?'발송 결과 확인 중':view.uncertain===s.id?'같은 요청으로 발송 결과 확인':'발송 처리'}</button></form>}</section>)}</>}
 <button disabled={view.busy||view.shipping||!!view.uncertain} onClick={()=>{if(id)void store.openSeller(id);else void store.list()}}>판매 주문 다시 조회</button>{id&&<p><Link to="/seller/orders">판매 주문 목록으로</Link></p>}
 </section>
}
