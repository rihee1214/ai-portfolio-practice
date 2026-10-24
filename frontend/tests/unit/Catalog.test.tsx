import { test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { SellerFeaturePage } from '../../src/pages/StoreLifecycle'
import { CatalogStore, categoryValid, productValid } from '../../src/catalog/CatalogStore'
import { CategoryPage, ProductPage } from '../../src/pages/CatalogPages'
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
  const item = { id, slug: 'test-product', title: '상품', description: '첫 줄\n둘째 줄', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    return json({ items: path.startsWith('/api/categories') ? [{ id, name: '카테고리 항목', active: true, version: 0 }] : [item], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  await userEvent.setup().click(await screen.findByRole('button', { name: '상품 · 초안' }))
  expect(screen.getByLabelText('설명', { exact: true })).toHaveValue('첫 줄\n둘째 줄')
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
  const item = { id, slug: 'test-product', title: '상품', description: '', categoryId: id, state: 'ACTIVE', version: 7, hiddenByAdmin: true }
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
