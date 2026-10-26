import {useEffect,useState,useSyncExternalStore} from 'react'
import type {AuthStore} from '../auth/AuthStore'
import {QuoteStore,formatMoney,type LineRequest} from './QuoteStore'
import {CouponStore,couponStatus} from './CouponStore'
import {Link} from 'react-router'
export function QuotePanel({auth,lines}:{auth:AuthStore;lines:LineRequest[]}){
 const [quotes]=useState(()=>new QuoteStore(auth)),[coupons]=useState(()=>new CouponStore(auth))
 const state=useSyncExternalStore(quotes.subscribe,quotes.getSnapshot),owned=useSyncExternalStore(coupons.subscribe,coupons.getSnapshot),member=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
 useEffect(()=>quotes.connect(),[quotes]);useEffect(()=>coupons.connect(),[coupons])
 const key=JSON.stringify(lines)
 useEffect(()=>{quotes.configure(JSON.parse(key) as LineRequest[])},[quotes,key])
 return <section aria-label="서버 견적"><h2>현재 구매 견적</h2><p>상품 행사 → 회원 쿠폰 → 배송 행사 순서로 계산합니다. 매대별 상품 행사 후 50,000원 이상은 기본 배송비가 무료이며, 미만은 3,000원입니다. 주문·결제 전에 다시 확인합니다.</p>
 <button disabled={state.busy} onClick={()=>void quotes.refresh()}>견적 다시 계산</button>
 {state.busy&&<p role="status">견적을 계산하고 있습니다.</p>}{state.error&&<><p role="alert">{state.error}</p><button onClick={quotes.clearCoupons}>쿠폰 선택 초기화</button></>}
 {member.member&&<><p>보유 쿠폰 {owned.ownedPage+1} 페이지</p>{owned.error&&<p role="alert">{owned.error}</p>}<button disabled={owned.busy||!owned.ownedPage} onClick={()=>void coupons.load(owned.ownedPage-1)}>이전 선택 쿠폰</button><button disabled={owned.busy||!owned.ownedMore} onClick={()=>void coupons.load(owned.ownedPage+1)}>다음 선택 쿠폰</button></>}
 {member.error&&<><p role="alert">{member.error.message}</p><Link to="/login">다시 로그인하고 견적 확인</Link></>}
 {state.quote?.stores.map(s=><section key={s.storeId} aria-label={s.name+' 견적'}><h3>{s.name}</h3><p>매대 주소: {s.slug}</p>
 {member.member&&<><label htmlFor={'quote-coupon-'+s.storeId}>적용 쿠폰 — {s.slug}</label><select id={'quote-coupon-'+s.storeId} disabled={state.busy||owned.busy} value={state.selected[s.storeId]??''} onChange={e=>quotes.selectCoupon(s.storeId,e.target.value)}><option value="">쿠폰 사용 안 함</option>{s.coupon&&!owned.owned.some(c=>c.id===s.coupon!.id)&&<option value={s.coupon.id}>{s.coupon.title} (선택됨)</option>}{owned.owned.filter(c=>c.storeId===s.storeId&&couponStatus(c)==='사용 가능').map(c=><option key={c.id} value={c.id}>{c.title}</option>)}</select></>}
 <p>상품 정가: {formatMoney(s.subtotal)}</p><p>상품 행사 할인: {formatMoney(s.productDiscount)}</p><p>쿠폰 할인: {formatMoney(s.couponDiscount)}</p><p>기본 배송비: {formatMoney(s.shippingBase)}</p><p>배송 행사 할인: {formatMoney(s.shippingDiscount)}</p><p>매대 견적: {formatMoney(s.total)}</p>
 {s.lines.map(l=><details key={l.skuId}><summary>{l.title} · {l.quantity}개 · 할인 후 {formatMoney(l.net)}</summary>{l.promotion&&<p>상품 행사: {l.promotion.title} (가장 큰 할인, 동률은 행사 ID 순)</p>}{l.units.map(u=><p key={u.ordinal}>{u.ordinal+1}번째 상품: {formatMoney(u.net)} · 행사 {formatMoney(u.promotionDiscount)} · 쿠폰 {formatMoney(u.couponDiscount)}</p>)}</details>)}{s.shippingPromotion&&<p>배송 행사: {s.shippingPromotion.title}</p>}
 </section>)}{state.quote&&<p data-testid="quote-total">현재 견적 합계: {formatMoney(state.quote.total)}</p>}
 </section>
}
