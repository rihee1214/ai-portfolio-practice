import { Link, useLocation } from 'react-router'

export function NotFoundPage() {
  const { pathname } = useLocation()
  return (
    <section className="not-found">
      <p className="eyebrow">404 · 화면을 찾을 수 없어요</p>
      <h1>아직 없는 길이에요.</h1>
      <p>주소를 확인하거나 홈에서 다시 시작해 주세요.</p>
      <p className="unknown-path">요청한 경로: <code>{pathname}</code></p>
      <Link className="solid-link" to="/">홈으로 돌아가기</Link>
    </section>
  )
}
