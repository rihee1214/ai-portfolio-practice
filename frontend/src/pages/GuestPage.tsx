import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { GuestStore } from '../guest/GuestStore'
import { requestJson } from '../api/http'

export function GuestPage() {
  const [store] = useState(() => new GuestStore())
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [name, setName] = useState(''), [phone, setPhone] = useState(''), [code, setCode] = useState('')
  const [preparation, setPreparation] = useState<'loading' | 'ready' | 'error'>('loading')
  const [preparationAttempt, retryPreparation] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void (async () => {
      // Complete the checkout's Set-Cookie before asking that same session to persist its first CSRF token.
      const known = await store.load()
      if (!active) return
      if (!known) { setPreparation('error'); return }
      const result = await requestJson('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]) })
      if (!active) return
      const token = result.kind === 'success' && result.status === 200 ? result.data as { headerName?: string; token?: string } | null : null
      setPreparation(token?.headerName === 'X-CSRF-TOKEN' && typeof token.token === 'string' && token.token.length > 0 ? 'ready' : 'error')
    })()
    const timer = setInterval(store.expire, 1000)
    return () => { active = false; controller.abort(); clearInterval(timer); store.dispose() }
  }, [store, preparationAttempt])
  if (preparation !== 'ready') return <section><h1>방문자 구매 인증</h1>
    <p role={preparation === 'error' ? 'alert' : 'status'}>{preparation === 'error' ? '구매 인증을 위한 서버 세션을 준비하지 못했습니다. 상태를 다시 확인해 주세요.' : '구매 인증을 위한 서버 세션을 준비하고 있습니다.'}</p>
    {preparation === 'error' && <button onClick={() => { setPreparation('loading'); retryPreparation(value => value + 1) }}>서버 준비 상태 다시 확인</button>}
  </section>
  return <section><h1>방문자 구매 인증</h1>
    <p>로컬 모의 인증입니다. 실제 문자나 실명 확인은 하지 않습니다. 가상의 이름과 전화번호만 입력해 주세요.</p>
    <p>구매자 정보입니다. 배송받는 사람의 정보는 이후 주문 단계에서 입력합니다. 주문·결제·이전 주문 조회 권한은 아직 생성되지 않습니다.</p>
    {view.error && <p role="alert">{view.error}</p>}
    {view.busy && <p role="status">인증 요청을 처리하고 있습니다.</p>}
    {view.state === 'INPUT' && <form onSubmit={event => { event.preventDefault(); void store.issue(name, phone) }}>
      <p><label>구매자 이름 <input value={name} maxLength={80} autoComplete="off" required disabled={view.busy} onChange={event => setName(event.target.value)} /></label></p>
      <p><label>전화번호 <input value={phone} type="tel" autoComplete="off" required disabled={view.busy} onChange={event => setPhone(event.target.value)} /></label></p>
      <button disabled={view.busy}>모의 인증번호 요청</button>
    </form>}
    {view.state === 'SENT' && <form onSubmit={event => { event.preventDefault(); const submitted = code; setCode(''); void store.verify(submitted) }}>
      <p>{view.maskedPhone}로 모의 인증을 요청했습니다. 인증번호 유효시간은 요청 후 3분입니다.</p>
      <p><label>인증번호 <input value={code} inputMode="numeric" autoComplete="off" pattern="[0-9]{6}" maxLength={6} required disabled={view.busy} onChange={event => setCode(event.target.value)} /></label></p>
      <button disabled={view.busy}>인증 확인</button>
    </form>}
    {view.state === 'VERIFIED' && <p role="status">{view.maskedPhone} 구매 인증을 확인했습니다. 이 결과는 현재 구매 세션에서만 유효합니다.</p>}
    {view.state === 'VERIFIED' && <section aria-label="주문 묶음 선택"><h2>주문 묶음 선택</h2><p>새 묶음은 같은 번호라도 이전 주문과 합치지 않습니다. 기존 묶음은 먼저 조회 인증한 뒤 같은 번호로 현재 구매 인증을 완료해야 합니다. 번호가 다르면 새 묶음을 선택해 주세요.</p><p><Link to="/guest/orders/login">기존 묶음 조회 인증</Link></p><button disabled={view.busy} onClick={() => void store.selectLink('NEW')}>새 묶음 선택</button><button disabled={view.busy} onClick={() => void store.selectLink('EXISTING')}>기존 묶음 연결</button><button disabled={view.busy} onClick={() => void store.load()}>서버 준비 상태 다시 확인</button><p>현재 선택: {!view.linkKnown ? '서버 확인 필요' : view.linkMode === 'NEW' ? '새 묶음' : '기존 묶음'}</p>{view.linkMode === 'EXISTING' && <p role="status">{view.linkReady ? '기존 묶음 연결 준비 완료. 기존 비밀번호를 유지합니다. 아직 주문은 생성되지 않았습니다.' : '기존 묶음 연결 자격을 다시 확인해 주세요.'}</p>}</section>}
    {view.state === 'VERIFIED' && view.linkMode === 'NEW' && view.linkKnown && (view.passwordReady ? <p role="status">조회 비밀번호 준비 완료. 아직 주문은 생성되지 않았습니다.</p> : <GuestPasswordForm key={view.attempt + view.linkRevision} store={store} busy={view.busy} />)}
    {view.state !== 'INPUT' && <button disabled={view.busy} onClick={() => { setCode(''); setName(''); setPhone(''); void store.reset() }}>정보 변경·새 인증 시작</button>}
    <p>재요청은 60초 뒤 가능합니다. 번호별 15분에 3회, 실패 5회까지 허용합니다. 인증을 마치지 못하면 새 인증을 시작해 주세요.</p>
    <p><Link to="/checkout">주문서 준비로 돌아가기</Link></p>
    <p><Link to="/guest/orders/login">비회원 주문 조회</Link></p>
  </section>
}
function GuestPasswordForm({ store, busy }: { store: GuestStore; busy: boolean }) {
  const [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState('')
  return <form onSubmit={event => { event.preventDefault(); const first = password, second = confirmation; setPassword(''); setConfirmation(''); void store.preparePassword(first, second) }}>
    <h2>조회 비밀번호 설정</h2><p>12~128자, 공백과 붙여넣기를 사용할 수 있습니다. 주문번호·전화번호와 함께 조회에 사용합니다.</p>
    <p><label>조회 비밀번호 <input type="password" autoComplete="new-password" required value={password} disabled={busy} onChange={event => setPassword(event.target.value)} /></label></p>
    <p><label>조회 비밀번호 확인 <input type="password" autoComplete="new-password" required value={confirmation} disabled={busy} onChange={event => setConfirmation(event.target.value)} /></label></p>
    <button disabled={busy}>조회 비밀번호 준비</button>
  </form>
}
