import { useEffect, useRef, useState } from 'react'
import { requestJson, type JsonResult } from '../api/http'

const base = '/api/admin/settlements'
const states = { DRAFT: '초안', REVIEWED: '검토 완료', PAID_MOCK: '모의 지급 완료' } as const
const kinds: Record<string, string> = {
  PRODUCT: '상품', REFUND: '상품 환불', SHIPPING: '최초 배송비', SHIPPING_REFUND: '배송비 환불',
  COMMISSION: '수수료', COMMISSION_REVERSAL: '수수료 반환', RECOVERY_FEE: '회수비',
}
const reasons: Record<string, string> = {
  PAYMENT_EVIDENCE: '원 결제 대사 필요', OPEN_OR_HELD_UNIT: '구매확정 또는 클레임 처리 대기',
  HELD_UNIT: '수량 보류 중', DEFERRED_AFTER_HOLD: '보류 해제 주로 이월',
  CLAIM_RESERVATION: '회수비 정산 대기', UNRESOLVED_REFUND: '환불 결과 미확정',
  SHIPPING_OUTCOME_PENDING: '최초 배송비 처리 대기', INCOMPLETE_ORIGINAL_GRAPH: '원 거래와 환불 귀속 확인 필요',
}
type Summary = { id: string; weekStart: string; state: keyof typeof states; closed: boolean;
  version: number; gross: string; fee: string; net: string }
type Entry = { id: string; storeId: string; sellerOrderId: string; causeId: string; kind: string;
  itemId: string | null; ordinal: number | null; net: string; occurredAt: string; eligibleAt: string;
  eligibilityHolds: { id: string; releasedAt: string }[];
  eligibilityReservations?: { id: string; kind: string; acquiredAt: string; releasedAt: string }[] }
