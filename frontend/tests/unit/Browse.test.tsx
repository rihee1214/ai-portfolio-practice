import { expect, test } from 'vitest'
import { BrowseResource, detailValid, pageValid, parseQuery, queryString, type Page } from '../../src/catalog/Browse'
import type { JsonResult } from '../../src/api/http'
const item = { slug: 'sample-item', title: '상품', description: '소개', categoryId: '00000000-0000-4000-8000-000000000003', categoryName: '생활', storeSlug: 'sample-store', storeName: '매대', minPrice: 1000, soldOut: false }
test('탐색 URL은 조건과 페이지를 복원하고 잘못된 값은 거부한다', () => {
  const query = parseQuery('?q=머그&minPrice=0&maxPrice=2000&inStock=true&sort=price_asc&page=2')!
  expect(query.q).toBe('머그'); expect(query.page).toBe(2); expect(query.inStock).toBe(true)
  expect(parseQuery(queryString(query))).toEqual(query)
  for (const value of ['page=-1', 'page=1.5', 'minPrice=2&maxPrice=1', 'sort=evil', 'inStock=yes', 'category=bad', 'page=1&page=2', 'q=%00', 'q=%0A']) expect(parseQuery(value)).toBeNull()
  expect(parseQuery('q=' + '😀'.repeat(81))).toBeNull()
  expect(parseQuery(queryString({ ...query, q: '다른 상품', page: 0 }))?.page).toBe(0)
})
test('탐색은 취소를 무시한 이전 응답도 새 결과를 덮지 못한다', async () => {
  const resolve: ((result: JsonResult) => void)[] = []; const signals: AbortSignal[] = []
  const store = new BrowseResource<Page>(pageValid, (_path, init) => { signals.push(init!.signal!); return new Promise(done => resolve.push(done)) })
  const first = store.load('old'); const second = store.load('new')
  expect(signals[0].aborted).toBe(true)
  resolve[1]({ kind: 'success', status: 200, data: { items: [item], page: 0, hasMore: false } }); await second
  resolve[0]({ kind: 'success', status: 200, data: { items: [{ ...item, title: '오래된 상품' }], page: 0, hasMore: false } }); await first
  expect(store.getSnapshot().data?.items[0].title).toBe('상품')
  store.stop()
})
test('탐색 실패와 재시도는 이전 결과를 지우고 잘못된 응답을 거부한다', async () => {
  let result: JsonResult = { kind: 'success', status: 200, data: { items: [item], page: 0, hasMore: false } }
  const store = new BrowseResource<Page>(pageValid, async () => result)
  await store.load('list'); result = { kind: 'network' }; await store.load('list')
  expect(store.getSnapshot().data).toBeUndefined(); expect(store.getSnapshot().error).toBeDefined()
  result = { kind: 'success', status: 200, data: { items: [{ ...item, minPrice: -1 }], page: 0, hasMore: false } }; await store.load('list')
  expect(store.getSnapshot().error).toBeDefined()
  result = { kind: 'success', status: 200, data: { items: [], page: 0, hasMore: false } }; await store.load('list')
  expect(store.getSnapshot().data?.items).toEqual([]); expect(store.getSnapshot().error).toBeUndefined()
})
test('공개 상세 응답은 옵션·가격·가용량의 범위를 검증한다', () => {
  const detail = { product: item, offers: [{ id: item.categoryId, options: [{ name: '색상', value: '파랑' }], price: 1000, available: 0 }] }
  expect(detailValid(detail)).toBe(true)
  expect(detailValid({ ...detail, offers: [{ ...detail.offers[0], available: -1 }] })).toBe(false)
  expect(detailValid({ ...detail, offers: [{ ...detail.offers[0], price: '1000' }] })).toBe(false)
  expect(detailValid({ product: { ...item, slug: '../secret' }, offers: [] })).toBe(false)
})

