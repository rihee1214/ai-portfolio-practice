import { useEffect, useState, useSyncExternalStore } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { requestJson } from '../api/http'
import { useInquiryCommand } from '../content/useInquiryCommand'

type Failure = { event: string; job: string }
function failure(value: unknown): value is Failure {
  return !!value && typeof value === 'object' && 'event' in value && typeof value.event === 'string'
    && 'job' in value && typeof value.job === 'string'
}
export function RetryNotificationsPage() {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  return session.member?.roles.includes('ADMIN')
    ? <RetryList key={[session.member.id, auth.getGeneration()].join(':')} />
    : <p>운영 권한이 필요합니다.</p>
}
function RetryList() {
  const [rows, setRows] = useState<Failure[]>([])
  const [reason, setReason] = useState('')
  const [message, setMessage] = useState('')
  const [revision, reload] = useState(0)
  const clear = () => { setRows([]); setReason(''); reload(n => n + 1) }
  const command = useInquiryCommand(() => { clear(); setMessage('알림 재처리를 요청했습니다.') }, clear)
  useEffect(() => {
    const controller = new AbortController()
    void requestJson('/api/admin/system/notifications/failed', { signal: controller.signal, cache: 'no-store' }).then(result => {
      if (controller.signal.aborted) return
      if (result.kind === 'success' && Array.isArray(result.data) && result.data.every(failure)) setRows(result.data)
      else { setRows([]); setReason(''); setMessage('실패한 알림을 조회할 수 없습니다.') }
    })
    return () => controller.abort()
  }, [revision])
  return <section><h2>실패한 알림 재처리</h2>
    <p role="status">{command.error || message || (rows.length === 0 ? '표시할 실패 알림이 없습니다.' : '')}</p>
    {rows.length > 0 && <label>재처리 사유<input maxLength={500} value={reason} disabled={!!command.pending}
      onChange={e => setReason(e.target.value)} /></label>}
    <ul>{rows.map(row => <li key={row.job} style={{ overflowWrap: 'anywhere' }}>실패 작업 {row.job}{' '}
      <button disabled={!!command.pending || !reason.trim()} onClick={() => void command.send({
        path: '/api/admin/system/notifications/' + row.event + '/retry',
        data: { key: crypto.randomUUID(), failedJob: row.job, reason: reason.trim() },
      })}>알림 재처리</button></li>)}</ul>
    {command.pending && !command.busy && <button onClick={() => command.pending && void command.send(command.pending)}>같은 요청 다시 확인</button>}
  </section>
}
