import { Link } from 'react-router'
import { PublicPosts, SiteGuide } from '../site/SitePages'
import HealthPanel from '../components/HealthPanel'
import ShopNamePreview from '../components/ShopNamePreview'
import { HomeProducts } from './BrowsePages'
export default function HomePage() {
  return <><HealthPanel /><section className="hero" aria-labelledby="hero-title">
    <p className="eyebrow">작은 매대, 다양한 취향</p><h1 id="hero-title">일상에 필요한 것들이 <br />모이는 곳.</h1>
    <p className="hero-copy">각자의 취향을 담은 매대를 만나보세요.</p>
    <Link className="text-link" to="/products">상품 둘러보기 ↘</Link>
    <div className="hero-note"><span>LOCAL FINDS</span><strong>작은 발견이<br />좋은 일상으로.</strong><span>MOA MARKET / 01</span></div>
  </section><PublicPosts kind="BANNER"/><HomeProducts /><SiteGuide/><ShopNamePreview /></>
}
