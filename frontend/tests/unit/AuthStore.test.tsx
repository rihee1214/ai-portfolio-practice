import { expect, test, vi } from 'vitest'
import { AuthStore } from '../../src/auth/AuthStore'

test('로그인 성공 뒤 토큰 재발급과 me를 조회하고 로그아웃 시작 즉시 개인 상태를 제거한다', async () => {
  const id = '00000000-0000-4000-8000-000000000001'
  const fetcher = vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'before' }))
    .mockResolvedValueOnce(json({ status: 'AUTHENTICATED' }))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'after' }))
    .mockResolvedValueOnce(json({ id, roles: ['BUYER'] }))
  vi.stubGlobal('fetch', fetcher)
  const store = new AuthStore()
  expect(await store.mutate('login', { email: 'a@example.com', password: 'password' })).toBe(true)
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/auth/me'])
  expect(store.getSnapshot().member?.id).toBe(id)
  let release!: (response: Response) => void
  fetcher.mockImplementationOnce(() => new Promise<Response>(resolve => { release = resolve }))
  const pending = store.mutate('logout')
  expect(store.getSnapshot().member).toBeNull()
  expect(store.getSnapshot().busy).toBe(true)
  release(json({ code: 'INTERNAL_ERROR', message: 'failed' }, 503))
  expect(await pending).toBe(false)
  expect(store.getSnapshot().member).toBeNull()
})

test('늦은 me 응답은 로그아웃 이후 개인 정보를 복원하지 않는다', async () => {
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { release = resolve })))
  const store = new AuthStore()
  const pending = store.refresh()
  store.stop()
  release(json({ id: '00000000-0000-4000-8000-000000000001', roles: ['BUYER'] }))
  await pending
  expect(store.getSnapshot().member).toBeNull()
  expect(store.getSnapshot().busy).toBe(false)
})

test('401·네트워크 오류는 개인 정보를 표시하지 않으며 잘못된 성공 응답을 거부한다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED', message: 'login' }, 401))
    .mockRejectedValueOnce(new TypeError('network'))
    .mockResolvedValueOnce(json({ id: 'bad', roles: ['ADMIN'] }))
  vi.stubGlobal('fetch', fetcher)
  const store = new AuthStore()
  await store.refresh()
  expect(store.getSnapshot().checked).toBe(true)
  expect(store.getSnapshot().error).toBeNull()
  await store.refresh()
  expect(store.getSnapshot().error?.message).toContain('연결')
  await store.refresh()
  expect(store.getSnapshot().member).toBeNull()
  expect(store.getSnapshot().error).not.toBeNull()
})

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
test('변경 요청은 CSRF를 먼저 받고 실패한 POST를 자동 재시도하지 않는다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'masked' }))
    .mockResolvedValueOnce(json({ code: 'FORBIDDEN', message: 'denied' }, 403))
  vi.stubGlobal('fetch', fetcher)
  const store = new AuthStore()
  expect(await store.mutate('login', { email: 'a@example.com', password: 'test-password' })).toBe(false)
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(fetcher.mock.calls[1][1].headers.get('X-CSRF-TOKEN')).toBe('masked')
  expect(store.getSnapshot().member).toBeNull()
})
