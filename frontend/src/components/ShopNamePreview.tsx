import { useState } from 'react'
export default function ShopNamePreview() {
 const [shopName, setShopName] = useState('나의 작은 매대')
 const previewName = shopName.trim() || '이름을 기다리는 매대'
 return (
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
 )
}

