import { purchaseSummary } from '../cart/Purchase'
import { useContext, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { CartContext } from '../cart/CartContext'
import type { CartStore, Line } from '../cart/CartStore'
const money = (value: number) => value.toLocaleString('ko-KR') + '원'
export function AddToCart({ sku, available }: { sku: string; available: number }) {
  const store = useContext(CartContext)
  return store ? <AddForm key={sku} store={store} sku={sku} available={available} /> : <Link to="/cart">장바구니</Link>
}
function AddForm({ store, sku, available }: { store: CartStore; sku: string; available: number }) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [quantity, setQuantity] = useState('1')
  return <div className="cart-add"><form onSubmit={e => { e.preventDefault(); void store.add(sku, Number(quantity)) }}>
    <label htmlFor="cart-add-quantity">담을 수량</label><input id="cart-add-quantity" type="number" min="1" max="99" required value={quantity} onChange={e => setQuantity(e.target.value)} disabled={state.busy || !state.ready || available === 0} />
    <button disabled={state.busy || !state.ready || available === 0}>장바구니 담기</button></form><Link to="/cart">장바구니 보기</Link>
    {state.notice && <p role="status">{state.notice}</p>}{state.error && <p role="alert">{state.error.message} <Link to="/cart">장바구니에서 다시 확인</Link></p>}
  </div>
}
function CartLine({ line, store }: { line: Line; store: CartStore }) {
  const title = line.detail?.product.title ?? '판매할 수 없는 상품'
  const offer = line.detail?.offers[0]
  return <article className="cart-line" aria-label={title}>
    <h2>{line.detail ? <Link to={'/products/' + line.detail.product.slug}>{title}</Link> : title}</h2>
    {offer ? <><p>{line.detail!.product.storeName} · {offer.options.length ? offer.options.map(o => o.name + ': ' + o.value).join(' / ') : '기본 상품'}</p>
      <p>개당 {money(offer.price)}</p><p>상품 금액: {money(offer.price * line.quantity)}</p>
      {offer.available === 0 ? <p>품절</p> : offer.available < line.quantity && <p>재고 부족: 현재 {offer.available}개</p>}</> : <p>비공개·판매 중지 또는 삭제된 옵션입니다. 이름과 가격을 표시하지 않습니다.</p>}
    <form onSubmit={event => { event.preventDefault(); void store.set(line.skuId, Number(new FormData(event.currentTarget).get('quantity'))) }}>
      <label htmlFor={'quantity-' + line.skuId}>{title} 수량</label><input id={'quantity-' + line.skuId} name="quantity" type="number" min="1" max="99" required defaultValue={line.quantity} />
      <button aria-label={title + ' 수량 변경'}>수량 변경</button><button type="button" aria-label={title + ' 삭제'} onClick={() => { void store.set(line.skuId, 0) }}>삭제</button>
    </form>
  </article>
}
export function CartPage() {
  const store = useContext(CartContext)
  if (!store) throw new Error('CartProvider is required')
  return <CartContent store={store} />
}
function CartContent({ store }: { store: CartStore }) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const summary = purchaseSummary(state.items)
  const total = state.items.reduce((sum, row) => sum + (row.detail?.offers[0]?.price ?? 0) * row.quantity, 0)
  return <section className="cart-page"><h1>장바구니</h1><p>상품별 최대 99개, 최대 100종을 담을 수 있습니다. 재고를 예약하지 않으며 가격·배송비·할인은 주문 단계에서 다시 확인합니다.</p>
    {!state.ready && !state.error && <p role="status">장바구니와 로그인 상태를 확인하고 있습니다.</p>}
    {state.error && <div role="alert"><p>{state.error.message}</p><p>가져오기가 다른 계정에 연결되어 있다면 원래 계정으로 로그인해 다시 확인하세요.</p></div>}
    <button disabled={state.busy} onClick={() => { void store.refresh() }}>장바구니 다시 확인</button>
    {state.notice && <p role="status">{state.notice}</p>}
    {state.ready && <><p>{state.member ? '회원 장바구니' : '방문자 장바구니 · 이 브라우저에만 저장됩니다.'}</p>
      {!state.member && <p><Link to="/login">로그인하면 회원 장바구니로 가져옵니다.</Link></p>}
      {state.items.length ? <>{summary.groups.map(group => <section key={group.slug} aria-label={group.name + ' 장바구니 상품'}><h2>{group.name}</h2>{group.items.map(row => <CartLine key={row.skuId + ':' + row.quantity} line={row} store={store} />)}<p>매대 상품 금액: {money(group.subtotal)}</p></section>)}
        {state.items.filter(row => !row.detail).map(row => <CartLine key={row.skuId + ':' + row.quantity} line={row} store={store} />)}
        <p>판매 가능한 항목의 상품 금액 합계: {money(total)}</p><p>품절·재고 부족 항목은 별도 안내를 확인하세요. 배송비·할인 미포함입니다.</p>
        {summary.allowed ? <p><Link to="/checkout">주문서 준비</Link></p> : <p>판매 불가·품절·재고 부족 항목을 확인한 뒤 주문서를 준비할 수 있습니다.</p>}
        <button onClick={() => { void store.clear() }}>장바구니 비우기</button></> : <p>장바구니가 비어 있습니다.</p>}</>}
    {state.error && <form onSubmit={e => { e.preventDefault(); void store.discardGuest() }}><p>복구할 수 없는 방문자 저장값만 비울 수 있습니다. 이미 반영된 회원 장바구니는 유지됩니다.</p>
      <label><input type="checkbox" required />방문자 저장값과 가져오기 기록 삭제 확인</label><button disabled={state.busy}>방문자 가져오기 취소</button></form>}
    <p><Link to="/products">상품 계속 둘러보기</Link></p>
  </section>
}
