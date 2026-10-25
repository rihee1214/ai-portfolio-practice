import { beforeEach, expect, test } from 'vitest'
import { GuestCart, GUEST_KEY, JOURNAL_KEY } from '../../src/cart/GuestCart'
const sku = '00000000-0000-4000-8000-000000000001'
beforeEach(() => localStorage.clear())
test('방문자 품목은 SKU와 수량만 보존하고 잘못된 저장값과 상한을 거부한다', () => {
  const cart = new GuestCart(localStorage)
  cart.set(sku, 99)
  expect(JSON.parse(localStorage.getItem(GUEST_KEY)!)).toEqual([{ skuId: sku, quantity: 99 }])
  expect(new GuestCart(localStorage).read()).toEqual([{ skuId: sku, quantity: 99 }])
  expect(() => cart.set(sku, 100)).toThrow()
  localStorage.setItem(GUEST_KEY, JSON.stringify([{ skuId: sku, quantity: 1, price: 10 }]))
  expect(() => cart.read()).toThrow(); expect(localStorage.getItem(GUEST_KEY)).toContain('price')
  localStorage.setItem(GUEST_KEY, '{broken'); expect(() => cart.set(sku, 1)).toThrow()
  expect(localStorage.getItem(GUEST_KEY)).toBe('{broken')
})
test('방문자 병합은 확인 전 보존하고 재시도 식별자를 다시 사용한다', () => {
  const cart = new GuestCart(localStorage); cart.set(sku, 3)
  const first = cart.prepare()!
  expect(first.items).toEqual([{ skuId: sku, quantity: 3 }])
  expect(new GuestCart(localStorage).prepare()).toEqual(first)
  expect(cart.read()).toHaveLength(1)
  expect(() => cart.set(sku, 4)).toThrow()
  expect(() => cart.acknowledge(crypto.randomUUID())).toThrow()
  cart.acknowledge(first.requestId)
  expect(localStorage.getItem(GUEST_KEY)).toBeNull(); expect(localStorage.getItem(JOURNAL_KEY)).toBeNull()
  cart.set(sku, 3); expect(cart.prepare()!.requestId).not.toBe(first.requestId)
})
test('저장 거부와 병합 확인 정리 실패는 복구 데이터를 없애지 않는다', () => {
  const cart = new GuestCart(localStorage); cart.set(sku, 2); const journal = cart.prepare()!
  const broken = new GuestCart({ getItem: key => localStorage.getItem(key), setItem: () => { throw new Error('quota') }, removeItem: () => { throw new Error('blocked') } })
  expect(() => broken.acknowledge(journal.requestId)).toThrow()
  expect(new GuestCart(localStorage).prepare()).toEqual(journal)
  cart.clear(); expect(() => broken.set(sku, 1)).toThrow(); expect(localStorage.getItem(GUEST_KEY)).toBeNull()
})
test('방문자 장바구니는 100종을 넘기지 않고 중복 저장량을 99로 정규화한다', () => {
  const items = Array.from({ length: 100 }, (_, n) => ({ skuId: '00000000-0000-4000-8000-' + String(n + 1).padStart(12, '0'), quantity: 1 }))
  localStorage.setItem(GUEST_KEY, JSON.stringify(items)); const cart = new GuestCart(localStorage)
  expect(() => cart.set('00000000-0000-4000-8000-000000000999', 1)).toThrow()
  expect(cart.read()).toHaveLength(100)
  localStorage.setItem(GUEST_KEY, JSON.stringify([{ skuId: sku, quantity: 70 }, { skuId: sku, quantity: 60 }]))
  expect(cart.read()).toEqual([{ skuId: sku, quantity: 99 }])
  cart.set(sku, 0); expect(cart.read()).toEqual([])
})

test('로그인 병합 응답 유실은 방문자 기록을 유지하고 같은 요청으로 복구한다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const guest = new GuestCart(localStorage); guest.set(sku, 3)
  const ids: string[] = []; let fail = true
  const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/auth/me') return json({ id: sku, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test-token' })
    if (path === '/api/cart/merge') { ids.push(JSON.parse(String(init?.body)).requestId); if (fail) throw new Error('lost'); return json({ version: 1, items: [{ skuId: sku, quantity: 3, detail: null }] }) }
    return json({ version: 0, items: [] })
  }))
  const auth = new AuthStore(); await auth.refresh(); const store = new CartStore(auth, guest, work => work())
  await store.refresh(); expect(store.getSnapshot().error).toBeDefined(); expect(guest.read()).toHaveLength(1)
  fail = false; await store.refresh()
  expect(ids).toHaveLength(2); expect(ids[1]).toBe(ids[0]); expect(store.getSnapshot().items[0].quantity).toBe(3); expect(guest.read()).toEqual([])
  expect(localStorage.getItem(JOURNAL_KEY)).toBeNull(); expect(localStorage.length).toBe(0)
})
test('로그아웃 뒤 늦게 도착한 회원 장바구니는 표시되거나 로컬 저장되지 않는다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  let signedIn = true; let finish!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(signedIn ? json({ id: sku, roles: ['BUYER'] }) : new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } }))
    if (path.startsWith('/api/cart?owner=')) return new Promise<Response>(resolve => { finish = resolve })
    return Promise.resolve(json({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }))
  }))
  const auth = new AuthStore(); await auth.refresh(); const store = new CartStore(auth, new GuestCart(localStorage), work => work()); const stop = store.connect()
  await new Promise(resolve => setTimeout(resolve, 0))
  signedIn = false; await auth.refresh()
  finish(json({ version: 1, items: [{ skuId: sku, quantity: 7, detail: null }] }))
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(store.getSnapshot().items).toEqual([]); expect(localStorage.length).toBe(0); stop()
})

