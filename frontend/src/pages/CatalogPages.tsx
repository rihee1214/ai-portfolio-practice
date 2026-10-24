import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore, categoryValid, productValid, productStates, type Category, type Product } from '../catalog/CatalogStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { SkuPanel } from './SkuPanel'

function Pager({ page, hasMore, busy, load, label }: { page: number; hasMore: boolean; busy: boolean; load: (page: number) => void; label: string }) {
  return <nav aria-label={label}><button disabled={busy || !page} onClick={() => load(page - 1)}>이전</button> <span>{page + 1} 페이지</span> <button disabled={busy || !hasMore || page >= 10000} onClick={() => load(page + 1)}>다음</button></nav>
}
export function CategoryPage() {
  const auth = useAuthStore()
  const [store] = useState(() => new CatalogStore(auth, '/api/admin/content/categories', categoryValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [selected, select] = useState<string | null>(null)
  useEffect(() => store.connect(), [store])
  const item = state.items?.find(row => row.id === selected) ?? null
  return <section className="auth-card store-card catalog-card"><h1>카테고리 운영</h1>
    <p>비활성화하면 연결 상품이 공개 조회에서 제외됩니다. 다시 활성화하면 공개 등록된 상품이 다시 노출됩니다. 기존 상품과 변경 이력은 유지됩니다.</p>
    {state.busy && <p role="status">처리 중입니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">{state.notice}</p>}
    <button disabled={state.busy} onClick={() => { select(null); void store.load() }}>목록 다시 확인</button>
    {state.items && <><ul>{state.items.map(row => <li key={row.id}><button disabled={state.busy} onClick={() => select(row.id)}>{row.name} · {row.active ? '활성' : '비활성'}</button></li>)}</ul>
      {!state.items.length && <p>등록된 카테고리가 없습니다.</p>}
      <Pager {...state} label="카테고리 목록 페이지" load={page => { select(null); void store.load(page) }} />
      <button disabled={state.busy} onClick={() => select(null)}>새 카테고리</button>
      <CategoryForm key={item ? item.id + ':' + item.version : 'new'} item={item} busy={state.busy} save={fields => store.save(item, fields)} />
    </>}
  </section>
}
function CategoryForm({ item, busy, save }: { item: Category | null; busy: boolean; save: (fields: Record<string, unknown>) => void }) {
  const [invalid, setInvalid] = useState('')
  return <form onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget); const name = String(data.get('name')).trim()
    if (!name || [...name].length > 80) { setInvalid('카테고리 이름을 1~80자로 입력해 주세요.'); return }
    setInvalid(''); save({ name, active: data.get('active') === 'on' })
  }}>
    <fieldset disabled={busy}><legend>{item ? '카테고리 수정' : '카테고리 등록'}</legend>
      <label>카테고리 이름<input name="name" required aria-describedby="category-name-hint" defaultValue={item?.name ?? ''} /></label><small id="category-name-hint">1~80자</small>
      <label className="checkbox-label"><input name="active" type="checkbox" defaultChecked={item?.active ?? true} />활성 카테고리</label>
      {invalid && <p role="alert">{invalid}</p>}<button type="submit">{item ? '카테고리 저장' : '카테고리 등록'}</button>
    </fieldset>
  </form>
}
export function ProductPage() {
  const auth = useAuthStore()
  const [store] = useState(() => new CatalogStore(auth, '/api/seller/products', productValid))
  const [categories] = useState(() => new CatalogStore(auth, '/api/categories', categoryValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const cats = useSyncExternalStore(categories.subscribe, categories.getSnapshot)
  const [selected, select] = useState<string | null>(null)
  useEffect(() => store.connect(), [store]); useEffect(() => categories.connect(), [categories])
  const item = state.items?.find(row => row.id === selected) ?? null
  return <section className="auth-card store-card catalog-card"><h1>상품 관리</h1>
    <p>상품을 초안으로 등록하고 공개 등록·판매 중지·보관 상태를 관리합니다. 보관 후에는 수정하거나 복구할 수 없습니다. 상품을 선택하면 옵션·가격·SKU 판매 상태를 관리할 수 있습니다. 이미지·재고와 구매 기능은 아직 준비 중입니다.</p>
    {state.busy && <p role="status">처리 중입니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{cats.error && <ApiErrorNotice error={cats.error} />}{state.notice && <p role="status">{state.notice}</p>}
    <button disabled={state.busy} onClick={() => { select(null); void store.load() }}>상품 다시 확인</button>
    {state.items && <><ul>{state.items.map(row => <li key={row.id}><button disabled={state.busy} onClick={() => select(row.id)}>{row.title} · {productStates[row.state]}</button>{row.hiddenByAdmin && <span> 운영 비공개</span>}</li>)}</ul>
      {!state.items.length && <p>등록된 상품이 없습니다.</p>}
      <Pager {...state} label="상품 목록 페이지" load={page => { select(null); void store.load(page) }} />
      <button disabled={state.busy} onClick={() => select(null)}>새 상품</button>
      <p>카테고리 목록은 페이지별로 표시합니다. 선택한 상품의 기존 카테고리는 비활성 상태여도 유지할 수 있습니다.</p>
      <Pager {...cats} label="상품 카테고리 페이지" load={categories.load} />
      <button disabled={cats.busy} onClick={() => void categories.load()}>카테고리 다시 확인</button>
      <ProductForm key={item ? item.id + ':' + item.version : 'new'} item={item} categories={cats.items ?? []} busy={state.busy || cats.busy || !cats.items} save={fields => store.save(item, fields)} />
      {item && <SkuPanel key={item.id} product={item} />}
    </>}
  </section>
}
function ProductForm({ item, categories, busy, save }: { item: Product | null; categories: Category[]; busy: boolean; save: (fields: Record<string, unknown>) => void }) {
  const [categoryId, setCategoryId] = useState(item?.categoryId ?? '')
  const [invalid, setInvalid] = useState('')
  const [confirmed, confirm] = useState(false)
  const [state, changeState] = useState<Product['state']>(item?.state ?? 'DRAFT')
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (state === 'ARCHIVED' && !confirmed) { setInvalid('보관 후 복구할 수 없음을 확인해 주세요.'); return }
    const data = new FormData(event.currentTarget)
    const title = String(data.get('title')).trim(); const description = String(data.get('description')).replace(/\r\n?/g, '\n').trim()
    if (!title || [...title].length > 120 || [...description].length > 4000) { setInvalid('상품명은 1~120자, 설명은 4000자 이내로 입력해 주세요.'); return }
    setInvalid(''); save({ slug: item?.slug ?? String(data.get('slug')), title, description, categoryId, state })
  }
  return <form onSubmit={submit}><fieldset disabled={busy || item?.state === 'ARCHIVED'}><legend>{item ? '상품 수정' : '상품 등록'}</legend>
    <label>상품 주소<input name="slug" required pattern="[a-z0-9][a-z0-9-]{1,61}[a-z0-9]" defaultValue={item?.slug ?? ''} readOnly={!!item} /><small>영문 소문자·숫자·하이픈 3~63자, 등록 후 변경 불가</small></label>
    <label>상품명<input name="title" required aria-describedby="product-title-hint" defaultValue={item?.title ?? ''} /></label><small id="product-title-hint">1~120자</small>
    <label htmlFor="product-description">설명</label><textarea id="product-description" name="description" aria-describedby="product-description-hint" defaultValue={item?.description ?? ''} /><small id="product-description-hint">줄바꿈 가능, 4000자 이내</small>
    <label htmlFor="product-category">카테고리</label><select id="product-category" required value={categoryId} onChange={event => setCategoryId(event.target.value)}><option value="">선택해 주세요</option>
      {categoryId && !categories.some(row => row.id === categoryId) && <option value={categoryId}>선택한 카테고리 (목록 밖 또는 비활성)</option>}
      {categories.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
    </select>
    <label htmlFor="product-state">상품 상태</label><select id="product-state" value={state} disabled={!item} onChange={event => changeState(event.target.value as Product['state'])}>
      {Object.entries(productStates).filter(([key]) => key !== 'DRAFT' || !item || item.state === 'DRAFT').map(([key, label]) => <option key={key} value={key}>{label}</option>)}
    </select>
    {state === 'ARCHIVED' && item?.state !== 'ARCHIVED' && <label className="checkbox-label"><input type="checkbox" checked={confirmed} onChange={event => confirm(event.target.checked)} />보관 후 복구할 수 없음을 확인합니다</label>}
    {invalid && <p role="alert">{invalid}</p>}<button type="submit">{item ? '상품 저장' : '상품 등록'}</button>
  </fieldset>{item?.state === 'ARCHIVED' && <p>보관된 상품입니다. 기록은 유지됩니다.</p>}</form>
}
