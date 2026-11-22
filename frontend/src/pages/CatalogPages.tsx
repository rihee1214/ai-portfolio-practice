import {decimalAmount} from '../catalog/ReturnPolicy'
import { useContext, useEffect, useRef, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore, categoryValid, productValid, productStates, type Category, type Product } from '../catalog/CatalogStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { SkuPanel } from './SkuPanel'
import { MediaPanel } from './MediaPanel'
import { InventoryPanel, AdminInventory } from './InventoryPanel'
import { ProductDescriptionEditor, type DescriptionSubmission } from '../catalog/ProductDescriptionEditor'
import { ProductDraftContext, type ProductFormDraft } from '../catalog/ProductDraftContext'

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
    <AdminInventory />
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
  const scope = useContext(ProductDraftContext)
  const [owner] = useState(() => auth.getSnapshot().member?.id)
  const [restored, setRestored] = useState(() => {
    const draft = scope?.get()
    return draft?.owner === owner ? draft : null
  })
  const [initialPage] = useState(restored?.page ?? 0)
  const [store] = useState(() => new CatalogStore(auth, '/api/seller/products', productValid))
  const [categories] = useState(() => new CatalogStore(auth, '/api/categories', categoryValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const cats = useSyncExternalStore(categories.subscribe, categories.getSnapshot)
  const [selected, select] = useState<string | null>(restored?.selected ?? null)
  const [pinned, setPinned] = useState<Product | undefined>(restored?.pinned)
  useEffect(() => store.connect(initialPage), [store, initialPage]); useEffect(() => categories.connect(), [categories])
  const item = state.items?.find(row => row.id === selected) ?? (pinned?.id === selected ? pinned : null)
  function choose(id: string | null) {
    scope?.set(null)
    setRestored(null)
    setPinned(undefined)
    select(id)
  }
  function saveDraft(form: ProductFormDraft) {
    if (!scope || !owner || auth.getSnapshot().member?.id !== owner) return
    const previous = scope.get()
    scope.set({
      owner, page: state.page, selected, version: item?.version, pinned,
      form: { ...(previous?.selected === selected && previous.version === item?.version ? previous.form : {}), ...form },
    })
  }
  return <section className="auth-card store-card catalog-card"><h1>상품 관리</h1>
    <p>상품을 초안으로 등록하고 공개 등록·판매 중지·보관 상태를 관리합니다. 보관 후에는 수정하거나 복구할 수 없습니다. 상품을 선택하면 옵션·가격·이미지·재고를 관리할 수 있습니다. 재고 수량을 조정할 수 있으며 구매 기능은 아직 준비 중입니다.</p>
    {state.busy && <p role="status">처리 중입니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{cats.error && <ApiErrorNotice error={cats.error} />}{state.notice && <p role="status">{state.notice}</p>}
    <button disabled={state.busy} onClick={() => { choose(null); void store.load() }}>상품 다시 확인</button>
    {state.items && <><ul>{state.items.map(row => <li key={row.id}><button disabled={state.busy} onClick={() => { if (selected !== row.id) choose(row.id) }}>{row.title} · {productStates[row.state]}</button>{row.hiddenByAdmin && <span> 운영 비공개</span>}</li>)}</ul>
      {!state.items.length && <p>등록된 상품이 없습니다.</p>}
      <Pager {...state} label="상품 목록 페이지" load={page => { choose(null); void store.load(page) }} />
      <button disabled={state.busy} onClick={() => { if (selected !== null) choose(null) }}>새 상품</button>
      <p>카테고리 목록은 페이지별로 표시합니다. 선택한 상품의 기존 카테고리는 비활성 상태여도 유지할 수 있습니다.</p>
      <Pager {...cats} label="상품 카테고리 페이지" load={categories.load} />
      <button disabled={cats.busy} onClick={() => void categories.load()}>카테고리 다시 확인</button>
      <ProductForm key={item ? item.id + ':' + item.version : 'new'} item={item} categories={cats.items ?? []} busy={state.busy || cats.busy || !cats.items}
        draft={restored?.selected === (item?.id ?? null) && restored.version === item?.version ? restored.form : undefined}
        onDraft={saveDraft} save={async (fields, images) => {
        const saved = await store.save(item, fields, images)
        if (saved) {
          setRestored(null)
          setPinned(saved)
          if (owner) scope?.set({ owner, page: store.getSnapshot().page, selected: saved.id, version: saved.version, pinned: saved, form: {} })
        }
        if (!item && saved) select(saved.id)
      }} />
      {item && <SkuPanel key={item.id} product={item} />}
      {item && <InventoryPanel key={'stock-' + item.id} product={item.id} disabled={item.state === 'ARCHIVED'} />}
      {item && <MediaPanel key={'media-' + item.id} kind="products" target={item.id} disabled={item.state === 'ARCHIVED'} />}
    </>}
  </section>
}
function ProductForm({ item, categories, busy, draft, onDraft, save }: {
  item: Product | null; categories: Category[]; busy: boolean; draft?: ProductFormDraft
  onDraft: (draft: ProductFormDraft) => void; save: (fields: Record<string, unknown>, images: File[]) => Promise<void>
}) {
  const [categoryId, setCategoryId] = useState(draft?.categoryId ?? item?.categoryId ?? '')
  const [invalid, setInvalid] = useState('')
  const [confirmed, confirm] = useState(draft?.confirmed ?? false)
  const [state, changeState] = useState<Product['state']>(draft?.state ?? item?.state ?? 'DRAFT')
  const descriptionEditor = useRef<(() => DescriptionSubmission | null) | null>(null)
  const disabled = busy || item?.state === 'ARCHIVED'
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (state === 'ARCHIVED' && !confirmed) { setInvalid('보관 후 복구할 수 없음을 확인해 주세요.'); return }
    const data = new FormData(event.currentTarget)
    const title = String(data.get('title')).trim()
    if (!title || [...title].length > 120) { setInvalid('상품명은 1~120자로 입력해 주세요.'); return }
    const returnFee=String(data.get('returnFee')).trim()
    if(!decimalAmount(returnFee)){setInvalid('회수비는 0원 이상의 정수로 입력해 주세요.');return}
    setInvalid('')
    const submission = descriptionEditor.current?.()
    if (!submission) return
    const { images, ...description } = submission
    void save({ returnFee, slug: item?.slug ?? String(data.get('slug')), title, ...description, categoryId, state }, images)
  }
  return <form onSubmit={submit}><fieldset disabled={disabled}><legend>{item ? '상품 수정' : '상품 등록'}</legend>
    <label>상품 주소<input name="slug" required pattern="[a-z0-9][a-z0-9-]{1,61}[a-z0-9]" defaultValue={draft?.slug ?? item?.slug ?? ''} onChange={event => onDraft({ slug: event.target.value })} readOnly={!!item} /><small>영문 소문자·숫자·하이픈 3~63자, 등록 후 변경 불가</small></label>
    <label>상품명<input name="title" required aria-describedby="product-title-hint" defaultValue={draft?.title ?? item?.title ?? ''} onChange={event => onDraft({ title: event.target.value })} /></label><small id="product-title-hint">1~120자</small>
    <ProductDescriptionEditor plain={item?.description ?? ''} rich={item?.descriptionBlocks} editorRef={descriptionEditor} disabled={disabled}
      draft={draft?.editor} onDraft={editor => onDraft({ editor })} />
    <label>변심 반품 회수비<input name="returnFee" inputMode="numeric" pattern="0|[1-9][0-9]{0,18}" required defaultValue={draft?.returnFee ?? item?.returnPolicy.amount ?? '3000'} onChange={event => onDraft({ returnFee: event.target.value })} /></label><p>0원도 설정할 수 있습니다. 같은 원 배송의 반품 대상 중 가장 큰 금액을 적용하고, 추가 반품은 늘어난 차액만 부과합니다. 변경은 이후 새 주문에 적용됩니다.</p>
    <label htmlFor="product-category">카테고리</label><select id="product-category" required value={categoryId} onChange={event => { setCategoryId(event.target.value); onDraft({ categoryId: event.target.value }) }}><option value="">선택해 주세요</option>
      {categoryId && !categories.some(row => row.id === categoryId) && <option value={categoryId}>선택한 카테고리 (목록 밖 또는 비활성)</option>}
      {categories.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
    </select>
    <label htmlFor="product-state">상품 상태</label><select id="product-state" value={state} disabled={!item} onChange={event => { const value = event.target.value as Product['state']; changeState(value); onDraft({ state: value }) }}>
      {Object.entries(productStates).filter(([key]) => key !== 'DRAFT' || !item || item.state === 'DRAFT').map(([key, label]) => <option key={key} value={key}>{label}</option>)}
    </select>
    {state === 'ARCHIVED' && item?.state !== 'ARCHIVED' && <label className="checkbox-label"><input type="checkbox" checked={confirmed} onChange={event => { confirm(event.target.checked); onDraft({ confirmed: event.target.checked }) }} />보관 후 복구할 수 없음을 확인합니다</label>}
    {invalid && <p role="alert">{invalid}</p>}<button type="submit">{item ? '상품 저장' : '상품 등록'}</button>
  </fieldset>{item?.state === 'ARCHIVED' && <p>보관된 상품입니다. 기록은 유지됩니다.</p>}</form>
}
