import {policyFixture} from './returnPolicyFixture'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import { CartContext } from '../../src/cart/CartContext'
import { CartStore } from '../../src/cart/CartStore'
import { GuestCart } from '../../src/cart/GuestCart'
import { expect, test } from 'vitest'
import { BrowseResource, detailValid, pageValid, parseQuery, queryString, type Page } from '../../src/catalog/Browse'
import type { JsonResult } from '../../src/api/http'
const item = { slug: 'sample-item', title: '상품', description: '소개', categoryId: '00000000-0000-4000-8000-000000000003', categoryName: '생활', storeSlug: 'sample-store', storeName: '매대', minPrice: 1000, soldOut: false,returnPolicy:policyFixture() }
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
  const { fireEvent, render, screen } = await import('@testing-library/react')
  const { default: userEvent } = await import('@testing-library/user-event')
  const { vi } = await import('vitest')
  const { MemoryRouter, Routes, Route, Link } = await import('react-router')
  const { ProductDetailPage } = await import('../../src/pages/BrowsePages')
  const response = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  const images = [
    { slot: 0, assetId: item.categoryId, alt: '상품 정면', version: 1, width: 32, height: 32 },
    { slot: 1, assetId: '00000000-0000-4000-8000-000000000006', alt: '상품 뒷면', version: 1, width: 32, height: 32 },
  ]
  let missing!: (r: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } }))
    if (path.endsWith('/reviews')) return Promise.resolve(response({reviews:[],ratingSum:0,count:0}))
    if (path.includes('/api/media/')) return Promise.resolve(response(images))
    if (path.endsWith('/second-item')) return new Promise<Response>(done => { missing = done })
    return Promise.resolve(response({ product: item, offers: [
      { id: item.categoryId, options: [{ name: '색', value: '파랑' }], price: 1000, available: 2 },
      { id: '00000000-0000-4000-8000-000000000004', options: [{ name: '색', value: '빨강' }], price: 2000, available: 0 },
    ] }))
  }))
  const auth = new AuthStore()
  await auth.refresh()
  const storage = { getItem: () => null, setItem: vi.fn(), removeItem: vi.fn() }
  const cart = new CartStore(auth, new GuestCart(storage), work => work())
  await cart.refresh()
  const add = vi.spyOn(cart, 'add')
  render(<AuthContext.Provider value={auth}><CartContext value={cart}><MemoryRouter initialEntries={['/products/sample-item?q=검색&sort=name&page=2']}><Link to="/products/second-item">다른 상품</Link><Routes><Route path="/products/:slug" element={<ProductDetailPage />} /></Routes></MemoryRouter></CartContext></AuthContext.Provider>)
  expect(await screen.findByText('가용 수량: 2')).toBeVisible()
  const user = userEvent.setup()
  const gallery = screen.getByRole('region', { name: '상품 사진' })
  expect(await screen.findByRole('img', { name: '상품 정면' })).toHaveAttribute('src', '/api/media/' + images[0].assetId + '/content')
  await user.click(screen.getByRole('button', { name: '상품 뒷면 보기' }))
  expect(gallery.querySelector('img[alt="상품 뒷면"]')).toHaveAttribute('src', '/api/media/' + images[1].assetId + '/content')
  const purchase = screen.getByRole('region', { name: '구매 정보' })
  const information = screen.getByRole('region', { name: '상품 정보' })
  expect(gallery.compareDocumentPosition(purchase) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(purchase.compareDocumentPosition(information) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getByRole('heading', { level: 1, name: item.title })).toBeVisible()
  expect(screen.getByRole('heading', { name: '배송·반품 안내' })).toBeVisible()
  expect(screen.getByRole('link', { name: '상품 목록으로' })).toHaveAttribute('href', '/products?q=%EA%B2%80%EC%83%89&sort=name&page=2')
  const cartButton = () => screen.getByRole<HTMLButtonElement>('button', { name: '장바구니 담기' })
  expect(cartButton()).toBeEnabled()
  await user.selectOptions(screen.getByLabelText('상품 옵션'), '00000000-0000-4000-8000-000000000004')
  expect(screen.getByText('품절', { exact: true })).toBeVisible(); expect(screen.getByText('선택 가격: 2,000원')).toBeVisible()
  expect(screen.getByLabelText('담을 수량')).toBeDisabled()
  const soldOutButton = cartButton()
  expect(soldOutButton).toBeDisabled()
  await user.click(soldOutButton)
  fireEvent.submit(soldOutButton.form!)
  expect(add).not.toHaveBeenCalled()
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(cart.getSnapshot().items).toEqual([])
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

test('상품 목록은 조회 상태와 빈 목록을 구분하고 검색 조건을 초기화한다', async () => {
  const { fireEvent, render, screen, waitFor } = await import('@testing-library/react')
  const { default: userEvent } = await import('@testing-library/user-event')
  const { vi } = await import('vitest')
  const { MemoryRouter, useLocation } = await import('react-router')
  const { BrowsePage } = await import('../../src/pages/BrowsePages')
  const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  const thumbnail = { assetId: item.categoryId, alt: '상품 실물' }
  const calls: string[] = []
  let initial = true
  let settleSearch: ((result: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    calls.push(path)
    if (path.startsWith('/api/categories')) return Promise.resolve(response({ items: [], page: 0, hasMore: false }))
    const query = new URL(path, 'http://test').searchParams
    if (query.has('q')) return new Promise<Response>(resolve => { settleSearch = resolve })
    const items = initial ? [] : [{ ...item, thumbnail }]
    initial = false
    return Promise.resolve(response({ items, page: 0, hasMore: false }))
  }))
  function Address() { return <output aria-label="현재 주소">{useLocation().search}</output> }
  render(<MemoryRouter initialEntries={['/products?sort=price_desc']}><Address /><BrowsePage /></MemoryRouter>)
  expect(await screen.findByText('아직 등록된 상품이 없습니다')).toBeVisible()
  expect(screen.queryByRole('link', { name: '조건 초기화' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다음 카테고리' })).not.toBeInTheDocument()
  expect(screen.queryByRole('navigation', { name: '상품 페이지' })).not.toBeInTheDocument()

  const user = userEvent.setup()
  await user.type(screen.getByLabelText('상품 검색'), '없는 상품{Enter}')
  await waitFor(() => expect(settleSearch).toBeDefined())
  expect(screen.getByText('상품을 불러오고 있습니다.')).toBeVisible()
  expect(screen.queryByText('아직 등록된 상품이 없습니다')).not.toBeInTheDocument()
  expect(screen.queryByText('검색 결과가 없습니다')).not.toBeInTheDocument()
  settleSearch!(response({}, 500)); settleSearch = undefined
  const retry = await screen.findByRole('button', { name: '상품 다시 불러오기' })
  expect(screen.queryByText('검색 결과가 없습니다')).not.toBeInTheDocument()

  await user.click(retry)
  await waitFor(() => expect(settleSearch).toBeDefined())
  expect(screen.queryByRole('button', { name: '상품 다시 불러오기' })).not.toBeInTheDocument()
  settleSearch!(response({ items: [], page: 0, hasMore: false })); settleSearch = undefined
  expect(await screen.findByText('검색 결과가 없습니다')).toBeVisible()
  await user.click(screen.getByRole('link', { name: '조건 초기화' }))
  const name = await screen.findByRole('link', { name: '상품' })
  const photo = screen.getByRole('link', { name: '상품 사진' })
  const shop = screen.getByRole('link', { name: item.storeName })
  expect(name).toBeVisible()
  expect(photo).toHaveAttribute('href', name.getAttribute('href'))
  expect(photo.querySelector('img')).toHaveAttribute('src', '/api/media/' + thumbnail.assetId + '/content')
  expect(photo.querySelector('img')).toHaveAttribute('alt', thumbnail.alt)
  expect(photo.compareDocumentPosition(shop) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(shop.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(name.compareDocumentPosition(screen.getByText('1,000원', { exact: true })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(calls.some(path => path.startsWith('/api/media/'))).toBe(false)
  fireEvent.error(photo.querySelector('img')!)
  expect(photo).toHaveTextContent('사진을 불러오지 못했습니다.')
  expect(photo).toHaveAttribute('href', name.getAttribute('href'))
  expect(screen.getByLabelText('현재 주소')).toBeEmptyDOMElement()
  expect(screen.getByLabelText('상품 검색')).toHaveValue('')
  expect(screen.getByLabelText('정렬')).toHaveValue('newest')

  await user.type(screen.getByLabelText('상품 검색'), '다른 상품{Enter}')
  await waitFor(() => expect(settleSearch).toBeDefined())
  expect(screen.queryByRole('link', { name: '상품' })).not.toBeInTheDocument()
  settleSearch!(response({}, 500))
  expect(await screen.findByRole('button', { name: '상품 다시 불러오기' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '상품' })).not.toBeInTheDocument()
})

