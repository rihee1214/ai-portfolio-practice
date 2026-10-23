import { useState } from 'react'
import HealthPanel from '../components/HealthPanel'

type SampleProduct = {
  id: string
  name: string
  seller: string
  category: string
  price: number
  symbol: string
  tone: string
}

const products: SampleProduct[] = [
  { id: 'cup', name: '아침을 여는 머그', seller: '흙과 온기', category: '주방', price: 18000, symbol: '☕', tone: 'sand' },
  { id: 'plant', name: '책상 위 작은 초록', seller: '초록의 자리', category: '생활', price: 12000, symbol: '🌿', tone: 'sage' },
  { id: 'basket', name: '차곡차곡 수납 바구니', seller: '느린 생활', category: '수납', price: 24000, symbol: '🧺', tone: 'rose' },
]

function ProductCard({ product }: { product: SampleProduct }) {
  return (
    <article className="product-card">
      <div className={`product-art ${product.tone}`} aria-hidden="true">{product.symbol}</div>
      <div className="product-info">
        <p className="seller">{product.seller} · {product.category}</p>
        <h3>{product.name}</h3>
        <p className="price">{product.price.toLocaleString('ko-KR')}원</p>
      </div>
    </article>
  )
}

export default function HomePage() {
  const [shopName, setShopName] = useState('나의 작은 매대')
  const previewName = shopName.trim() || '이름을 기다리는 매대'

  return (
    <>
        <HealthPanel />
        <section className="hero" aria-labelledby="hero-title">
          <p className="eyebrow">작은 매대, 다양한 취향</p>
          <h1 id="hero-title">일상에 필요한 것들이 <br />모이는 곳.</h1>
          <p className="hero-copy">각자의 취향을 담은 매대를 만나보세요.<br />함께 만들어 갈 마켓플레이스의 첫 번째 화면입니다.</p>
          <a className="text-link" href="#sample-products">샘플 상품 둘러보기 <span aria-hidden="true">↘</span></a>
          <div className="hero-note"><span>LOCAL FINDS</span><strong>작은 발견이<br />좋은 일상으로.</strong><span>MOA MARKET / 01</span></div>
        </section>

        <section id="sample-products" aria-labelledby="products-title">
          <div className="section-heading"><h2 id="products-title">매대에서 만나는 일상</h2><span>샘플 상품 3개</span></div>
          <p className="section-description">화면 연습용 데이터입니다. 현재 구매·장바구니 기능은 제공하지 않습니다.</p>
          <div className="product-grid">{products.map((product) => <ProductCard key={product.id} product={product} />)}</div>
        </section>

        <section className="preview-section" aria-labelledby="preview-title">
          <div>
            <p className="eyebrow">나만의 이름으로</p>
            <h2 id="preview-title">첫 매대를 상상해 보세요</h2>
            <p className="section-description">이름을 입력하면 옆의 미리보기가 바뀝니다.<br />입력은 저장되지 않으며 화면을 떠나거나 새로고침하면 초기화됩니다.</p>
            <label htmlFor="shop-name">매대 이름</label>
            <div className="input-row"><input id="shop-name" value={shopName} maxLength={30} onChange={(event) => setShopName(event.target.value)} aria-describedby="name-help" /><button type="button" onClick={() => setShopName('나의 작은 매대')}>초기화</button></div>
            <p id="name-help" className="field-help">최대 30자 · 공백만 입력하면 기본 안내가 표시됩니다.</p>
          </div>
          <div className="shop-preview"><span className="badge">매대 이름 미리보기</span><p className="preview-name" aria-live="polite">{previewName}</p><p>좋아하는 것들로 채울 작은 공간</p></div>
        </section>


    </>
  )
}

