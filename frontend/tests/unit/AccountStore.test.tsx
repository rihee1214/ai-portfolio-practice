import { expect, test, vi } from 'vitest'
import { AccountStore, isAddress, isProfile } from '../../src/auth/AccountStore'
import { AuthStore } from '../../src/auth/AuthStore'

const id = '00000000-0000-4000-8000-000000000001'
const profile = { email: 'buyer@example.com', displayName: '', phone: null }
const address = { id, label: '집', recipient: '수령인', phone: '01012345678', postalCode: '12345', addressLine1: '서울시', addressLine2: '', isDefault: true }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
async function setup(resource: 'profile' | 'addresses' = 'profile') {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ id, roles: ['BUYER'] }))
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore()
  await auth.refresh()
  return { auth, fetcher, store: new AccountStore(auth, resource) }
}

test('프로필 저장은 CSRF 선행 후 수정 허용 필드만 보내며 정규 응답을 다시 읽는다', async () => {
  const { fetcher, store } = await setup()
  fetcher.mockResolvedValueOnce(json(profile))
  await store.load()
  expect(store.getSnapshot().profile).toEqual(profile)
  const saved = { ...profile, displayName: '구매자', phone: '01012345678' }
  fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json(saved)).mockResolvedValueOnce(json(saved))
  expect(await store.save('PUT', { displayName: saved.displayName, phone: saved.phone })).toBe(true)
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/auth/me', '/api/account/profile', '/api/auth/csrf', '/api/account/profile', '/api/account/profile'])
  expect(JSON.parse(fetcher.mock.calls[3][1].body)).toEqual({ displayName: '구매자', phone: '01012345678' })
  expect(fetcher.mock.calls[3][1].headers.get('X-CSRF-TOKEN')).toBe('csrf')
})

test('인증 재확인과 로그아웃 시작은 개인 정보를 즉시 삭제하며 늦은 응답도 무시한다', async () => {
  const { auth, fetcher, store } = await setup()
  let release!: (response: Response) => void
  fetcher.mockImplementationOnce(() => new Promise<Response>(resolve => { release = resolve }))
  const disconnect = store.connect()
  expect(store.getSnapshot().busy).toBe(true)
  auth.stop()
  release(json(profile))
  await vi.waitFor(() => { expect(store.getSnapshot().busy).toBe(false) })
  expect(store.getSnapshot().profile).toBeNull()
  disconnect()
})

test('세션 만료는 편집 데이터와 인증 상태를 지우고 요청 ID를 보존한다', async () => {
  const { auth, fetcher, store } = await setup()
  fetcher.mockResolvedValueOnce(json(profile))
  await store.load()
  fetcher.mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED', message: '인증 필요', requestId: '00000000-0000-4000-8000-000000000123' }, 401))
  expect(await store.save('PUT', { displayName: '구매자', phone: null })).toBe(false)
  expect(auth.getSnapshot().member).toBeNull()
  expect(auth.getSnapshot().error?.requestId).toBe('00000000-0000-4000-8000-000000000123')
  expect(store.getSnapshot().profile).toBeNull()
})

test('배송지 삭제 뒤 승격된 기본 배송지를 조회하고 실패한 변경은 재전송하지 않는다', async () => {
  const { fetcher, store } = await setup('addresses')
  fetcher.mockResolvedValueOnce(json({ addresses: [address] }))
  await store.load()
  fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ status: 'ADDRESS_DELETED' })).mockResolvedValueOnce(json({ addresses: [] }))
  expect(await store.save('DELETE', undefined, id)).toBe(true)
  expect(store.getSnapshot().addresses).toEqual([])
  fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'INVALID_REQUEST', message: '잘못된 값', requestId: '00000000-0000-4000-8000-000000000123' }, 400))
  expect(await store.save('POST', address)).toBe(false)
  expect(fetcher).toHaveBeenCalledTimes(7)
  expect(store.getSnapshot().error?.requestId).toBe('00000000-0000-4000-8000-000000000123')
})

test('프로필과 배송지 응답은 필수 필드와 기본 배송지 불변식을 검증한다', async () => {
  expect(isProfile(profile)).toBe(true)
  expect(isProfile({ ...profile, phone: 1 })).toBe(false)
  expect(isAddress(address)).toBe(true)
  expect(isAddress({ ...address, isDefault: 'true' })).toBe(false)
  const { fetcher, store } = await setup('addresses')
  fetcher.mockResolvedValueOnce(json({ addresses: [address, address] }))
  expect(await store.load()).toBe(false)
  expect(store.getSnapshot().addresses).toBeNull()
  expect(store.getSnapshot().error).not.toBeNull()
})

test('화면 종료 뒤 도착한 CSRF는 변경 요청을 보내지 않고 늦은 저장 응답도 개인정보를 복원하지 않는다', async () => {
  const { fetcher, store } = await setup()
  let release!: (response: Response) => void
  fetcher.mockImplementationOnce(() => new Promise<Response>(resolve => { release = resolve }))
  const pendingCsrf = store.save('PUT', { displayName: '구매자', phone: null })
  store.stop()
  release(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
  expect(await pendingCsrf).toBe(false)
  expect(fetcher).toHaveBeenCalledTimes(2)
  fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockImplementationOnce(() => new Promise<Response>(resolve => { release = resolve }))
  const pendingSave = store.save('PUT', { displayName: '구매자', phone: null })
  await vi.waitFor(() => { expect(fetcher).toHaveBeenCalledTimes(4) })
  store.stop()
  release(json(profile))
  expect(await pendingSave).toBe(false)
  expect(store.getSnapshot().profile).toBeNull()
  expect(fetcher).toHaveBeenCalledTimes(4)
})
