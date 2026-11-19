import { useEffect, useRef, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { AuthEntryActions } from '../auth/AuthEntryActions'
import { useAuthStore } from '../auth/AuthContext'
import type { ApiErrorView } from '../api/apiError'
import './account.css'

export function AuthForm({ mode }: { mode: 'signup' | 'login' | 'verify-email' }) {
  const store = useAuthStore()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const navigate = useNavigate()
  const formRef = useRef<HTMLFormElement>(null)
  const [validation, setValidation] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const heading = { signup: '회원가입', login: '로그인', 'verify-email': '이메일 확인' }[mode]
  useEffect(() => { store.invalidate(); return () => { store.stop() } }, [store, mode])
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const fields: Record<string, string> = mode === 'verify-email' ? { token: String(data.get('token') ?? '') }
      : { email: String(data.get('email') ?? ''), password: String(data.get('password') ?? '') }
    if (mode === 'signup' && ('password' in fields) && ([...fields.password].length < 12 || [...fields.password].length > 128)) {
      setValidation('비밀번호는 문자 기준 12~128자로 입력해 주세요.')
      return
    }
    setValidation('')
    const ok = await store.mutate(mode, fields)
    // Clear credentials even if the server/network rejects the request.
    if (formRef.current) {
      const sensitive = formRef.current.elements.namedItem(mode === 'verify-email' ? 'token' : 'password')
      if (sensitive instanceof HTMLInputElement) sensitive.value = ''
    }
    if (ok && mode === 'login') void navigate('/account')
    if (ok && mode !== 'login') setSubmitted(true)
  }
  return <div className="auth-card"><p className="eyebrow">모아마켓 회원 서비스</p><h1>{heading}</h1>
    {mode === 'signup' && <p id="signup-policy">영문(ASCII) 이메일을 사용해 주세요. 앞뒤 공백과 대소문자는 같은 주소로 처리합니다.
      비밀번호는 문자 기준 12~128자이며 공백을 포함할 수 있습니다.</p>}
    {mode === 'verify-email' && <p id="verify-policy">로컬 테스트 메일함의 확인 코드를 붙여넣어 주세요. 30분 이내 한 번만 사용할 수 있습니다.
      현재 외부 이메일은 실제 발송하지 않습니다.</p>}
    {mode === 'signup' && <p>개발용 가입입니다. 실제 이메일은 발송하지 않으며 확인 코드는 로컬 테스트 도구에서 확인합니다.</p>}
    {validation && <p role="alert">{validation}</p>}
    {state.error && <ApiErrorNotice error={state.error} />}
    {state.notice && <p role="status">{state.notice}</p>}
    {!submitted && <form ref={formRef} onSubmit={event => { void submit(event) }} aria-busy={state.busy}>
      {mode !== 'verify-email' ? <>
        <label htmlFor="auth-email">이메일</label><input id="auth-email" name="email" type="email" required maxLength={254} autoComplete="username" disabled={state.busy} />
        <label htmlFor="auth-password">비밀번호</label><input id="auth-password" name="password" type="password" required maxLength={256}
          autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} disabled={state.busy} />
      </> : <><label htmlFor="auth-token">이메일 확인 코드</label><input id="auth-token" name="token" required maxLength={43}
        autoComplete="off" spellCheck={false} disabled={state.busy} /></>}
      <button type="submit" disabled={state.busy}>{state.busy ? '확인 중…' : heading}</button>
    </form>}
    {submitted && <Link className="solid-link" to={mode === 'signup' ? '/verify-email' : '/login'}>
      {mode === 'signup' ? '이메일 확인하기' : '로그인하기'}</Link>}
    {state.error && <button type="button" disabled={state.busy} onClick={() => { void store.refresh() }}>세션 상태 다시 확인</button>}
    {mode === 'login' && <p><Link to="/password/request">비밀번호를 잊으셨나요?</Link></p>}
  </div>
}

export function AccountPage() {
  const store = useAuthStore()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [mutation, setMutation] = useState<{ generation: number; memberId: string; error?: ApiErrorView }>()
  useEffect(() => { store.invalidate(); return () => { store.stop() } }, [store])

  async function mutateAccount(action: 'logout' | 'withdraw', fields?: Record<string, string>) {
    const { busy, member } = store.getSnapshot()
    if (busy || !member) return
    const result = store.mutate(action, fields)
    const generation = store.getGeneration()
    setMutation({ generation, memberId: member.id })
    if (await result || store.getGeneration() !== generation) return
    const { error } = store.getSnapshot()
    if (error) {
      setMutation({ generation, memberId: member.id, error })
      await store.refresh()
    }
  }

  const checking = state.busy || !state.checked
  const currentMutationFailed = state.error && mutation?.generation === store.getGeneration()
  const mutationError = currentMutationFailed ? state.error
    : mutation?.error && (!state.member || state.member.id === mutation.memberId) ? mutation.error : null
  const sessionError = currentMutationFailed ? null : state.error
  const mutationNotice = mutationError && <ApiErrorNotice error={mutationError} />

  if (checking || state.error || !state.member) {
    return <div className="auth-card account-card">
      <h1>내 계정</h1>
      {mutationNotice}
      {checking ? <p role="status">로그인 상태를 확인하고 있습니다.</p>
        : sessionError ? <>
          <ApiErrorNotice error={sessionError} />
          <button className="account-retry" type="button" onClick={() => { void store.refresh() }}>다시 시도</button>
        </> : !currentMutationFailed && <>
          <p>로그인이 필요합니다.</p>
          {state.notice && <p role="status">{state.notice}</p>}
          <AuthEntryActions />
        </>}
    </div>
  }

  return <div className="auth-card"><h1>내 계정</h1>
    {mutationNotice}
    <p>로그인되었습니다.</p>
    <dl><dt>회원 ID</dt><dd>{state.member.id}</dd>
      <dt>이용 자격</dt><dd>{state.member.roles.map(role => ({ BUYER: '구매자', SELLER: '판매자', ADMIN: '운영자' })[role]).join(', ')}</dd></dl>
    <p><Link to="/account/reports">내 신고 상태</Link></p>
    <p><Link to="/account/notifications">내 알림</Link></p>
    <button type="button" onClick={() => { void mutateAccount('logout') }}>로그아웃</button>
    {state.member.roles.includes('ADMIN') ? <p>운영자 계정의 탈퇴는 권한 인계 절차가 마련된 후 지원합니다.</p> :
      <form onSubmit={event => {
        event.preventDefault()
        const form = event.currentTarget
        const data = new FormData(form)
        const password = String(data.get('password') ?? '')
        form.reset()
        void mutateAccount('withdraw', { password, confirmation: 'WITHDRAW' })
      }}>
        <h2>회원 탈퇴</h2>
        <p>계정을 복구할 수 없으며 로그인 정보·프로필·배송지를 삭제합니다. 거래 이력은 별도 보존 대상입니다.</p>
        <label htmlFor="withdraw-password">탈퇴 확인 비밀번호</label>
        <input id="withdraw-password" name="password" type="password" required maxLength={256} autoComplete="current-password" />
        <label><input type="checkbox" required />탈퇴 후 계정을 복구할 수 없음을 확인했습니다.</label>
        <button type="submit">회원 탈퇴</button>
      </form>}
    <p><Link to="/account/profile">프로필 변경</Link> · <Link to="/account/addresses">배송지 관리</Link></p>
    <p><Link to="/account/seller-application">판매 신청·이력</Link></p>
  </div>
}
