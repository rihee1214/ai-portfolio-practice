import { useEffect, useRef } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { workspaces } from '../navigation'

export function SiteLayout() {
  const { pathname } = useLocation()
  const mainRef = useRef<HTMLElement>(null)
  const previousPath = useRef(pathname)

  useEffect(() => {
    document.title = `${mainRef.current?.querySelector('h1')?.textContent ?? '화면 안내'} · 모아마켓`
    if (previousPath.current !== pathname) {
      mainRef.current?.focus()
      window.scrollTo(0, 0)
      previousPath.current = pathname
    }
  }, [pathname])

  return (
    <>
      <a className="skip-link" href="#main-content">본문으로 건너뛰기</a>
      <header className="site-header">
        <Link className="brand" to="/" aria-label="모아마켓 홈">모아<span>마켓</span><span className="brand-dot">.</span></Link>
        <nav className="primary-nav" aria-label="주요 화면">
          <NavLink to="/" end>둘러보기</NavLink><NavLink to="/products">상품</NavLink>
          {workspaces.map((workspace) => <NavLink key={workspace.path} to={workspace.path}>{workspace.label}</NavLink>)}
        </nav>
        <nav className="account-nav" aria-label="회원 서비스">
          <Link className="account-link" to="/cart">장바구니</Link><Link className="account-link" to="/login">로그인</Link>
          <Link className="account-link" to="/signup">회원가입</Link>
          <Link className="account-link" to="/account">내 계정</Link>
        </nav>
      </header>
      <main id="main-content" ref={mainRef} tabIndex={-1}><Outlet /></main>
      <footer>
        <Link className="brand" to="/">모아마켓.</Link>
        <p>포트폴리오 개발용 화면 · 실제 구매·판매·운영 기능 준비 중</p>
      </footer>
    </>
  )
}
