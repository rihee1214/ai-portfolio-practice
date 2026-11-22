import { useSyncExternalStore } from 'react'
import { matchPath, NavLink, Outlet, useLocation } from 'react-router'
import { useAuthStore } from './AuthContext'
import { AuthEntryLinks } from './AuthEntryActions'

const authEntryRoutes = ['/login', '/signup', '/verify-email', '/password/*']
const memberLinks = [
  { to: '/account', label: '내 계정' },
  { to: '/account/profile', label: '프로필' },
  { to: '/account/addresses', label: '배송지' },
  { to: '/account/wishlist', label: '찜한 상품' },
  { to: '/account/coupons', label: '내 쿠폰' },
  { to: '/account/orders', label: '주문·결제 이력' },
]

export default function AuthLayout() {
  const { pathname } = useLocation()
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const ready = session.checked && !session.busy && !session.error
  const showEntryMenu = ready && !session.member && authEntryRoutes.some(path => matchPath(path, pathname))
  const showMemberMenu = ready && session.member
  return <section className="auth-shell">
    {showEntryMenu ? <nav className="member-menu" aria-label="회원 메뉴">
      <AuthEntryLinks />
      <NavLink className="navigation-link" to="/verify-email">이메일 확인</NavLink>
    </nav> : showMemberMenu && <nav className="member-menu" aria-label="회원 메뉴">
      {memberLinks.map(({ to, label }) => <NavLink className="navigation-link" key={to} to={to} end={to === '/account'}>{label}</NavLink>)}
    </nav>}
    <Outlet />
  </section>
}
