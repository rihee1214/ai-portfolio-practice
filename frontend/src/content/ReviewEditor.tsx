import {useEffect, useRef, useState} from 'react'
import {requestJson} from '../api/http'
import {ReviewImageEditor} from './ReviewImageEditor'

type Entry = {version: number; deleted: boolean; rating: number; body: string}
type State = {eligible: boolean; existing: Entry | null}
type Action = 'CREATE' | 'REWRITE' | 'EDIT' | 'DELETE'
type Command = {key: string; action: Action; expectedVersion: number; rating?: number; body?: string}

export function ReviewEditor({number, item, confirmationRevision = 0}: {number: string; item: string; confirmationRevision?: number}) {
  const path = '/api/orders/' + encodeURIComponent(number) + '/items/' + encodeURIComponent(item) + '/review'
  const [view, setView] = useState<State | null>(null)
  const [body, setBody] = useState('')
  const [rating, setRating] = useState(5)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<Command | null>(null)
  const [revision, refresh] = useState(0)
  const lifetime = useRef(true)

  useEffect(() => {
    lifetime.current = true
    const controller = new AbortController()
    void requestJson(path + '-eligibility', {signal: controller.signal}).then(result => {
      if (controller.signal.aborted) return
      if (result.kind !== 'success' || !result.data || typeof result.data !== 'object' ||
          !('eligible' in result.data) || typeof result.data.eligible !== 'boolean' || !('existing' in result.data)) {
        setMessage('후기 작성 상태를 조회하지 못했습니다.')
        return
      }
      const state = result.data as State
      setView(state)
      setBody(state.existing?.body ?? '')
      setRating(state.existing?.rating ?? 5)
    })
    return () => { controller.abort(); lifetime.current = false }
  }, [path, revision, confirmationRevision, lifetime])

  async function send(command: Command) {
    setBusy(true)
    setPending(command)
    const csrf = await requestJson('/api/auth/csrf')
    if (!lifetime.current) return
    if (csrf.kind !== 'success' || !csrf.data || typeof csrf.data !== 'object' ||
        !('token' in csrf.data) || typeof csrf.data.token !== 'string') {
      setMessage('로그인 상태를 확인하고 다시 시도해 주세요.')
      setBusy(false)
      return
    }
    const result = await requestJson(path, {method: 'POST', headers: {'Content-Type':'application/json',
      'X-CSRF-TOKEN':csrf.data.token}, body: JSON.stringify(command)})
    if (!lifetime.current) return
    setBusy(false)
    if (result.kind === 'success' || (result.kind === 'http' && result.status < 500)) {
      setPending(null)
      setView(null)
      setMessage(result.kind === 'success' ? '저장했습니다.' : '현재 권한과 후기 상태를 다시 확인해 주세요.')
      refresh(n => n + 1)
    } else setMessage('저장 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요.')
  }
  const existing = view?.existing
  const action: Action = !existing ? 'CREATE' : existing.deleted ? 'REWRITE' : 'EDIT'
  const allowed = view && (view.eligible || (existing && !existing.deleted))
  function submit(next: Action) {
    if (busy || pending) return
    void send({key: crypto.randomUUID(), action: next, expectedVersion: existing?.version ?? 0,
      ...(next === 'DELETE' ? {} : {body, rating})})
  }
  return <section aria-label="구매 후기"><h3>구매 후기</h3>
    {allowed ? <fieldset disabled={busy || !!pending}>
      <label>평점 <select value={rating} onChange={event => setRating(Number(event.target.value))}>
        {[1,2,3,4,5].map(value => <option key={value} value={value}>{value}점</option>)}</select></label>
      <label>후기 본문 <textarea value={body} onChange={event => setBody(event.target.value)}/></label>
      <button disabled={!body.trim() || [...body.trim()].length > 2000} onClick={() => submit(action)}>
        {action === 'EDIT' ? '후기 수정' : '후기 등록'}</button>
      {action === 'EDIT' && <button onClick={() => submit('DELETE')}>후기 삭제</button>}
    </fieldset> : view && <p>구매 확정하고 환불되지 않은 수량이 있어야 작성할 수 있습니다.</p>}
    {existing && !existing.deleted && <ReviewImageEditor key={existing.version} path={path} version={existing.version}
      saved={() => refresh(n => n+1)}/>}
    {message && <p role="status">{message}</p>}
    {pending && !busy && <button onClick={() => void send(pending)}>같은 요청으로 결과 확인</button>}
    {!view && !pending && message && <button onClick={() => refresh(n => n + 1)}>후기 상태 다시 조회</button>}
  </section>
}
