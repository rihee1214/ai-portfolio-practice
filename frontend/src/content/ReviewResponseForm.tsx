import {useEffect, useRef, useState} from 'react'
import {requestJson} from '../api/http'

export type ResponseRow = {id: string; kind: 'COMMENT' | 'SELLER_REPLY'; body: string; version: number; mine: boolean}
export function ReviewResponseForm({id, edition, kind, existing, saved}: {
  id: string; edition: number; kind: ResponseRow['kind']; existing?: ResponseRow; saved: () => void
}) {
  const [body, setBody] = useState(existing?.body ?? '')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const label = kind === 'COMMENT' ? '댓글' : '판매자 답글'
  async function send(payload: string) {
    setBusy(true)
    setPending(payload)
    const csrf = await requestJson('/api/auth/csrf')
    if (!active.current) return
    if (csrf.kind !== 'success' || !csrf.data || typeof csrf.data !== 'object' || !('token' in csrf.data) ||
      typeof csrf.data.token !== 'string') {
      setBusy(false); setError('로그인 상태를 확인해 주세요.'); return
    }
    const result = await requestJson('/api/reviews/' + id + '/responses', {method:'POST',
      headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:payload})
    if (!active.current) return
    setBusy(false)
    if (result.kind === 'success') {
      setPending(null); setBusy(!!existing); setBody(''); setError(''); saved()
    }
    else if (result.kind === 'http' && result.status < 500) {
      setPending(null); setError('작성 권한과 최신 후기 상태를 확인해 주세요.'); saved()
    } else setError('저장 결과를 다시 확인해 주세요.')
  }
  function submit(remove: boolean) {
    void send(JSON.stringify({key:crypto.randomUUID(),kind,edition,expectedVersion:existing?.version ?? 0,
      responseId:existing?.id ?? null,action:remove ? 'DELETE' : existing ? 'EDIT' : 'CREATE',body:remove ? null : body}))
  }
  return <div role="group" aria-label={label+(existing ? ' 수정' : ' 작성')}><label>{label} 본문 <textarea value={body} disabled={busy || !!pending}
    onChange={event => setBody(event.target.value)}/></label>
    <button disabled={busy || !!pending || !body.trim() || [...body.trim()].length > (kind === 'COMMENT' ? 500 : 1000)}
      onClick={() => submit(false)}>{label} {existing ? '수정' : '등록'}</button>
    {existing && <button disabled={busy || !!pending} onClick={() => submit(true)}>{label} 삭제</button>}
    {pending && !busy && <button onClick={() => void send(pending)}>같은 요청으로 결과 확인</button>}
    {error && <p role="alert">{error}</p>}
  </div>
}
