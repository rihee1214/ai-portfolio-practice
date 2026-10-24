import { useEffect, useRef, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link } from 'react-router'
import { useAuthStore } from '../auth/AuthContext'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

export function PasswordPage({ mode }: { mode: 'request' | 'reset' }) {
  const store = useAuthStore()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const form = useRef<HTMLFormElement>(null)
  const active = useRef(true)
  const [validation, setValidation] = useState('')
  const [done, setDone] = useState(false)
  const heading = mode === 'request' ? '복구 코드 요청' : '비밀번호 재설정'
  useEffect(() => { active.current = true; store.invalidate(); return () => { active.current = false; store.stop() } }, [store, mode])
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const fields: Record<string, string> = mode === 'request' ? { email: String(data.get('email') ?? '') }
      : { token: String(data.get('token') ?? '').trim(), password: String(data.get('password') ?? '') }
    if (mode === 'reset' && ([...fields.password].length < 12 || [...fields.password].length > 128)) {
      setValidation('비밀번호는 문자 기준 12~128자로 입력해 주세요.'); return
    }
    setValidation('')
    const ok = await store.mutate('password/' + mode as 'password/request' | 'password/reset', fields)
    if (!active.current) return
    form.current?.reset()
    if (ok) setDone(true)
  }
  return <div className="auth-card"><h1>{heading}</h1>
    <p>외부 이메일은 실제 발송하지 않습니다. 로컬 테스트 메일함의 복구 코드는 30분 이내 한 번만 사용할 수 있으며 가장 최근 요청만 유효합니다.</p>
    {validation && <p role="alert">{validation}</p>}
    {state.error && <ApiErrorNotice error={state.error} />}
    {state.notice && <p role="status">{state.notice}</p>}
    {!done && <form ref={form} onSubmit={event => { void submit(event) }} aria-busy={state.busy}>
      {mode === 'request' ? <><label htmlFor="recovery-email">이메일</label><input id="recovery-email" name="email" type="email" required maxLength={254} autoComplete="email" disabled={state.busy} /></>
        : <><label htmlFor="recovery-token">복구 코드</label><input id="recovery-token" name="token" required maxLength={43} autoComplete="off" spellCheck={false} disabled={state.busy} />
          <label htmlFor="recovery-password">새 비밀번호</label><input id="recovery-password" name="password" type="password" required maxLength={256} autoComplete="new-password" disabled={state.busy} />
          <p>비밀번호는 문자 기준 12~128자로 입력해 주세요.</p></>}
      <button disabled={state.busy}>{heading}</button>
    </form>}
    {mode === 'request' && <p><Link to="/password/reset">복구 코드 입력하기</Link></p>}
    <Link to="/login">로그인하기</Link>
  </div>
}
