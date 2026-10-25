import { useContext, useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { CartContext } from '../cart/CartContext'
import { purchaseSummary } from '../cart/Purchase'
const money = (value: number) => value.toLocaleString('ko-KR') + '원'
export function CheckoutPage() {
  const store = useContext(CartContext)
  if (!store) throw new Error('CartProvider is required')
  return <Preparation store={store} />
}
function Preparation({ store }: { store: NonNullable<React.ContextType<typeof CartContext>> }) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [entered, setEntered] = useState(false)
  useEffect(() => { let active = true; void store.refresh().then(() => { if (active) setEntered(true) }); return () => { active = false } }, [store])
  const ready = entered && state.ready && !state.busy && !state.error
  const summary = purchaseSummary(state.items)
  return <section><h1>주문서 준비</h1>
    <p>상품과 수량을 확인하는 준비 내역입니다. 아직 주문·결제·재고 예약은 이루어지지 않았습니다. 배송비·할인·최종 견적과 배송지 입력은 준비 중입니다.</p>
    <button disabled={state.busy} onClick={() => { void store.refresh() }}>준비 내역 다시 확인</button>
    {!ready && !state.error && <p role="status">현재 장바구니를 확인하고 있습니다.</p>}
    {state.error && <p role="alert">{state.error.message}</p>}
    {ready && (!state.items.length ? <p role="alert">장바구니가 비어 있어 주문서를 준비할 수 없습니다.</p>
      : !summary.allowed ? <p role="alert">판매 불가·품절·재고 부족 항목을 장바구니에서 확인해 주세요.</p>
      : <><p>{state.member ? '회원 구매 준비' : '방문자 구매 준비 · 구매 인증 기능은 준비 중입니다.'}</p>
        {!state.member && <p><Link to="/login">회원으로 로그인</Link></p>}
        {summary.groups.map(group => <section key={group.slug} aria-label={group.name + ' 준비 상품'}><h2>{group.name}</h2>
          {group.items.map(line => <article key={line.skuId}><h3>{line.detail!.product.title}</h3><p>{line.detail!.offers[0].options.map(o => o.name + ': ' + o.value).join(' / ') || '기본 상품'} · {line.quantity}개</p><p>상품 금액: {money(line.quantity * line.detail!.offers[0].price)}</p></article>)}
          <p>매대 상품 금액: {money(group.subtotal)}</p></section>)}
        <p>상품 금액 합계: {money(summary.total)}</p><p>표시 금액은 조회 시점의 상품 금액이며 결제할 금액이 확정된 것은 아닙니다.</p></>)}
    <p><Link to="/cart">장바구니로 돌아가기</Link></p>
  </section>
}
