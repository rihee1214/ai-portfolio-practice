import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link, useParams } from 'react-router'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore, productValid } from '../catalog/CatalogStore'
import { PromotionStore, promotionValid, koreanTime, type Promotion } from '../promotion/PromotionStore'
import { requestJson } from '../api/http'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
const states = { DRAFT: '초안', ACTIVE: '공개', STOPPED: '중지', ARCHIVED: '보관' }
export function PromotionPage() {
  const auth = useAuthStore()
  const [store] = useState(() => new PromotionStore(auth))
  const [products] = useState(() => new CatalogStore(auth, '/api/seller/products', productValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const catalog = useSyncExternalStore(products.subscribe, products.getSnapshot)
  const [id, setId] = useState<string | null>(null)
  useEffect(() => store.connect(), [store]); useEffect(() => products.connect(), [products])
  const item = state.items?.find(p => p.id === id) ?? null
  return <section className="auth-card store-card"><h1>공개 행사 관리</h1><p>회원과 비회원에게 같은 상품·배송 행사 조건을 제공합니다. 최종 견적은 주문 준비에서 다시 확인합니다.</p>
    {state.error && <ApiErrorNotice error={state.error} />}{catalog.error && <ApiErrorNotice error={catalog.error} />}{state.notice && <p role="status">{state.notice}</p>}
    <button disabled={state.busy} onClick={() => { setId(null); void store.load() }}>행사 목록 다시 확인</button>
    <ul>{state.items?.map(p => <li key={p.id}><button disabled={state.busy} onClick={() => setId(p.id)}>{p.title} · {states[p.state]}</button>{p.state === 'ACTIVE' && <Link to={'/events/' + p.id}>공개 안내</Link>}</li>)}</ul>
    <nav aria-label="행사 페이지"><button disabled={state.busy || !state.page} onClick={() => { setId(null); void store.load(state.page - 1) }}>이전</button><span>{state.page + 1} 페이지</span><button disabled={state.busy || !state.hasMore} onClick={() => { setId(null); void store.load(state.page + 1) }}>다음</button></nav>
    <button disabled={state.busy} onClick={() => setId(null)}>새 행사</button>
    {state.items && <PromotionForm key={item ? item.id + ':' + item.version : 'new'} item={item} busy={state.busy} products={catalog.items ?? []} save={fields => store.save(item, fields)} />}
    <nav aria-label="대상 상품 페이지"><button disabled={catalog.busy || !catalog.page} onClick={() => void products.load(catalog.page - 1)}>이전 상품</button><span>{catalog.page + 1} 페이지</span><button disabled={catalog.busy || !catalog.hasMore} onClick={() => void products.load(catalog.page + 1)}>다음 상품</button></nav>
  </section>
}
const local = (iso: string) => new Date(Date.parse(iso) + 9 * 3600000).toISOString().slice(0, 16)
function PromotionForm({ item, busy, products, save }: { item: Promotion | null; busy: boolean; products: { id: string; title: string }[]; save: (v: Record<string, unknown>) => void }) {
  const [scope, setScope] = useState(item?.scope ?? 'PRODUCT')
  const [targets, setTargets] = useState(item?.productIds ?? [])
  const [kind, setKind] = useState(item?.kind ?? 'FIXED')
  const [error, setError] = useState('')
  return <form onSubmit={(e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault(); const d = new FormData(e.currentTarget)
    const start = new Date(String(d.get('start')) + ':00+09:00'), end = new Date(String(d.get('end')) + ':00+09:00')
    const value = kind === 'FREE' ? 0 : Number(d.get('value')), cap = kind === 'FREE' || d.get('cap') === '' ? null : Number(d.get('cap'))
    if (!Number.isFinite(start.getTime()) || !(end > start) || !Number.isSafeInteger(value) || value < 0 || cap !== null && (!Number.isSafeInteger(cap) || cap < 0) || scope === 'PRODUCT' && !targets.length) { setError('기간·할인 값·대상 상품을 확인해 주세요.'); return }
    setError(''); save({ title: d.get('title'), scope, productIds: scope === 'PRODUCT' ? targets : [], startsAt: start.toISOString(), endsAt: end.toISOString(), kind: d.get('kind'), value, cap, state: d.get('state') })
  }}><fieldset disabled={busy || item?.state === 'ARCHIVED'}><legend>{item ? '행사 수정' : '행사 등록'}</legend>
    <label>행사 이름<input name="title" required maxLength={120} defaultValue={item?.title ?? ''} /></label>
    <label htmlFor="promotion-scope">행사 대상</label><select id="promotion-scope" value={scope} onChange={e => { setScope(e.target.value as Promotion['scope']); setKind('FIXED') }}><option value="PRODUCT">상품</option><option value="SHIPPING">배송</option></select>
    {scope === 'PRODUCT' && <fieldset><legend>대상 상품 (최대 20개)</legend>{products.map(p => <label key={p.id}><input type="checkbox" checked={targets.includes(p.id)} onChange={e => setTargets(e.target.checked ? [...targets, p.id] : targets.filter(id => id !== p.id))} />{p.title}</label>)}<p>{targets.length}개 선택</p><button type="button" onClick={() => setTargets([])}>대상 선택 초기화</button></fieldset>}
    <label>시작 (한국 시간)<input name="start" type="datetime-local" required defaultValue={item ? local(item.startsAt) : ''} /></label>
    <label>종료 (한국 시간)<input name="end" type="datetime-local" required defaultValue={item ? local(item.endsAt) : ''} /></label>
    <label htmlFor="promotion-kind">할인 방식</label><select id="promotion-kind" name="kind" value={kind} onChange={e => setKind(e.target.value as Promotion['kind'])}><option value="FIXED">정액</option>{scope === 'PRODUCT' ? <option value="RATE">정률</option> : <option value="FREE">무료 배송</option>}</select>
    <label>할인 값<input name="value" disabled={kind === 'FREE'} type="number" min="0" step="1" required defaultValue={item?.value ?? 1000} /></label><p>정액은 원, 정률은 100이 1%, 무료 배송은 0을 입력합니다.</p>
    <label>최대 할인 (원, 선택)<input name="cap" disabled={kind === 'FREE'} type="number" min="0" step="1" defaultValue={item?.cap ?? ''} /></label>
    <label>행사 상태<select name="state" defaultValue={item?.state ?? 'DRAFT'}>{Object.entries(states).filter(([key]) => item || key === 'DRAFT').map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
    {error && <p role="alert">{error}</p>}<button type="submit">{item ? '행사 저장' : '행사 등록'}</button>
  </fieldset></form>
}
export function PublicPromotionPage() {
  const { id } = useParams(); const [data, setData] = useState<{ id: string | undefined; items: Promotion[]; page: number; hasMore: boolean; error: boolean } | null>(null)
  const [retry, setRetry] = useState(0); const [page, setPage] = useState(0)
  useEffect(() => { let active = true; const controller = new AbortController()
    void requestJson('/api/events' + (id ? '/' + id : '?page=' + page), { credentials: 'same-origin', cache: 'no-store', signal: controller.signal }).then(r => {
      if (!active) return
      const values: unknown = r.kind === 'success' ? id ? [r.data] : (r.data as { items?: unknown })?.items : null
      const payload = r.kind === 'success' ? r.data as { page?: number; hasMore?: boolean } : null
      const valid = Array.isArray(values) && values.every(promotionValid) && (id || payload?.page === page && typeof payload?.hasMore === 'boolean')
      setData({ id, page, hasMore: !!payload?.hasMore, items: Array.isArray(values) && values.every(promotionValid) ? values : [], error: !valid })
    }); return () => { active = false; controller.abort() }
  }, [id, retry, page])
  const current = data && data.id === id && data.page === page ? data : null
  return <section><h1>공개 행사</h1><p>회원·비회원에게 동일하게 적용됩니다. 조회 시점 안내이며 실제 구매 시 다시 확인합니다.</p><button onClick={() => { setData(null); setRetry(v => v + 1) }}>행사 다시 확인</button>
    {!current && <p role="status">행사를 확인하고 있습니다.</p>}{current?.error && <p role="alert">현재 공개된 행사를 확인하지 못했습니다.</p>}
    {current?.items.map(p => <article key={p.id}><h2>{p.title}</h2>{!id && <Link to={'/events/' + p.id}>행사 상세</Link>}<p><Link to={'/stores/' + p.storeSlug}>{p.storeName}</Link></p>{p.targets.map(t => <p key={t.id}><Link to={'/products/' + t.slug}>{t.title}</Link></p>)}<p>{p.scope === 'PRODUCT' ? '상품 행사' : '배송 행사'}</p><p>{koreanTime(p.startsAt)}부터 {koreanTime(p.endsAt)} 전까지 (한국 시간)</p><p>{p.kind === 'FREE' ? '무료 배송' : p.kind === 'FIXED' ? p.value.toLocaleString('ko-KR') + '원 할인' : p.value / 100 + '% 할인'}{p.cap !== null && ' · 최대 ' + p.cap.toLocaleString('ko-KR') + '원'}</p></article>)}
    {!id && <nav aria-label="공개 행사 페이지"><button disabled={!page || !current} onClick={() => setPage(v => v - 1)}>이전 행사</button><span>{page + 1} 페이지</span><button disabled={!current?.hasMore || current.error} onClick={() => setPage(v => v + 1)}>다음 행사</button></nav>}
  </section>
}
