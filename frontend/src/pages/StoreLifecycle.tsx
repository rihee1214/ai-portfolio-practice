import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { StoreOperationsStore } from '../seller/StoreOperationsStore'
import { storeStates, type ManagedStore } from '../seller/StoreEditorStore'
import { requestJson } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { FeaturePage } from './WorkspacePages'
import type { Feature } from '../navigation'

type Event = { actorId: string; action: string; beforeState: string; afterState: string; reason: string; occurredAt: string }
function eventValid(value: unknown): value is Event {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v.actorId === 'string' && /^[0-9a-f-]{36}$/i.test(v.actorId) && ['suspend', 'restore', 'close'].includes(String(v.action))
    && Object.keys(storeStates).includes(String(v.beforeState)) && Object.keys(storeStates).includes(String(v.afterState))
    && typeof v.reason === 'string' && [...v.reason].length > 0 && [...v.reason].length <= 300 && typeof v.occurredAt === 'string' && Number.isFinite(Date.parse(v.occurredAt))
}
export function StoreHistory({ id, version, admin = false }: { id: string; version: number; admin?: boolean }) {
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState<{ key: string; events?: Event[]; error?: ApiErrorView } | null>(null)
  const key = id + ':' + version + ':' + retry + ':' + admin
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void requestJson((admin ? '/api/admin/accounts/stores/' : '/api/seller/stores/') + id + '/history', {
      credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    }).then(response => {
      if (!active) return
      if (response.kind === 'success' && response.status === 200 && Array.isArray(response.data) && response.data.length <= 20 && response.data.every(eventValid)) setResult({ key, events: response.data })
      else setResult({ key, error: apiErrorView(response) ?? { message: '이력을 확인하지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [id, admin, key])
  return <section aria-label="최근 조치 이력"><h3>최근 조치 이력 (최대 20건)</h3>
    {result?.key !== key ? <p role="status">이력을 확인하고 있습니다.</p> : result.error ? <ApiErrorNotice error={result.error} /> : <>
      {!result.events?.length && <p>조치 이력이 없습니다.</p>}
      <ol>{result.events?.map((event, index) => <li key={index}>{storeStates[event.beforeState as keyof typeof storeStates]} → {storeStates[event.afterState as keyof typeof storeStates]} · {event.reason} · {new Date(event.occurredAt).toLocaleString('ko-KR')}{admin && <> · 처리자 {event.actorId}</>}</li>)}</ol>
    </>}
    <button onClick={() => setRetry(value => value + 1)}>이력 다시 확인</button>
  </section>
}
export function StoreOperationsPage() {
  const auth = useAuthStore()
  const [store] = useState(() => new StoreOperationsStore(auth))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [invalid, setInvalid] = useState('')
  useEffect(() => store.connect(), [store])
  function submit(event: SubmitEvent<HTMLFormElement>, item: ManagedStore) {
    event.preventDefault()
    const data = new FormData(event.currentTarget); const reason = String(data.get('reason') ?? '').trim()
    if (!reason || [...reason].length > 300) { setInvalid('조치 사유를 1~300자로 입력해 주세요.'); return }
    setInvalid(''); void store.change(item, item.state === 'OPEN' ? 'suspend' : 'restore', reason)
  }
  return <section className="auth-card store-card"><h1>매대 운영 관리</h1>
    <p>판매 정지는 회원 로그인 정지와 다릅니다. 복구 후에도 비공개를 유지하며 판매자가 다시 공개해야 합니다. 폐점한 매대는 복구할 수 없습니다.</p>
    {state.busy && <p role="status">매대 상태를 확인하고 있습니다.</p>}
    {state.items?.length === 0 && <p>등록된 매대가 없습니다.</p>}
    {state.items?.map(item => <article key={item.id} aria-label={item.slug}><h2>{item.name}</h2><p>{item.slug} · <strong>{storeStates[item.state]}</strong></p>
      {item.state !== 'CLOSED' && <form key={item.version} onSubmit={event => submit(event, item)}>
        <label htmlFor={'operation-' + item.id}>조치 사유</label><input id={'operation-' + item.id} name="reason" required maxLength={600} disabled={state.busy} />
        <button disabled={state.busy}>{item.state === 'OPEN' ? '판매 정지하기' : '판매 복구하기'}</button>
      </form>}
      <StoreHistory id={item.id} version={item.version} admin />
    </article>)}
    <button disabled={state.busy || state.page === 0} onClick={() => { void store.load(state.page - 1) }}>이전 목록</button><span> {state.page + 1}페이지 </span>
    <button disabled={state.busy || !state.hasMore} onClick={() => { void store.load(state.page + 1) }}>다음 목록</button>
    <button disabled={state.busy} onClick={() => { void store.load(state.page) }}>목록 새로고침</button>
    {invalid && <p role="alert">{invalid}</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">{state.notice}</p>}
  </section>
}
export function SellerFeaturePage({ feature }: { feature: Feature }) {
  const [attempt, retry] = useState(0)
  const [result, setResult] = useState<{ attempt: number; allowed?: boolean; error?: ApiErrorView } | null>(null)
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void requestJson('/api/seller/capabilities', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) }).then(response => {
      if (!active) return
      if (response.kind === 'success' && response.status === 200 && typeof response.data === 'object' && response.data !== null
        && 'manageCatalog' in response.data && typeof response.data.manageCatalog === 'boolean' && 'existingTrades' in response.data && typeof response.data.existingTrades === 'boolean') {
        setResult({ attempt, allowed: feature.path === 'products' ? response.data.manageCatalog : response.data.existingTrades })
      } else setResult({ attempt, error: apiErrorView(response) ?? { message: '판매 기능 상태를 확인하지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [feature.path, attempt])
  if (result?.attempt === attempt && result.allowed) return <><p>이 공간에 접근할 수 있습니다. 실제 거래 업무는 아직 준비 중입니다.</p><FeaturePage feature={feature} /></>
  return <section className="auth-card"><h1>{feature.title}</h1>{result?.attempt !== attempt ? <p role="status">판매 기능 상태를 확인하고 있습니다.</p>
    : result.error ? <ApiErrorNotice error={result.error} /> : <p role="alert">현재 매대 상태에서는 이 기능을 이용할 수 없습니다.</p>}
    <button onClick={() => retry(value => value + 1)}>판매 기능 다시 확인</button></section>
}
