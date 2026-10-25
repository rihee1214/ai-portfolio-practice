import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CatalogStore, type Product } from '../catalog/CatalogStore'
import { skuValid, skuLabel, invalidOptionText, type Sku, type SkuOption } from '../catalog/Skus'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

export function SkuPanel({ product }: { product: Product }) {
  const auth = useAuthStore()
  const [store] = useState(() => new CatalogStore(auth, '/api/seller/products/' + product.id + '/skus', skuValid))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [selected, select] = useState<string | null>(null)
  useEffect(() => store.connect(), [store])
  const item = state.items?.find(row => row.id === selected) ?? null
  return <section aria-label="SKU 관리"><h2>옵션·가격·판매 상태</h2>
    <p>SKU는 실제 판매할 옵션 조합입니다. 옵션이 없으면 기본 SKU를 사용합니다. 기본 가격은 0원이며 처음에는 판매 중지 상태입니다. 재고 수량은 재고 관리에서 조정합니다. 구매 기능은 아직 제공하지 않습니다.</p>
    <p>옵션 SKU를 등록하면 기본 SKU가 판매 중지되고 다시 활성화할 수 없습니다. 등록한 옵션 조합과 SKU 코드는 변경·삭제할 수 없습니다. 잘못된 조합은 판매 중지 후 새로 등록하세요.</p>
    {state.busy && <p role="status">SKU 처리 중입니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">SKU {state.notice}</p>}
    <button disabled={state.busy} onClick={() => { select(null); void store.load() }}>SKU 다시 확인</button>
    {state.items && <><ul>{state.items.map(row => <li key={row.id}><button disabled={state.busy} onClick={() => select(row.id)}>{skuLabel(row)} · {row.price}원 · {row.active ? '판매 활성' : '판매 중지'}</button></li>)}</ul>
      <nav aria-label="SKU 목록 페이지"><button disabled={state.busy || !state.page} onClick={() => { select(null); void store.load(state.page - 1) }}>이전 SKU</button> <span>{state.page + 1} 페이지</span> <button disabled={state.busy || !state.hasMore || state.page >= 10000} onClick={() => { select(null); void store.load(state.page + 1) }}>다음 SKU</button></nav>
      <button disabled={state.busy || product.state === 'ARCHIVED'} onClick={() => select(null)}>새 옵션 SKU</button>
      <SkuForm key={item ? item.id + ':' + item.version : 'new'} item={item} disabled={state.busy || product.state === 'ARCHIVED'} save={fields => store.save(item, fields)} />
      {product.state === 'ARCHIVED' && <p>보관 상품의 SKU는 수정할 수 없습니다.</p>}
    </>}
  </section>
}
function SkuForm({ item, disabled, save }: { item: Sku | null; disabled: boolean; save: (fields: Record<string, unknown>) => void }) {
  const [error, setError] = useState('')
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const raw = String(data.get('price')); const price = Number(raw)
    if (!/^\d+$/.test(raw) || !Number.isSafeInteger(price) || price > 999999999) { setError('가격은 0~999999999원 정수로 입력해 주세요.'); return }
    const options: SkuOption[] = item ? item.options : []
    if (!item) {
      for (let i = 0; i < 3; i++) {
        const name = String(data.get('name-' + i)).normalize('NFC').trim(); const value = String(data.get('value-' + i)).normalize('NFC').trim()
        if (!name && !value) continue
        if (!name || !value || [...name].length > 40 || [...value].length > 40 || invalidOptionText(name + value)) { setError('옵션 이름과 값을 각각 1~40자로 입력해 주세요.'); return }
        options.push({ name, value })
      }
      if (!options.length || new Set(options.map(o => o.name)).size !== options.length) { setError('서로 다른 옵션 이름을 1~3개 입력해 주세요.'); return }
      if (data.get('confirm') !== 'on') { setError('옵션 등록 후 기본 SKU 복귀가 불가함을 확인해 주세요.'); return }
    }
    setError(''); save({ options, price, active: data.get('active') === 'on' })
  }
  return <form onSubmit={submit}><fieldset disabled={disabled}><legend>{item ? 'SKU 수정' : '옵션 SKU 등록'}</legend>
    {item ? <p>{skuLabel(item)}<br />코드: {item.code}</p> : <>{[0, 1, 2].map(i => <div key={i}>
      <label>옵션 이름 {i + 1}<input name={'name-' + i} placeholder={i === 0 ? '예: 색상' : ''} /></label>
      <label>옵션 값 {i + 1}<input name={'value-' + i} placeholder={i === 0 ? '예: 빨강' : ''} /></label>
    </div>)}</>}
    <label>SKU 가격 (원)<input name="price" type="number" min="0" max="999999999" step="1" required defaultValue={item?.price ?? 0} /></label>
    <label className="checkbox-label"><input name="active" type="checkbox" defaultChecked={item?.active ?? false} />SKU 판매 활성</label>
    {!item && <label className="checkbox-label"><input name="confirm" type="checkbox" />기본 SKU 복귀 불가와 조합 변경 불가를 확인합니다</label>}
    {error && <p role="alert">{error}</p>}<button type="submit">{item ? 'SKU 저장' : '옵션 SKU 등록'}</button>
  </fieldset></form>
}