type Batch = Summary & { items: Entry[]; held: { sellerOrderId: string; reason: string }[] }
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const money = (v: unknown): v is string => typeof v === 'string' && /^-?\d+$/.test(v)
const day = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)
const won = (value: string) => BigInt(value).toLocaleString('ko-KR') + '원'
const dateTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' })
const instant = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
function summary(v: unknown): v is Summary {
  return record(v) && typeof v.id === 'string' && day(v.weekStart) && typeof v.closed === 'boolean'
    && typeof v.state === 'string' && Object.hasOwn(states, v.state) && Number.isSafeInteger(v.version)
    && money(v.gross) && money(v.fee) && money(v.net)
}
function detail(v: unknown): v is Batch {
  return summary(v) && 'items' in v && Array.isArray(v.items) && v.items.every(i => record(i)
    && [i.id, i.storeId, i.sellerOrderId, i.causeId, i.kind].every(s => typeof s === 'string') && money(i.net)
    && instant(i.occurredAt) && instant(i.eligibleAt) && Array.isArray(i.eligibilityHolds)
    && i.eligibilityHolds.every(h => record(h) && typeof h.id === 'string' && instant(h.releasedAt))
    && (i.eligibilityReservations === undefined || (Array.isArray(i.eligibilityReservations)
      && i.eligibilityReservations.every(r => record(r) && typeof r.id === 'string' && typeof r.kind === 'string'
        && instant(r.acquiredAt) && instant(r.releasedAt))))
    && ((i.itemId === null && i.ordinal === null) || (typeof i.itemId === 'string' && Number.isSafeInteger(i.ordinal))))
    && 'held' in v && Array.isArray(v.held) && v.held.every(h => record(h)
      && typeof h.sellerOrderId === 'string' && typeof h.reason === 'string')
}
function StoreEntries({ items }: { items: Entry[] }) {
  const stores = new Map<string, Entry[]>()
  for (const entry of items) {
    const group = stores.get(entry.storeId)
    if (group) group.push(entry)
    else stores.set(entry.storeId, [entry])
  }
  return <>{Array.from(stores, ([storeId, entries]) =>
    <section key={storeId} aria-label={'매대 ' + storeId}>
      <h4>매대 {storeId}</h4>
      <p>매대 지급액 {won(entries.reduce((sum, i) => sum + BigInt(i.net), 0n).toString())}</p>
      <ul>{entries.map(i => <li key={i.id}>{kinds[i.kind] ?? i.kind} · <strong>{won(i.net)}</strong>
        <br />하위 주문 {i.sellerOrderId}{i.itemId && i.ordinal !== null && <> · 항목 {i.itemId} · {i.ordinal + 1}번째 수량</>}
        <br />원인 {i.causeId} · 발생 <time dateTime={i.occurredAt}>{dateTime.format(new Date(i.occurredAt))}</time>
        {(i.eligibilityHolds.length > 0 || !!i.eligibilityReservations?.length) && <p>
          {[i.eligibilityHolds.length > 0 && '보류', !!i.eligibilityReservations?.length && '예약'].filter(Boolean).join('·')}
          {' 해제 후 지급 가능 '}<time dateTime={i.eligibleAt}>
          {dateTime.format(new Date(i.eligibleAt))}</time> (한국 시각)</p>}</li>)}</ul>
    </section>)}</>
}
const denied = (r: JsonResult) => r.kind === 'http' && (r.status === 401 || r.status === 403)
function conflictMessage(data: unknown): string {
  if (record(data) && data.code === 'SETTLEMENT_UNPAID_BATCH_CONFLICT' && Array.isArray(data.conflictingWeeks)
      && data.conflictingWeeks.length && data.conflictingWeeks.every(day)) {
    return `${data.conflictingWeeks.join(', ')} 주의 미지급 배치에 이월할 항목이 남아 있습니다. 해당 주의 초안을 먼저 다시 작성한 뒤 이 주의 정산을 재시도해 주세요.`
  }
  return '정산 대상이 없거나 명세가 달라졌습니다. 기존 배치는 상세를 조회한 뒤 변경된 주의 초안을 다시 작성해 주세요.'
}
async function command(path: string, body: object, signal: AbortSignal): Promise<JsonResult> {
  const csrf = await requestJson('/api/auth/csrf', { signal, cache: 'no-store' })
  if (csrf.kind !== 'success') return csrf
  if (!record(csrf.data) || typeof csrf.data.token !== 'string' || !csrf.data.token) {
    return { kind: 'invalid-response', status: csrf.status }
  }
  return requestJson(path, { signal, method: 'POST', cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.data.token }, body: JSON.stringify(body) })
}

