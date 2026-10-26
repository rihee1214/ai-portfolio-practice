import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { GuestAccessStore } from '../guest/GuestAccessStore'
export function GuestOrdersPage() {
  const [store] = useState(() => new GuestAccessStore())
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [number, setNumber] = useState(''), [phone, setPhone] = useState(''), [password, setPassword] = useState('')
  useEffect(() => { void store.load(); const focus = () => { void store.load() }; window.addEventListener('focus', focus); return () => { window.removeEventListener('focus', focus); store.dispose() } }, [store])
  return <section><h1>비회원 주문 조회</h1><p>주문번호·등록한 전화번호·직접 정한 조회 비밀번호로 인증합니다. 같은 번호만으로 다른 주문 묶음이 연결되지 않습니다.</p>
    <p>현재는 로컬 가상 주문의 조회 자격 검증 단계입니다. 실제 결제·배송 상세는 아직 제공하지 않습니다.</p>
    {view.busy && <p role="status">조회 정보를 확인하고 있습니다.</p>}{view.error && <p role="alert">{view.error}</p>}
    {view.logoutPending && <><p>서버의 조회 세션 폐기를 확인하지 못했습니다. 로그아웃을 다시 시도해 주세요.</p><button disabled={view.busy} onClick={() => { void store.logout() }}>비회원 조회 로그아웃 다시 시도</button></>}
    {view.authenticated ? <><h2>연결된 주문</h2><ul>{view.orders.map(order => <li key={order}>{order}</li>)}</ul><button disabled={view.busy} onClick={() => { setNumber(''); setPhone(''); setPassword(''); void store.logout() }}>비회원 조회 로그아웃</button></>
      : !view.logoutPending && <form onSubmit={event => { event.preventDefault(); const secret = password; setPassword(''); void store.login(number, phone, secret).then(() => { if (store.getSnapshot().authenticated) { setNumber(''); setPhone('') } }) }}>
        <p><label>주문번호 <input value={number} required maxLength={64} disabled={view.busy} autoComplete="off" onChange={event => setNumber(event.target.value)} /></label></p>
        <p><label>등록 전화번호 <input type="tel" value={phone} required disabled={view.busy} autoComplete="off" onChange={event => setPhone(event.target.value)} /></label></p>
        <p><label>조회 비밀번호 <input type="password" value={password} required disabled={view.busy} autoComplete="off" onChange={event => setPassword(event.target.value)} /></label></p>
        <button disabled={view.busy}>주문 조회 인증</button>
      </form>}
    <p><Link to="/guest/security">비밀번호 변경·복구 코드 관리</Link></p><p><Link to="/guest/recovery">비밀번호를 잊으셨나요?</Link></p>
    <p><Link to="/checkout/guest">방문자 구매 인증</Link></p>
  </section>
}
