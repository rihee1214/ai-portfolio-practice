import { useContext, useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { AuthEntryActions } from '../auth/AuthEntryActions'
import { AuthContext, useAuthStore } from '../auth/AuthContext'
import { WishlistStore } from '../cart/WishlistStore'
function useWishlist() {
  const auth = useAuthStore()
  const member = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const [store] = useState(() => new WishlistStore(auth))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => store.connect(), [store])
  return { store, state, member }
}
export function SaveWish({ slug }: { slug: string }) {
  const auth = useContext(AuthContext)
  return auth ? <SaveWishContent key={slug} slug={slug}/> : <SaveWishEntry />
}
function SaveWishEntry() {
  return <div><p>찜하려면 로그인해 주세요.</p><AuthEntryActions /></div>
}
function SaveWishContent({ slug }: { slug: string }) {
  const { store, state, member } = useWishlist()
  if (!member.checked || member.busy) return <p>로그인 상태를 확인하고 있습니다.</p>
  if (!member.member || member.error) return <SaveWishEntry />
  return <div><button disabled={state.busy} onClick={() => { void store.add(slug) }}>찜 저장</button>
    <Link to="/account/wishlist">찜한 상품 보기</Link>
    {state.notice && <p role="status">{state.notice}</p>}{state.error && <p role="alert">{state.error}</p>}</div>
}
export function WishlistPage() {
  const { store, state, member } = useWishlist()
  return <section><h1>찜한 상품</h1><p>상품 단위로 최대 100개를 저장할 수 있습니다. 옵션과 수량은 상품 상세에서 선택하세요.</p>
    {!member.checked || member.busy ? <p role="status">로그인 상태를 확인하고 있습니다.</p>
      : !member.member || member.error ? <><p>찜한 상품은 로그인 후 확인할 수 있습니다.</p><AuthEntryActions /></>
      : <><button disabled={state.busy} onClick={() => { void store.refresh() }}>찜 목록 다시 확인</button>
        {state.busy && <p role="status">찜 목록을 확인하고 있습니다.</p>}
        {state.error && <p role="alert">{state.error}</p>}{state.notice && <p role="status">{state.notice}</p>}
        {state.ready && (state.items.length ? state.items.map(row => <article key={row.productId}>
          <h2>{row.detail ? <Link to={'/products/'+row.detail.product.slug}>{row.detail.product.title}</Link> : '판매할 수 없는 상품'}</h2>
          {row.detail && <p>{row.detail.product.storeName} · {row.detail.product.minPrice === null ? '판매 준비 중' : row.detail.product.minPrice.toLocaleString('ko-KR')+'원부터'}{row.detail.product.soldOut && ' · 품절'}</p>}
          <button onClick={() => { void store.remove(row.productId) }}>찜 해제</button>
        </article>) : <p>찜한 상품이 없습니다.</p>)}</>}
    <p><Link to="/products">상품 둘러보기</Link></p>
  </section>
}
