import { useEffect, useState, useSyncExternalStore } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore, productValid } from '../catalog/CatalogStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { ProductQuestions } from './ProductQuestions'

export function SellerQuestions() {
  const auth = useAuthStore()
  const [products] = useState(() => new CatalogStore(auth, '/api/seller/products', productValid))
  const catalog = useSyncExternalStore(products.subscribe, products.getSnapshot)
  const [selected, select] = useState('')
  useEffect(() => products.connect(), [products])
  const current = catalog.items?.find(product => product.slug === selected)
  return <section><h1>내 상품 문의</h1>
    {catalog.error && <ApiErrorNotice error={catalog.error} />}
    <ul>{catalog.items?.map(product => <li key={product.id}>
      <button onClick={() => select(product.slug)}>{product.title}</button>
    </li>)}</ul>
    <nav aria-label="문의 대상 상품 페이지">
      <button disabled={catalog.busy || !catalog.page} onClick={() => void products.load(catalog.page - 1)}>이전 상품</button>
      <button disabled={catalog.busy || !catalog.hasMore} onClick={() => void products.load(catalog.page + 1)}>다음 상품</button>
    </nav>
    {current && <ProductQuestions slug={current.slug} defaultOpen />}
  </section>
}
