import {policyFixture} from './returnPolicyFixture'
import { test, expect, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { SellerFeaturePage } from '../../src/pages/StoreLifecycle'
import { CatalogStore, categoryValid, productValid } from '../../src/catalog/CatalogStore'
import { CategoryPage, ProductPage } from '../../src/pages/CatalogPages'
import { ProductDraftScope } from '../../src/catalog/ProductDraftScope'
import userEvent from '@testing-library/user-event'

const id = '00000000-0000-4000-8000-000000000003'
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
test('카테고리 입력은 서버와 같은 문자 기준으로 emoji 80자를 허용한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id, roles: ['ADMIN'] }) : json({ items: [], page: 0, hasMore: false })))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><CategoryPage /></AuthContext>)
  const input = await screen.findByLabelText('카테고리 이름')
  await userEvent.setup().type(input, '😀'.repeat(80))
  expect(input).toHaveValue('😀'.repeat(80))
})
test('저장된 여러 줄 설명과 선택창의 레이블 이름은 내용에 따라 바뀌지 않는다', async () => {
  const item = { id, slug: 'test-product', title: '상품', description: '첫 줄\n둘째 줄', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false,returnPolicy:policyFixture(id) }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    return json({ items: path.startsWith('/api/categories') ? [{ id, name: '카테고리 항목', active: true, version: 0 }] : [item], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  await userEvent.setup().click(await screen.findByRole('button', { name: '상품 · 초안' }))
  expect(screen.getByRole('textbox', { name: '상품 설명' }).textContent).toBe('첫 줄\n둘째 줄')
  expect(screen.getByLabelText('카테고리', { exact: true })).toHaveValue(id)
  expect(screen.getByLabelText('상품 상태', { exact: true })).toHaveValue('DRAFT')
})
test('운영 가능한 판매자의 상품 공간에 등록 폼을 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'SELLER'] })
    if (path === '/api/seller/capabilities') return json({ manageCatalog: true, existingTrades: true, newSales: true })
    return json({ items: [], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<MemoryRouter><AuthContext value={auth}><SellerFeaturePage feature={{ path: 'products', title: '상품 관리', description: '', planned: '' }} /></AuthContext></MemoryRouter>)
  expect(await screen.findByRole('button', { name: '상품 등록' })).toBeVisible()
  expect(screen.getByLabelText('카테고리', { exact: true })).toHaveAttribute('id', 'product-category')
  expect(screen.getByLabelText('상품 상태', { exact: true })).toHaveAttribute('id', 'product-state')
})

test('상품 수정은 버전과 CSRF를 보내고 409를 자동 재시도하지 않는다', async () => {
  const item = { id, slug: 'test-product', title: '상품', description: '', categoryId: id, state: 'ACTIVE', version: 7, hiddenByAdmin: true,returnPolicy:policyFixture(id) }
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'PUT') return new Response(JSON.stringify({ code: 'CONFLICT', requestId: id }), { status: 409, headers: { 'Content-Type': 'application/json' } })
    return json({ items: [item], page: 0, hasMore: false })
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  const store = new CatalogStore(auth, '/api/seller/products', productValid); const stop = store.connect()
  await waitFor(() => expect(store.getSnapshot().items).toHaveLength(1))
  await store.save(store.getSnapshot().items![0], { state: 'STOPPED' })
  const calls = fetcher.mock.calls.filter(call => call[1].method === 'PUT')
  expect(calls).toHaveLength(1)
  expect(new Headers(calls[0][1].headers).get('X-CSRF-TOKEN')).toBe('csrf')
  expect(JSON.parse(String(calls[0][1].body))).toEqual({ state: 'STOPPED', version: 7 })
  expect(store.getSnapshot().error).not.toBeNull()
  expect(store.getSnapshot().items![0].version).toBe(7)
  stop()
})

test('카테고리 저장 중 로그아웃하면 늦은 성공 응답을 폐기한다', async () => {
  let release!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['ADMIN'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'POST') return new Promise<Response>(resolve => { release = resolve })
    return json({ items: [], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  const store = new CatalogStore(auth, '/api/admin/content/categories', categoryValid); const stop = store.connect()
  await waitFor(() => expect(store.getSnapshot().items).toEqual([]))
  const saving = store.save(null, { name: '카테고리', active: true })
  await waitFor(() => expect(release).toBeDefined())
  auth.stop(); release(new Response(JSON.stringify({ id, name: '카테고리', active: true, version: 0 }), { status: 201, headers: { 'Content-Type': 'application/json' } })); await saving
  expect(store.getSnapshot().items).toBeUndefined()
  expect(store.getSnapshot().notice).toBe('')
  stop()
})

test('상품을 선택하면 기본 SKU 가격과 판매 여부를 관리할 수 있다', async () => {
  const product = { id, slug: 'test-product', title: '상품', description: '', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false,returnPolicy:policyFixture(id) }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path.includes('/skus')) return json({ items: [{ id, code: 'sku-' + id, options: [], price: 0, active: false, version: 0 }], page: 0, hasMore: false })
    return json({ items: path.startsWith('/api/categories') ? [{ id, name: '카테고리', active: true, version: 0 }] : [product], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  await userEvent.setup().click(await screen.findByRole('button', { name: '상품 · 초안' }))
  await userEvent.setup().click(await screen.findByRole('button', { name: '기본 SKU · 0원 · 판매 중지' }))
  expect(await screen.findByLabelText('SKU 가격 (원)')).toHaveValue(0)
  expect(screen.getByLabelText('SKU 판매 활성')).not.toBeChecked()
})

test('선택한 상품에 이미지 업로드와 대체 텍스트 입력이 있다', async () => {
  const product = { id, slug: 'test-product', title: '상품', description: '', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false,returnPolicy:policyFixture(id) }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path.startsWith('/api/media/')) return json(Array.from({ length: 5 }, (_, slot) => ({ slot, assetId: null, alt: '', version: 0, width: 0, height: 0 })))
    return json({ items: path.includes('/skus') || path.startsWith('/api/categories') ? [] : [product], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  await userEvent.setup().click(await screen.findByRole('button', { name: '상품 · 초안' }))
  expect(await screen.findByLabelText('이미지 파일 1')).toBeEnabled()
  expect(screen.getByLabelText('이미지 설명 1')).toBeEnabled()
})

test('상품 선택 후 SKU 재고 수량과 조정 사유를 입력할 수 있다', async () => {
  const product = { id, slug: 'stock-product', title: '재고 상품', description: '', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false,returnPolicy:policyFixture(id) }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path.includes('/inventory')) return json({ items: [{ id, code: 'sku-test', onHand: 0, reserved: 0, available: 0, version: 0 }], page: 0, hasMore: false })
    if (path.includes('/skus')) return json({ items: [], page: 0, hasMore: false })
    return json({ items: path.startsWith('/api/categories') ? [] : [product], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  await userEvent.setup().click(await screen.findByRole('button', { name: '재고 상품 · 초안' }))
  await userEvent.setup().click(await screen.findByRole('button', { name: /sku-test · 보유/ }))
  expect(screen.getByLabelText('보유 수량')).toHaveValue(0)
  expect(screen.getByLabelText('재고 조정 사유')).toBeVisible()
})
test('P09C seller form shows the saved zero return fee before changing it', async () => {
  const item={id,slug:'zero-fee-product',title:'회수비 상품',description:'',categoryId:id,state:'DRAFT',version:0,hiddenByAdmin:false,returnPolicy:{productId:id,revision:1,source:'SELLER_PRODUCT',amount:'0',version:'P09-RETURN-PRODUCT-MAX-1',basis:'ORIGINAL_SHIPMENT',aggregation:'RETURNED_TARGET_CUMULATIVE_MAX',repeat:'DELTA_ONLY'}}
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id,roles:['SELLER']}):json({items:path.startsWith('/api/categories')?[{id,name:'분류',active:true,version:0}]:[item],page:0,hasMore:false})))
  const auth=new AuthStore();await auth.refresh();render(<AuthContext value={auth}><ProductPage/></AuthContext>);await userEvent.setup().click(await screen.findByRole('button',{name:'회수비 상품 · 초안'}));expect(screen.getByLabelText('변심 반품 회수비')).toHaveValue('0')
})

test('a saved product opens for editing even when it is outside the visible page', async () => {
  const product = { id, slug: 'new-product', title: '새 상품 이름', description: '', categoryId: id, state: 'DRAFT', version: 0,
    hiddenByAdmin: false, returnPolicy: policyFixture(id) }
  const items = Array.from({ length: 20 }, (_, index) => {
    const rowId = '00000000-0000-4000-8000-' + String(index + 10).padStart(12, '0')
    return { ...product, id: rowId, slug: 'other-product-' + index, title: '다른 상품 ' + index, returnPolicy: policyFixture(rowId) }
  })
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (path.startsWith('/api/categories')) return json({ items: [{ id, name: '상품 분류', active: true, version: 0 }], page: 0, hasMore: false })
    if (init.method === 'POST') return new Response(JSON.stringify(product), { status: 201, headers: { 'Content-Type': 'application/json' } })
    return json({ items: path.startsWith('/api/seller/products?') ? items : [], page: 0, hasMore: true })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText(/^상품 주소/), product.slug)
  await user.type(screen.getByLabelText('상품명'), product.title)
  await user.selectOptions(screen.getByLabelText('카테고리', { exact: true }), id)
  await user.click(screen.getByRole('button', { name: '상품 등록' }))
  expect(await screen.findByRole('button', { name: '상품 저장' })).toBeEnabled()
  expect(screen.getByLabelText('상품명')).toHaveValue(product.title)
  expect(screen.queryByRole('button', { name: product.title + ' · 초안' })).toBeNull()
})

test('the native picker delivers a selected file while the seller workspace rechecks access', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id, roles: ['SELLER'] })
    : json({ items: [], page: 0, hasMore: false })))
  const auth = new AuthStore(); await auth.refresh()
  function GatedProducts({ allowed }: { allowed: boolean }) {
    return <AuthContext value={auth}><ProductDraftScope>{allowed && <ProductPage />}</ProductDraftScope></AuthContext>
  }
  const view = render(<GatedProducts allowed />)
  const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: '이미지 삽입' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '이미지 삽입' }))
  const input = screen.getByLabelText('이미지 파일')
  view.rerender(<GatedProducts allowed={false} />)
  expect(screen.queryByRole('textbox', { name: '상품 설명' })).toBeNull()
  expect(screen.getByLabelText('이미지 파일')).toBe(input)
  fireEvent.change(input, { target: { files: [new File(['fixture'], 'focus-draft.png', { type: 'image/png' })] } })
  view.rerender(<GatedProducts allowed />)
  expect(await screen.findByText('focus-draft.png')).toBeVisible()
  expect(screen.getByLabelText('이미지 대체 텍스트')).toBeEnabled()
})
