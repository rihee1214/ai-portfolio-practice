import { expect, test, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { AuthStore } from '../../src/auth/AuthStore'
import { StoreOperationsStore } from '../../src/seller/StoreOperationsStore'
import { StoreEditorStore } from '../../src/seller/StoreEditorStore'

const id = '00000000-0000-4000-8000-000000000003'
const item = { id, slug: 'sample-store', name: '매대', introduction: '소개', address: '가상 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 7, state: 'OPEN' as const }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
test('운영 조치는 읽은 버전과 CSRF를 보내고 충돌은 자동 재전송하지 않는다', async () => {
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'ADMIN'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (path.endsWith('/suspend')) return json({ code: 'CONFLICT', message: 'conflict', requestId: id }, 409)
    return json({ items: [item], page: 0, hasMore: false })
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  const store = new StoreOperationsStore(auth); const disconnect = store.connect()
  await waitFor(() => expect(store.getSnapshot().items).toHaveLength(1))
  await store.change(item, 'suspend', '모의 정지')
  const calls = vi.mocked(fetch).mock.calls.filter(call => String(call[0]).endsWith('/suspend'))
  expect(calls).toHaveLength(1)
  expect(new Headers(calls[0][1]?.headers).get('X-CSRF-TOKEN')).toBe('csrf')
  expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ version: 7, reason: '모의 정지' })
  expect(store.getSnapshot().error?.requestId).toBe(id)
  expect(store.getSnapshot().items?.[0].state).toBe('OPEN')
  disconnect()
})
test('폐점 요청 뒤 세션이 사라지면 늦은 응답으로 매대 정보를 복원하지 않는다', async () => {
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (path.endsWith('/close')) return new Promise<Response>(resolve => { release = resolve })
    return json({ store: item })
  }))
  const auth = new AuthStore(); await auth.refresh()
  const store = new StoreEditorStore(auth); const disconnect = store.connect()
  await waitFor(() => expect(store.getSnapshot().store?.id).toBe(id))
  const closing = store.close('모의 폐점')
  await waitFor(() => expect(release).toBeDefined())
  auth.stop(); release(json({ ...item, state: 'CLOSED', visible: false, version: 8 })); await closing
  expect(store.getSnapshot().store).toBeUndefined()
  disconnect()
})
