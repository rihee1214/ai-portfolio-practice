import { beforeEach, expect, test, vi } from 'vitest'
import { AuthStore } from '../../src/auth/AuthStore'
import { WishlistStore } from '../../src/cart/WishlistStore'
const owner = '00000000-0000-4000-8000-000000000001', other = '00000000-0000-4000-8000-000000000002'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => localStorage.clear())
test('찜 조회·저장·삭제는 예상 계정과 CSRF를 보내고 회원 품목을 로컬에 저장하지 않는다', async () => {
  const calls: [string,RequestInit | undefined][] = []
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    calls.push([path,init])
    if (path === '/api/auth/me') return json({ id: owner, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    return json({ items: init?.method === 'DELETE' ? [] : [{ productId: other, detail: null }] })
  }))
  const auth = new AuthStore(); await auth.refresh(); const store = new WishlistStore(auth)
  await store.refresh(); expect(store.getSnapshot().items).toHaveLength(1)
  await store.add('some-product'); await store.remove(other)
  expect(calls.some(([path,init]) => path === '/api/wishlist/some-product?owner='+owner && init?.method === 'PUT' && new Headers(init.headers).get('X-CSRF-TOKEN') === 'test')).toBe(true)
  expect(calls.some(([path,init]) => path === '/api/wishlist/'+other+'?owner='+owner && init?.method === 'DELETE')).toBe(true)
  expect(store.getSnapshot().items).toEqual([]); expect(localStorage.length).toBe(0)
})
test('찜 계정 변경 뒤 늦은 이전 응답과 실패는 기존 품목을 복원하지 않는다', async () => {
  let current = owner; let finish!: (response: Response) => void; let fail = false
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(json({ id: current, roles: ['BUYER'] }))
    if (path === '/api/wishlist?owner='+owner) return new Promise<Response>(resolve => { finish = resolve })
    return Promise.resolve(fail ? json({},409) : json({ items: [{ productId: other, detail: null }] }))
  }))
  const auth = new AuthStore(); await auth.refresh(); const store = new WishlistStore(auth); const stop = store.connect()
  await new Promise(resolve => setTimeout(resolve,0)); current = other; await auth.refresh(); await new Promise(resolve => setTimeout(resolve,0))
  finish(json({ items: [{ productId: owner, detail: null }] })); await new Promise(resolve => setTimeout(resolve,0))
  expect(store.getSnapshot().items.map(i => i.productId)).toEqual([other])
  fail = true; await store.refresh(); expect(store.getSnapshot().items).toEqual([]); expect(store.getSnapshot().error).toBeTruthy(); stop()
})
test('비회원은 찜 요청을 보내지 않으며 회원 중복·잘못된 응답은 거부한다', async () => {
  let signedIn = false
  const fetcher = vi.fn(async (path: string) => path === '/api/auth/me' ? signedIn ? json({ id: owner, roles:['BUYER'] }) : json({},401) : json({ items: [{ productId:other,detail:null },{ productId:other,detail:null }] }))
  vi.stubGlobal('fetch',fetcher)
  const auth = new AuthStore(); await auth.refresh(); const store = new WishlistStore(auth); await store.add('some-product')
  expect(fetcher).toHaveBeenCalledTimes(1)
  signedIn = true; await auth.refresh(); await store.refresh(); expect(store.getSnapshot().error).toBeTruthy(); expect(store.getSnapshot().items).toEqual([])
})
