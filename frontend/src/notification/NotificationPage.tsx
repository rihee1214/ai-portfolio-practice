import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { requestJson } from '../api/http'
import { useAuthStore } from '../auth/AuthContext'

const labels = {
  PAYMENT_BUYER: '주문 결제가 완료되었습니다.', PAYMENT_SELLER: '매대 주문의 결제가 완료되었습니다.',
  PURCHASE_CONFIRMED: '구매가 확정되었습니다.', SHIPPED: '주문 상품이 발송되었습니다.',
  EXCHANGE_SHIPPED: '교환 상품이 발송되었습니다.', PRODUCT_ANSWER: '상품 문의에 답변이 등록되었습니다.',
  CASE_REPLY: '거래지원 문의에 새 답변이 있습니다.', MODERATION_ACTION: '운영 조치가 적용되었습니다.',
  MODERATION_RESTORED: '운영 조치가 복원되었습니다.', REPORT_CLOSED: '신고 처리가 종결되었습니다.',
}
type Entry = { id: string; kind: keyof typeof labels; destination: string; readAt: string | null }
function entry(value: unknown): value is Entry {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  return typeof row.id === 'string' && typeof row.kind === 'string' && Object.hasOwn(labels, row.kind)
    && typeof row.destination === 'string' && /^(?:\/(?:account|seller|admin|products)\/|\/support\?case=[0-9a-f-]{36}$)/i.test(row.destination) && !row.destination.includes('\\')
    && (row.readAt === null || typeof row.readAt === 'string')
}

export function NotificationPage() {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  return session.member ? <Inbox key={[session.member.id,auth.getGeneration(),...session.member.roles].join(':')} /> : <p>로그인 후 알림을 확인할 수 있습니다.</p>
}

function Inbox() {
  const [rows, setRows] = useState<Entry[] | null>(null)
  const [error, setError] = useState(false)
  const [revision, setRevision] = useState(0)
  const [before, setBefore] = useState<string | null>(null)
  const [next, setNext] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  function refresh(page: string | null) {
    controller.current?.abort()
    setRows(null)
    setNext(null)
    setError(false)
    setBefore(page)
    setRevision(n => n + 1)
  }
  useEffect(() => {
    const active = new AbortController()
    controller.current = active
    void requestJson('/api/account/notifications' + (before ? '?before=' + encodeURIComponent(before) : ''), { signal: active.signal, cache: 'no-store' }).then(result => {
      if (active.signal.aborted) return
      if (result.kind === 'success' && result.data && typeof result.data === 'object' && 'items' in result.data
        && Array.isArray(result.data.items) && result.data.items.every(entry) && 'next' in result.data
        && (result.data.next === null || typeof result.data.next === 'string')) {
        setRows(result.data.items); setNext(result.data.next)
      }
      else setError(true)
    })
    const onFocus = () => refresh(null)
    window.addEventListener('focus', onFocus)
    return () => { active.abort(); window.removeEventListener('focus', onFocus) }
  }, [revision, before])
  async function read(id: string) {
    const signal = controller.current?.signal
    if (!signal || signal.aborted) return
    const token = await requestJson('/api/auth/csrf', { signal, cache: 'no-store' })
    if (signal.aborted) return
    if (token.kind !== 'success' || !token.data || typeof token.data !== 'object'
      || !('token' in token.data) || typeof token.data.token !== 'string') {
      setRows(null); setError(true); return
    }
    const result = await requestJson('/api/account/notifications/' + encodeURIComponent(id) + '/read',
      { method: 'POST', headers: { 'X-CSRF-TOKEN': token.data.token }, signal })
    if (signal.aborted) return
    if (result.kind === 'success' && entry(result.data) && result.data.id === id) {
      const saved = result.data
      setRows(current => current?.map(row => row.id === id ? saved : row) ?? null)
    } else { setRows(null); setError(true) }
  }
  return <section aria-label="내 알림"><h1>내 알림</h1>
    {error ? <p role="alert">알림을 확인할 수 없습니다. <button onClick={() => refresh(before)}>다시 조회</button></p>
      : rows === null ? <p role="status">조회 중</p> : rows.length === 0 ? <p>받은 알림이 없습니다.</p>
      : <ul>{rows.map(row => <li key={row.id}><a href={row.destination}>{labels[row.kind]}</a> {' '}
        {row.readAt ? '읽음' : <button onClick={() => void read(row.id)}>읽음 표시</button>}</li>)}</ul>}
    {next && <button onClick={() => refresh(next)}>이전 알림 보기</button>}
    {before && <button onClick={() => refresh(null)}>최신 알림 보기</button>}
  </section>
}
