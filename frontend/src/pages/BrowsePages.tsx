import {returnPolicyText} from '../catalog/ReturnPolicy'
import { SaveWish } from './WishlistPages'
import { AddToCart } from './CartPages'
import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { BrowseResource, detailValid, pageValid, parseQuery, queryString, slugValid, type Item, type Query } from '../catalog/Browse'
import { requestJson } from '../api/http'
import { PublicMedia } from './MediaPanel'
import { PublicReviews } from '../content/PublicReviews'
import { ProductReport } from '../reports/ProductReport'
import { ProductQuestions } from '../content/ProductQuestions'
import './product-browse.css'

function useResource<T>(path: string, validate: (value: unknown) => value is T) {
  const [resource] = useState(() => new BrowseResource<T>(validate, requestJson))
  const state = useSyncExternalStore(resource.subscribe, resource.getSnapshot)
  useEffect(() => { if (path) void resource.load(path); return resource.stop }, [resource, path])
  return { ...(state.key === path ? state : { key: path, busy: Boolean(path) }), retry: () => { void resource.load(path) } }
}
const price = (value: number) => value.toLocaleString('ko-KR') + '원'
function Card({ item, search }: { item: Item; search: string }) {
  return <article className="product-card"><div className="product-info">
    <p>{item.categoryName} · <Link to={'/stores/' + item.storeSlug}>{item.storeName}</Link></p>
    <h3><Link to={'/products/' + item.slug + '?' + search}>{item.title}</Link></h3>
    <p>{item.minPrice === null ? '판매 준비 중' : '옵션 최저가 ' + price(item.minPrice)}</p>
    {item.minPrice !== null && item.soldOut && <p>품절</p>}
  </div></article>
}
type Categories = { items: { id: string; name: string }[]; page: number; hasMore: boolean }
function categoriesValid(value: unknown): value is Categories {
  if (!value || typeof value !== 'object') return false
  const v = value as Categories
  return Array.isArray(v.items) && v.items.length <= 20 && Number.isInteger(v.page) && v.page >= 0 && typeof v.hasMore === 'boolean'
    && v.items.every(c => c && typeof c.id === 'string' && /^[0-9a-f-]{36}$/i.test(c.id) && typeof c.name === 'string')
}
function CategoryOptions({ selected, compact }: { selected: string; compact: boolean }) {
  const [page, setPage] = useState(0)
  const [choice, choose] = useState({ id: selected, name: '선택한 카테고리' })
  const state = useResource('/api/categories?page=' + page, categoriesValid)
  const canAdvance = page < 10000 && state.data?.hasMore
  return <div><label htmlFor="browse-category">카테고리</label><select id="browse-category" name="category" value={choice.id} onChange={event => choose({ id: event.target.value, name: event.target.selectedOptions[0]?.textContent ?? '선택한 카테고리' })}>
    <option value="">전체 카테고리</option>
    {choice.id && !state.data?.items.some(c => c.id === choice.id) && <option value={choice.id}>{choice.name}</option>}
    {state.data?.items.map(c => <option value={c.id} key={c.id}>{c.name}</option>)}
  </select>
    {state.error && <p role="alert">카테고리를 불러오지 못했습니다. <button type="button" onClick={state.retry}>카테고리 재시도</button></p>}
    {(!compact || page > 0 || canAdvance) && <div className="browse-category-pages">
      <button type="button" disabled={page === 0 || state.busy} onClick={() => setPage(page - 1)}>이전 카테고리</button>
      <button type="button" disabled={!canAdvance || state.busy} onClick={() => setPage(page + 1)}>다음 카테고리</button>
    </div>}
  </div>
}
export function BrowseList({ store }: { store?: string }) {
  const [params, setParams] = useSearchParams()
  const query = parseQuery(params.toString())
  const effective = query && { ...query, ...(store ? { store } : {}) }
  const search = effective ? queryString(effective) : ''
  const state = useResource(effective ? '/api/browse/products?' + search : '', pageValid)
  const [invalid, setInvalid] = useState('')
  const resetPath = store ? '/stores/' + store : '/products'
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget); const next = new URLSearchParams()
    for (const key of ['q', 'category', 'minPrice', 'maxPrice', 'sort']) next.set(key, String(data.get(key) ?? ''))
    if (effective?.store) next.set('store', effective.store)
    next.set('inStock', data.get('inStock') === 'on' ? 'true' : 'false'); next.set('page', '0')
    const value = parseQuery(next.toString())
    if (!value) { setInvalid('검색어·가격 범위를 확인해 주세요.'); return }
    setInvalid(''); setParams(queryString(value))
  }
  if (!effective) return <div role="alert">검색 주소가 올바르지 않습니다. <Link to={resetPath} onClick={() => setInvalid('')}>검색 조건 초기화</Link></div>
  function move(page: number) { setInvalid(''); setParams(queryString({ ...effective as Query, page })) }

  const hasPriceOrStock = Boolean(effective.minPrice || effective.maxPrice || effective.inStock)
  const isFiltered = Boolean(effective.q || effective.category || effective.store || hasPriceOrStock)
  const reset = <Link to={resetPath} onClick={() => setInvalid('')}>{store ? '검색 조건 초기화' : '조건 초기화'}</Link>
  const keyword = <div><label htmlFor="browse-q">상품 검색</label><input id="browse-q" name="q" defaultValue={effective.q} maxLength={160} /></div>
  const category = <CategoryOptions selected={effective.category} compact={!store} />
  const prices = <>
    <div><label htmlFor="browse-min">최소 가격</label><input id="browse-min" name="minPrice" type="number" min="0" max="999999999" defaultValue={effective.minPrice} /></div>
    <div><label htmlFor="browse-max">최대 가격</label><input id="browse-max" name="maxPrice" type="number" min="0" max="999999999" defaultValue={effective.maxPrice} /></div>
  </>
  const sort = <div><label htmlFor="browse-sort">정렬</label><select id="browse-sort" name="sort" defaultValue={effective.sort}>
      <option value="newest">최신 등록순</option><option value="price_asc">낮은 가격순</option><option value="price_desc">높은 가격순</option><option value="name">상품명순</option>
  </select></div>
  const stock = <label className="checkbox-label"><input type="checkbox" name="inStock" defaultChecked={effective.inStock} />재고 있는 상품만</label>
  const page = state.data
  const canAdvance = page?.hasMore && effective.page < 10000
  const hasPagination = page && (store || effective.page > 0 || canAdvance)
  const empty = page?.items.length === 0
  const emptyMessage = store ? '조건에 맞는 상품이 없습니다.' : effective.page > 0 && !isFiltered ? '이 페이지에는 상품이 없습니다' : isFiltered ? '검색 결과가 없습니다' : '아직 등록된 상품이 없습니다'

  return <section aria-label="상품 탐색">
    <form key={search} onSubmit={submit} className={store ? 'browse-form' : 'browse-form product-browse-form'}>
      {store ? <>{keyword}{category}{prices}{sort}{stock}<button>검색 적용</button>{reset}</> : <>
        <div className="product-browse-primary">{keyword}{category}{sort}</div>
        <details className="product-browse-details" open={hasPriceOrStock} onInvalidCapture={event => { event.currentTarget.open = true }}>
          <summary>가격·재고 필터</summary>
          <div className="product-browse-advanced">{prices}{stock}</div>
          <p>가격은 활성 옵션의 최저가입니다. 재고는 조회 시점의 수량입니다.</p>
        </details>
        <div className="product-browse-actions"><button>검색 적용</button>{!empty && (isFiltered || effective.sort !== 'newest') && reset}</div>
      </>}
    </form>
    {invalid && <p role="alert">{invalid}</p>}
    {store && <p>가격은 활성 옵션의 최저가입니다. 재고는 조회 시점의 수량입니다.</p>}
    <div className={store ? undefined : 'product-browse-results'}>
      {state.busy && <p role="status">상품을 불러오고 있습니다.</p>}
      {state.error && <p role="alert">{state.error.message} <button onClick={state.retry}>상품 다시 불러오기</button></p>}
      {page && <>
        {(store || !empty) && <p role="status">{hasPagination ? `${effective.page + 1}페이지 · ` : ''}{page.items.length}개 상품</p>}
        {empty ? <div className={store ? undefined : 'product-browse-empty'}>
          <p role={store ? undefined : 'status'}>{emptyMessage}</p>
          {!store && isFiltered && reset}
        </div> : <div className="product-grid">{page.items.map(item => <Card key={item.slug} item={item} search={search} />)}</div>}
        {hasPagination && <nav aria-label="상품 페이지">
          <button disabled={effective.page === 0} onClick={() => move(effective.page - 1)}>이전 상품</button>
          <button disabled={!canAdvance} onClick={() => move(effective.page + 1)}>다음 상품</button>
        </nav>}
      </>}
    </div>
  </section>
}
export function BrowsePage() { return <section className="product-browse-page"><h1>상품 둘러보기</h1><BrowseList /></section> }
export function HomeProducts() {
  const state = useResource('/api/browse/products?sort=newest&page=0', pageValid)
  return <section><h2>매대에서 만나는 일상</h2><Link to="/products">전체 상품 둘러보기</Link>
    {state.busy && <p role="status">상품을 불러오고 있습니다.</p>}
    {state.error && <p role="alert">{state.error.message} <button onClick={state.retry}>상품 다시 불러오기</button></p>}
    {state.data && (state.data.items.length ? <div className="product-grid">{state.data.items.slice(0, 3).map(item => <Card key={item.slug} item={item} search="sort=newest&page=0" />)}</div> : <p>공개된 상품이 없습니다.</p>)}
  </section>
}
export function ProductDetailPage() {
  const { slug } = useParams(); const [params] = useSearchParams()
  const state = useResource(slugValid(slug) ? '/api/browse/products/' + slug : '', detailValid)
  const [selection, select] = useState('')
  const data = state.data?.product.slug === slug ? state.data : undefined
  const offer = data?.offers.find(o => o.id === selection) ?? data?.offers[0]
  const query = parseQuery(params.toString())
  return <section><h1>상품 상세</h1><Link to={'/products' + (query ? '?' + queryString(query) : '')}>상품 목록으로</Link>
    {!slugValid(slug) && <p role="alert">상품 주소가 올바르지 않습니다.</p>}
    {state.busy && <p role="status">상품을 불러오고 있습니다.</p>}
    {state.error && <p role="alert">{state.error.message} <button onClick={state.retry}>상품 다시 불러오기</button></p>}
    {data && <><h2>{data.product.title}</h2><SaveWish slug={data.product.slug} /><Link to={'/stores/' + data.product.storeSlug}>{data.product.storeName}</Link><p>{data.product.categoryName}</p>
      <PublicMedia key={slug} kind="products" slug={slug!} />
      <PublicReviews key={'reviews-'+data.product.slug} slug={data.product.slug}/>
      <ProductQuestions slug={data.product.slug}/><ProductReport slug={data.product.slug}/>
      <p className="product-description">{data.product.description}</p><p data-testid="product-return-policy">{returnPolicyText(data.product.returnPolicy)}</p>
      {offer ? <><label htmlFor="browse-option">상품 옵션</label><select id="browse-option" value={offer.id} onChange={e => select(e.target.value)}>
        {data.offers.map(o => <option key={o.id} value={o.id}>{o.options.length ? o.options.map(x => x.name + ': ' + x.value).join(' / ') : '기본 상품'} · {price(o.price)}{o.available === 0 ? ' · 품절' : ''}</option>)}
      </select><p>선택 가격: {price(offer.price)}</p><p>{offer.available === 0 ? '품절' : '가용 수량: ' + offer.available}</p><p>재고는 조회 시점의 수량입니다.</p><AddToCart sku={offer.id} available={offer.available} /></> : <p>판매 준비 중</p>}
    </>}
  </section>
}
