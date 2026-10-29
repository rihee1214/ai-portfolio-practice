import {useEffect,useState,useSyncExternalStore} from 'react'
import {Link} from 'react-router'
import {useAuthStore} from '../auth/AuthContext'
import {OrderQueryStore} from '../order/OrderQueryStore'
import {formatMoney} from '../promotion/QuoteStore'
import {orderState} from '../order/orderLabels'
export function OrdersPage(){return <section><h1>주문·결제 이력</h1><OrdersPanel /></section>}
export function OrdersPanel({guest=false}:{guest?:boolean}){
 const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot),[store]=useState(()=>new OrderQueryStore(auth,guest?'guest':'member')),view=useSyncExternalStore(store.subscribe,store.getSnapshot)
 useEffect(()=>{if(session.checked&&!session.busy&&!session.error)void store.load()},[store,session.checked,session.busy,session.error,session.member?.id])
 useEffect(()=>{const reload=()=>{void store.load()};window.addEventListener('focus',reload);return()=>{window.removeEventListener('focus',reload);store.dispose()}},[store])
 return <section aria-label="실제 주문 이력"><h2>실제 주문 이력</h2><p>주문 당시 상품·금액을 표시합니다. 결제 완료는 배송 완료를 뜻하지 않습니다.</p>{view.busy&&<p role="status">주문 이력을 확인하고 있습니다.</p>}{view.error&&<p role="alert">{view.error}</p>}{!view.busy&&!view.error&&view.orders.length===0&&<p>조회할 실제 주문이 없습니다.</p>}<ul>{view.orders.map(order=><li key={order.orderNumber}><Link to={(guest?'/guest/orders/':'/account/orders/')+order.orderNumber}>{order.orderNumber}</Link><p>{orderState(order.state)} · {formatMoney(order.total)} · {order.mode==='DEMO'?'모의 결제':'테스트 결제'}</p><time dateTime={order.createdAt}>{new Date(order.createdAt).toLocaleString('ko-KR')}</time></li>)}</ul>{view.nextCursor&&<button disabled={view.busy} onClick={()=>void store.next()}>다음 주문 이력</button>}<button disabled={view.busy} onClick={()=>void store.load()}>주문 이력 다시 조회</button></section>
}
