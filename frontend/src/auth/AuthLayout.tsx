import { Link, Outlet } from 'react-router'
export default function AuthLayout() {
  return <section className="auth-shell"><nav aria-label="회원 메뉴"><Link to="/login">로그인</Link><Link to="/signup">회원가입</Link>
    <Link to="/verify-email">이메일 확인</Link><Link to="/account">내 계정</Link><Link to="/account/profile">프로필</Link><Link to="/account/addresses">배송지</Link><Link to="/account/wishlist">찜한 상품</Link></nav><Outlet /></section>
}
