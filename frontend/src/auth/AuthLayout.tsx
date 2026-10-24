import { useEffect, useState } from 'react'
import { Link, Outlet } from 'react-router'
import { AuthContext } from './AuthContext'
import { AuthStore } from './AuthStore'

export default function AuthLayout() {
  const [store] = useState(() => new AuthStore())
  useEffect(() => {
    store.invalidate()
    const onFocus = () => { void store.refresh() }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') store.stop()
      else store.invalidate()
    }
    const onPageShow = () => store.invalidate()
    const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('marketplace-auth')
    if (channel) channel.onmessage = event => {
      if (event.data?.source !== store.source) store.invalidate()
    }
    window.addEventListener('focus', onFocus)
    window.addEventListener('pageshow', onPageShow)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      store.stop()
      channel?.close()
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('pageshow', onPageShow)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [store])
  return <AuthContext value={store}><section className="auth-shell">
    <nav aria-label="회원 메뉴"><Link to="/login">로그인</Link><Link to="/signup">회원가입</Link>
      <Link to="/verify-email">이메일 확인</Link><Link to="/account">내 계정</Link></nav>
    <Outlet />
  </section></AuthContext>
}
