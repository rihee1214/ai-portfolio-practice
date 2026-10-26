import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { GuestRecoveryStore } from '../guest/GuestRecoveryStore'
export function GuestRecoveryPage({ manage = false }: { manage?: boolean }) {
  const [store] = useState(() => new GuestRecoveryStore())
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [order, setOrder] = useState(''), [phone, setPhone] = useState(''), [recovery, setRecovery] = useState('')
  const [code, setCode] = useState(''), [current, setCurrent] = useState(''), [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState('')
  useEffect(() => () => store.dispose(), [store])
  function submitPassword(change: boolean) {
    const old = current, next = password, repeated = confirmation
    setCurrent(''); setPassword(''); setConfirmation('')
    if (manage) void store.manage(old, next, repeated, change)
    else void store.reset(next, repeated)
  }
  return <section><h1>{manage ? '비회원 조회 자격 관리' : '비회원 조회 비밀번호 복구'}</h1>
    <p>로컬 모의 인증과 가상 주문의 조회 자격을 확인하는 화면입니다. 실제 문자는 보내지 않습니다.</p>
    <p>전화 인증만으로 과거 주문을 복구할 수 없습니다. 복구 코드도 잃었다면 지원 문의가 필요합니다. 현재는 가상 데이터 확인 절차 안내만 제공하며 실제 지원 접수·자동 복구는 하지 않습니다.</p>
    {view.error && <p role="alert">{view.error}</p>}{view.busy && <p role="status">처리 중입니다.</p>}
    {view.step === 'COMPLETE' ? <><p role="status">처리 완료. 비밀번호를 변경했다면 모든 이전 조회 세션이 종료됩니다. 새 비밀번호로 로그인해 주세요.</p>
      {view.recoveryCode && <><p>새 복구 코드는 이 응답에서만 표시합니다. 안전한 곳에 보관하세요. 이전 코드는 사용할 수 없습니다.</p><label>새 복구 코드 <input readOnly value={view.recoveryCode} autoComplete="off" /></label><button onClick={store.hide}>복구 코드 숨기기</button></>}
      <p><Link to="/guest/orders/login">조회 로그인으로 이동</Link></p></>
      : manage ? <form onSubmit={event => { event.preventDefault(); submitPassword(true) }}>
        <p>조회 로그인과 현재 비밀번호 확인이 필요합니다. 재발급하면 이전 복구 코드와 진행 중인 재설정 권한은 무효가 됩니다.</p>
        <p><label>현재 조회 비밀번호 <input type="password" required value={current} disabled={view.busy} autoComplete="off" onChange={event => setCurrent(event.target.value)} /></label></p>
        <p><label>새 조회 비밀번호 <input type="password" value={password} disabled={view.busy} autoComplete="new-password" onChange={event => setPassword(event.target.value)} /></label></p>
        <p><label>새 조회 비밀번호 확인 <input type="password" value={confirmation} disabled={view.busy} autoComplete="new-password" onChange={event => setConfirmation(event.target.value)} /></label></p>
        <button disabled={view.busy}>비밀번호 변경</button> <button type="button" disabled={view.busy || !current} onClick={() => submitPassword(false)}>복구 코드 재발급</button>
      </form> : view.step === 'INPUT' ? <form onSubmit={event => { event.preventDefault(); const secret = recovery; setRecovery(''); void store.start(order, phone, secret) }}>
        <p><label>주문번호 <input required value={order} maxLength={64} disabled={view.busy} onChange={event => setOrder(event.target.value)} /></label></p>
        <p><label>등록 전화번호 <input type="tel" required value={phone} disabled={view.busy} onChange={event => setPhone(event.target.value)} /></label></p>
        <p><label>복구 코드 <input type="password" required value={recovery} autoComplete="off" disabled={view.busy} onChange={event => setRecovery(event.target.value)} /></label></p>
        <button disabled={view.busy}>복구용 모의 인증 요청</button>
      </form> : view.step === 'SENT' ? <form onSubmit={event => { event.preventDefault(); const secret = code; setCode(''); void store.verify(secret) }}>
        <p>인증번호는 3분 동안 유효합니다. 실패하거나 응답을 잃으면 60초 뒤 새로 시작하세요.</p><label>인증번호 <input required pattern="[0-9]{6}" inputMode="numeric" value={code} disabled={view.busy} onChange={event => setCode(event.target.value)} /></label><button disabled={view.busy}>복구 인증 확인</button>
      </form> : <form onSubmit={event => { event.preventDefault(); submitPassword(true) }}>
        <p>5분 안에 새 비밀번호를 정하세요. 12~128자이며 공백·붙여넣기를 허용합니다.</p>
        <p><label>새 조회 비밀번호 <input type="password" required value={password} autoComplete="new-password" disabled={view.busy} onChange={event => setPassword(event.target.value)} /></label></p>
        <p><label>새 조회 비밀번호 확인 <input type="password" required value={confirmation} autoComplete="new-password" disabled={view.busy} onChange={event => setConfirmation(event.target.value)} /></label></p>
        <button disabled={view.busy}>조회 비밀번호 재설정</button>
      </form>}
    <p><Link to="/guest/orders/login">조회 로그인</Link></p><p><a href="/guest/recovery">복구를 처음부터 시작</a></p>
  </section>
}