export function SettlementPage() {
  const [rows, setRows] = useState<Summary[] | null>(null)
  const [week, setWeek] = useState('')
  const [selected, setSelected] = useState<Batch | null>(null)
  const [selectedId, selectId] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, refresh] = useState(0)
  const pending = useRef<AbortController | null>(null)
  const retry = useRef<{ action: string; key: string } | null>(null)
  useEffect(() => {
    const c = new AbortController()
    void Promise.all(['/policy', '/batches'].map(path => requestJson(base + path, { signal: c.signal, cache: 'no-store' })))
      .then(([policy, list]) => {
        if (c.signal.aborted) return
        if (denied(policy) || denied(list)) { setNotice('현재 정산 권한을 확인해 주세요.'); return }
        if (policy.kind !== 'success' || !record(policy.data) || !day(policy.data.currentWeekStart)
            || list.kind !== 'success' || !Array.isArray(list.data) || !list.data.every(summary)) {
          setNotice('정산 목록을 조회하지 못했습니다.'); return
        }
        setRows(list.data); setWeek(policy.data.currentWeekStart)
      })
    return () => { c.abort(); pending.current?.abort() }
  }, [reload])

  async function load(path: string, expected: { week?: string; id?: string }, body?: Record<string, unknown>) {
    if (pending.current) return
    const c = new AbortController()
    pending.current = c; setBusy(true); setNotice(''); setSelected(null); selectId(expected.id ?? '')
    let result: JsonResult
    if (body) {
      const action = path + JSON.stringify(body)
      if (retry.current?.action !== action) retry.current = { action, key: crypto.randomUUID() }
      result = await command(path, { key: retry.current.key, ...body }, c.signal)
    } else {
      result = await requestJson(path, { signal: c.signal, cache: 'no-store' })
    }
    if (c.signal.aborted) return
    setBusy(false); pending.current = null
    if (denied(result)) {
      setRows(null); selectId(''); setWeek(''); retry.current = null
      setNotice('현재 정산 권한을 확인해 주세요.'); return
    }
    if (result.kind !== 'success' || !detail(result.data)
        || (expected.week && result.data.weekStart !== expected.week) || (expected.id && result.data.id !== expected.id)) {
      setNotice(result.kind === 'http' && result.status === 409
        ? conflictMessage(result.data)
        : '처리 결과를 확인할 수 없습니다. 최신 내역을 조회해 주세요.')
      return
    }
    const updated = result.data
    retry.current = null; selectId(updated.id); setSelected(updated)
    setRows(current => [updated, ...(current ?? []).filter(b => b.id !== updated.id)])
  }
  const batches = base + '/batches'
  const read = (id: string) => void load(batches + '/' + id, { id })
  return <section style={{ overflowWrap: 'anywhere' }}>
    <h1>주간 모의 정산</h1>
    {notice && <p role="alert">{notice}</p>}
    <button disabled={busy} onClick={() => {
      setRows(null); setSelected(null); selectId(''); setWeek(''); setNotice(''); refresh(value => value + 1)
    }}>목록 다시 조회</button>
    {rows && <>
      <form onSubmit={e => { e.preventDefault(); void load(batches, { week }, { weekStart: week }) }}>
        <label>정산 주 시작일 <input type="date" value={week} required step="7" min="1970-01-05"
          disabled={busy} onChange={e => setWeek(e.target.value)} /></label>
        <p>한국 시각 월요일 0시에 한 주를 마감합니다. 지난주 배치를 늦게 실행해도 마감 후 다른 수량에 접수된 반품은 지난주 지급액에 영향을 주지 않습니다.</p>
        <button disabled={busy || !week}>초안 생성</button>
      </form>
      <h2>정산 배치</h2>
      {!rows.length && <p>생성된 배치가 없습니다.</p>}
      <ul>{rows.map(b => <li key={b.id}>{b.weekStart} · {states[b.state]} · {won(b.net)}{' '}
        <button aria-label={b.weekStart + ' 정산 상세'} disabled={busy} onClick={() => read(b.id)}>상세</button></li>)}</ul>
      {selectedId && <button disabled={busy} onClick={() => read(selectedId)}>상세 다시 조회</button>}
      {selected && <section aria-label="배치 상세"><h2>{selected.weekStart} · {states[selected.state]}</h2>
        <dl><dt>수수료 차감 전 금액</dt><dd>{won(selected.gross)}</dd>
          <dt>수수료</dt><dd>{won(selected.fee)}</dd><dt>모의 지급액</dt><dd>{won(selected.net)}</dd></dl>
        <h3>원장 명세</h3>
        <StoreEntries items={selected.items} />
        {selected.held.length > 0 && <><h3>보류 거래</h3><ul>{selected.held.map(h =>
          <li key={h.sellerOrderId + h.reason}>{h.sellerOrderId} · {reasons[h.reason] ?? h.reason}</li>)}</ul></>}
        {!selected.closed && <p>주간 마감 후 검토와 모의 지급이 가능합니다.</p>}
        {selected.state !== 'PAID_MOCK' && <button disabled={!selected.closed || busy} onClick={() =>
          void load(batches + '/' + selected.id + (selected.state === 'DRAFT' ? '/review' : '/simulate-pay'),
            { id: selected.id }, { expectedVersion: selected.version })}>
          {selected.state === 'DRAFT' ? '검토 확정' : '모의 지급'}</button>}
        {selected.state !== 'PAID_MOCK' && <button disabled={busy} onClick={() =>
          void load(batches, { week: selected.weekStart, id: selected.id }, { weekStart: selected.weekStart })}>
          선택한 주 초안 다시 작성</button>}
      </section>}
    </>}
  </section>
}
