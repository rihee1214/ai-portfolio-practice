import { useEffect, useRef, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore } from '../catalog/CatalogStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

type Balance = { id: string; code: string; onHand: number; reserved: number; available: number; version: number }
type Movement = { id: string; actorId: string; cause: string; reason: string; beforeOnHand: number; afterOnHand: number; reserved: number; version: number; occurredAt: string }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v)
const quantity = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0 && Number(v) <= 999999999
const balanceValid = (v: unknown): v is Balance => object(v) && uuid(v.id) && typeof v.code === 'string' && quantity(v.onHand) && quantity(v.reserved) && v.reserved <= v.onHand && v.available === v.onHand - v.reserved && Number.isSafeInteger(v.version) && Number(v.version) >= 0
const movementValid = (v: unknown): v is Movement => object(v) && uuid(v.id) && uuid(v.actorId) && v.cause === 'MANUAL_ADJUST' && typeof v.reason === 'string' && !!v.reason.trim() && [...v.reason].length <= 200 && quantity(v.beforeOnHand) && quantity(v.afterOnHand) && quantity(v.reserved) && v.reserved <= Math.min(v.beforeOnHand, v.afterOnHand) && Number.isSafeInteger(v.version) && Number(v.version) > 0 && typeof v.occurredAt === 'string' && Number.isFinite(Date.parse(v.occurredAt))

export function InventoryPanel({ product, administrator = false, disabled = false }: { product: string; administrator?: boolean; disabled?: boolean }) {
  const auth = useAuthStore()
  const path = (administrator ? '/api/admin/content/products/' : '/api/seller/products/') + product + '/inventory'
  const [store] = useState(() => new CatalogStore(auth, path, balanceValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [selected, select] = useState<string | null>(null)
  useEffect(() => store.connect(), [store])
  const item = state.items?.find(row => row.id === selected)
  return <section aria-label="재고 관리"><h2>SKU 재고 관리</h2>
    <p>실제 보유 수량을 입력합니다. 예약 수량보다 낮출 수 없습니다. 예약 생성·확정·해제 및 구매 기능은 아직 제공하지 않습니다.</p>
    {state.busy && <p role="status">재고 처리 중입니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">재고 {state.notice}</p>}
    <button disabled={state.busy} onClick={() => { select(null); void store.load() }}>재고 다시 확인</button>
    <ul>{state.items?.map(row => <li key={row.id}><button disabled={state.busy} onClick={() => select(row.id)}>{row.code} · 보유 {row.onHand} · 예약 {row.reserved} · 가용 {row.available}</button></li>)}</ul>
    <nav aria-label="재고 목록 페이지"><button disabled={state.busy || !state.page} onClick={() => { select(null); void store.load(state.page - 1) }}>이전 재고</button> {state.page + 1} 페이지 <button disabled={state.busy || !state.hasMore || state.page >= 10000} onClick={() => { select(null); void store.load(state.page + 1) }}>다음 재고</button></nav>
    {item && <><StockForm key={'form:' + item.id + ':' + item.version} item={item} disabled={disabled || state.busy} save={fields => store.save(item, fields)} /><History key={'history:' + item.id + ':' + item.version} path={path + '/' + item.id + '/history'} /></>}
  </section>
}
function StockForm({ item, disabled, save }: { item: Balance; disabled: boolean; save: (fields: Record<string, unknown>) => void }) {
  const [error, setError] = useState('')
  const intent = useRef<{ body: string; requestId: string } | null>(null)
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget)
    const raw = String(data.get('quantity')); const onHand = Number(raw); const reason = String(data.get('reason')).trim()
    if (!/^\d+$/.test(raw) || !quantity(onHand) || onHand < item.reserved || onHand === item.onHand || !reason || [...reason].length > 200 || [...reason].some(c => c.codePointAt(0)! < 32 || c.codePointAt(0) === 127)) { setError('예약 수량 이상의 다른 보유 수량과 1~200자 사유를 입력해 주세요.'); return }
    const body = JSON.stringify({ onHand, reason, version: item.version })
    if (intent.current?.body !== body) intent.current = { body, requestId: crypto.randomUUID() }
    setError(''); save({ onHand, reason, requestId: intent.current.requestId })
  }
  return <form onSubmit={submit}><fieldset disabled={disabled}><legend>재고 수량 조정</legend>
    <p>{item.code} · 예약 {item.reserved} · 가용 {item.available}</p>
    <label>보유 수량<input name="quantity" type="number" min={item.reserved} max="999999999" step="1" required defaultValue={item.onHand} /></label>
    <label>재고 조정 사유<input name="reason" required /></label>
    {error && <p role="alert">{error}</p>}<button type="submit">재고 조정 저장</button>
  </fieldset></form>
}
function History({ path }: { path: string }) {
  const auth = useAuthStore(); const [store] = useState(() => new CatalogStore(auth, path, movementValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => store.connect(), [store])
  return <section aria-label="재고 조정 이력"><h3>재고 조정 이력</h3>{state.error && <ApiErrorNotice error={state.error} />}
    <ul>{state.items?.map(row => <li key={row.id}>{row.beforeOnHand} → {row.afterOnHand} · {row.reason} · 예약 {row.reserved} · {new Date(row.occurredAt).toLocaleString()}<br />조정자: {row.actorId}</li>)}</ul>
    <button disabled={state.busy || !state.page} onClick={() => void store.load(state.page - 1)}>이전 이력</button> {state.page + 1} 페이지 <button disabled={state.busy || !state.hasMore || state.page >= 10000} onClick={() => void store.load(state.page + 1)}>다음 이력</button>
    <button disabled={state.busy} onClick={() => void store.load()}>이력 다시 확인</button>
  </section>
}
export function AdminInventory() {
  const [product, select] = useState(''); const [error, setError] = useState('')
  return <section><h2>운영자 재고 조회</h2><form onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault(); const id = String(new FormData(event.currentTarget).get('product')).trim()
    if (!uuid(id)) { setError('유효한 상품 식별자를 입력해 주세요.'); return }
    setError(''); select(id)
  }}><label>재고 조회 상품 식별자<input name="product" required /></label><button>상품 재고 조회</button>{error && <p role="alert">{error}</p>}</form>
    {product && <InventoryPanel key={product} product={product} administrator />}
  </section>
}