test('상세 화면은 옵션 선택과 주소 변경에서 이전 상품을 제거한다', async () => {
  const { render, screen } = await import('@testing-library/react')
  const { default: userEvent } = await import('@testing-library/user-event')
  const { vi } = await import('vitest')
  const { MemoryRouter, Routes, Route, Link } = await import('react-router')
  const { ProductDetailPage } = await import('../../src/pages/BrowsePages')
  const response = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  let missing!: (r: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path.includes('/api/media/')) return Promise.resolve(response([]))
    if (path.endsWith('/second-item')) return new Promise<Response>(done => { missing = done })
    return Promise.resolve(response({ product: item, offers: [
      { id: item.categoryId, options: [{ name: '색', value: '파랑' }], price: 1000, available: 2 },
      { id: '00000000-0000-4000-8000-000000000004', options: [{ name: '색', value: '빨강' }], price: 2000, available: 0 },
    ] }))
  }))
  render(<MemoryRouter initialEntries={['/products/sample-item']}><Link to="/products/second-item">다른 상품</Link><Routes><Route path="/products/:slug" element={<ProductDetailPage />} /></Routes></MemoryRouter>)
  expect(await screen.findByText('가용 수량: 2')).toBeVisible()
  const user = userEvent.setup()
  await user.selectOptions(screen.getByLabelText('상품 옵션'), '00000000-0000-4000-8000-000000000004')
  expect(screen.getByText('품절', { exact: true })).toBeVisible(); expect(screen.getByText('선택 가격: 2,000원')).toBeVisible()
  await user.click(screen.getByRole('link', { name: '다른 상품' }))
  expect(screen.queryByRole('heading', { name: '상품' })).not.toBeInTheDocument()
  expect(screen.queryByText('선택 가격: 2,000원')).not.toBeInTheDocument()
  missing(new Response('{}', { status: 404, headers: { 'Content-Type': 'application/json' } }))
  expect(await screen.findByRole('alert')).toBeVisible()
})

test('카테고리 선택은 페이지 이동 뒤 유지되고 검색 제출은 페이지를 초기화한다', async () => {
  const { render, screen, waitFor } = await import('@testing-library/react')
  const { default: userEvent } = await import('@testing-library/user-event')
  const { vi } = await import('vitest')
  const { MemoryRouter, useLocation, useNavigate } = await import('react-router')
  const { BrowseList } = await import('../../src/pages/BrowsePages')
  const calls: string[] = []
  const ids = Array.from({ length: 21 }, (_, n) => '00000000-0000-4000-8000-' + String(n + 1).padStart(12, '0'))
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    calls.push(path)
    const categoryPage = new URL(path, 'http://test').searchParams.get('page') === '1' ? 1 : 0
    const data = path.startsWith('/api/categories') ? { page: categoryPage, hasMore: categoryPage === 0, items: ids.slice(categoryPage * 20, categoryPage * 20 + 20).map((id, n) => ({ id, name: '카테고리 ' + (categoryPage * 20 + n + 1) })) }
      : { items: [], page: Number(new URL(path, 'http://test').searchParams.get('page')), hasMore: false }
    return Promise.resolve(new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } }))
  }))
  function Wrapper() { const location = useLocation(); const navigate = useNavigate(); return <><output aria-label="현재 주소">{location.search}</output><button onClick={() => void navigate(-1)}>뒤로</button><BrowseList store="sample-store" /></> }
  render(<MemoryRouter initialEntries={['/stores/sample-store?q=이전&minPrice=10&sort=price_desc&page=2']}><Wrapper /></MemoryRouter>)
  const user = userEvent.setup()
  await screen.findByRole('option', { name: '카테고리 1' })
  await user.selectOptions(screen.getByLabelText('카테고리', { exact: true }), ids[0])
  await user.click(screen.getByRole('button', { name: '다음 카테고리' }))
  await screen.findByRole('option', { name: '카테고리 21' })
  expect(screen.getByLabelText('카테고리', { exact: true })).toHaveValue(ids[0])
  await user.clear(screen.getByLabelText('상품 검색', { exact: true })); await user.type(screen.getByLabelText('상품 검색', { exact: true }), '새검색')
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('page=0'))
  const last = calls.filter(p => p.startsWith('/api/browse/')).at(-1)!
  const query = new URL(last, 'http://test').searchParams
  expect(query.get('category')).toBe(ids[0]); expect(query.get('q')).toBe('새검색'); expect(query.get('store')).toBe('sample-store'); expect(query.get('minPrice')).toBe('10'); expect(query.get('sort')).toBe('price_desc'); expect(query.get('page')).toBe('0')
  await user.click(screen.getByRole('button', { name: '뒤로' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('page=2'))
  expect(screen.getByLabelText('상품 검색', { exact: true })).toHaveValue('이전')
  expect(screen.getByLabelText('카테고리', { exact: true })).toHaveValue('')
  expect(screen.getByLabelText('정렬', { exact: true })).toHaveValue('price_desc')
  await waitFor(() => expect(new URL(calls.filter(p => p.startsWith('/api/browse/')).at(-1)!, 'http://test').searchParams.get('page')).toBe('2'))
})