test('동시 방문자 담기는 잠금 안에서 최신 저장 수량을 읽어 합산한다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/auth/me') return new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    return json({ version: 0, items: JSON.parse(String(init?.body)).items.map((row: { skuId: string; quantity: number }) => ({ ...row, detail: null })) })
  }))
  let tail: Promise<unknown> = Promise.resolve()
  const lock = <T,>(work: () => Promise<T>): Promise<T> => { const next = tail.then(work); tail = next.catch(() => {}); return next }
  const guest = new GuestCart(localStorage); guest.set(sku, 1)
  const auth = new AuthStore(); await auth.refresh()
  const first = new CartStore(auth, guest, lock), second = new CartStore(auth, guest, lock)
  await first.refresh(); await second.refresh(); await Promise.all([first.add(sku, 1), second.add(sku, 1)])
  expect(guest.read()).toEqual([{ skuId: sku, quantity: 3 }])
})

test('회원 조회는 서버 쿠키 계정이 바뀌어도 예상 계정 밖의 품목을 받지 않는다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id: sku, roles: ['BUYER'] })
    if (path === '/api/cart?owner=' + sku) return json({}, 409)
    return json({ version: 3, items: [{ skuId: sku, quantity: 8, detail: null }] })
  }))
  const auth = new AuthStore(); await auth.refresh(); const store = new CartStore(auth, new GuestCart(localStorage), work => work())
  await store.refresh(); expect(store.getSnapshot().items).toEqual([]); expect(store.getSnapshot().error).toBeDefined()
  await store.discardGuest(); expect(store.getSnapshot().items).toEqual([]); expect(store.getSnapshot().error).toBeDefined()
})
test('품목 삭제 뒤 journal 삭제만 실패해도 동일 요청으로 복구한다', () => {
  const guest = new GuestCart(localStorage); guest.set(sku, 3); const journal = guest.prepare()!
  const broken = new GuestCart({ getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value), removeItem: key => { if (key === JOURNAL_KEY) throw new Error('blocked'); localStorage.removeItem(key) } })
  expect(() => broken.acknowledge(journal.requestId)).toThrow()
  expect(localStorage.getItem(GUEST_KEY)).toBeNull(); expect(guest.prepare()).toEqual(journal)
  guest.acknowledge(journal.requestId); expect(localStorage.length).toBe(0)
})
test('다른 계정의 pending 병합 충돌은 journal과 방문자 품목을 보존한다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const guest = new GuestCart(localStorage); guest.set(sku, 3); const journal = guest.prepare()!
  const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id: '00000000-0000-4000-8000-000000000002', roles: ['BUYER'] }) : path === '/api/auth/csrf' ? json({ headerName: 'X-CSRF-TOKEN', token: 'token' }) : json({}, 409)))
  const auth = new AuthStore(); await auth.refresh(); const store = new CartStore(auth, guest, work => work()); await store.refresh()
  expect(store.getSnapshot().items).toEqual([]); expect(store.getSnapshot().error).toBeDefined(); expect(guest.prepare()).toEqual(journal); expect(guest.read()).toEqual(journal.items)
})

test('A에서 B로 전환한 뒤 늦은 A 응답은 B 장바구니를 덮지 않는다', async () => {
  const { vi } = await import('vitest'); const { AuthStore } = await import('../../src/auth/AuthStore'); const { CartStore } = await import('../../src/cart/CartStore')
  const other = '00000000-0000-4000-8000-000000000002'
  const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  let owner = sku; let finish!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(json({ id: owner, roles: ['BUYER'] }))
    if (path === '/api/cart?owner=' + sku) return new Promise<Response>(resolve => { finish = resolve })
    return Promise.resolve(json({ version: 4, items: [{ skuId: other, quantity: 2, detail: null }] }))
  }))
  const auth = new AuthStore(); await auth.refresh()
  const store = new CartStore(auth, new GuestCart(localStorage), work => work()); const stop = store.connect()
  await new Promise(resolve => setTimeout(resolve, 0))
  owner = other; await auth.refresh(); await new Promise(resolve => setTimeout(resolve, 0))
  finish(json({ version: 1, items: [{ skuId: sku, quantity: 7, detail: null }] }))
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(store.getSnapshot().items).toEqual([{ skuId: other, quantity: 2, detail: null }]); expect(localStorage.length).toBe(0); stop()
})
