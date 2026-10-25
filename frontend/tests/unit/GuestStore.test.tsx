import { expect, it, vi } from 'vitest'
import { GuestStore } from '../../src/guest/GuestStore'
const id = '12345678-1234-1234-1234-123456789012'
const status = (state = 'SENT') => ({ state, attemptId: id, maskedPhone: '010-****-5678', expiresAt: new Date(Date.now() + 180000).toISOString() })
const json = (body: unknown, code = 200) => new Response(JSON.stringify(body), { status: code, headers: { 'Content-Type': 'application/json' } })
function fixture() {
  const requests: { path: string; init?: RequestInit }[] = []
  const fetcher = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ path: String(path), init })
    if (String(path).endsWith('/csrf')) return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (String(path).endsWith('/checkout')) return json({ state: 'OPEN' })
    if (String(path).endsWith('/exchange')) return json({ host: '127.0.0.1:9099', project: 'demo-unit', customToken: 'custom-secret' })
    return json(status(String(path).endsWith('/verify') ? 'VERIFIED' : 'SENT'))
  })
  const sdk = vi.fn(async () => 'proof-secret')
  return { store: new GuestStore(fetcher, sdk), fetcher, sdk, requests }
}
it('binds fixed purchase purpose and waits for Java confirmation before showing success', async () => {
  const f = fixture()
  await f.store.issue('가상 구매자', '01012345678')
  expect(f.store.getSnapshot().state).toBe('SENT')
  await f.store.verify('123456')
  expect(f.store.getSnapshot().state).toBe('VERIFIED')
  expect(f.sdk).toHaveBeenCalledWith('127.0.0.1:9099', 'demo-unit', 'custom-secret')
  const changes = f.requests.filter(r => r.init?.method === 'POST')
  expect(changes.map(r => JSON.parse(String(r.init?.body)))).toEqual([{}, { name: '가상 구매자', phone: '01012345678', purpose: 'PURCHASE' }, { phone: '01012345678', purpose: 'PURCHASE', code: '123456' }, { phone: '01012345678', purpose: 'PURCHASE', proof: 'proof-secret' }])
  for (const request of changes) expect(new Headers(request.init?.headers).get('X-CSRF-TOKEN')).toBe('csrf')
  expect(JSON.stringify(f.store.getSnapshot())).not.toMatch(/custom-secret|proof-secret|123456"|01012345678/)
})
it('rejects invalid input and suppresses duplicate submissions', async () => {
  const f = fixture()
  await f.store.issue('', '01012345678'); await f.store.issue('가상', '01112345678')
  expect(f.fetcher).not.toHaveBeenCalled()
  await Promise.all([f.store.issue('가상', '01012345678'), f.store.issue('가상', '01012345678')])
  expect(f.requests.filter(r => r.path === '/api/guest/verifications')).toHaveLength(1)
  await f.store.verify('abc')
  expect(f.sdk).not.toHaveBeenCalled()
})
it('discards late SDK proof after leaving and never sends the verify request', async () => {
  const f = fixture(); let finish!: (value: string) => void
  f.sdk.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await f.store.issue('가상', '01012345678')
  const verifying = f.store.verify('123456')
  await vi.waitFor(() => expect(f.sdk).toHaveBeenCalled())
  f.store.dispose(); finish('late-private-proof'); await verifying
  expect(f.requests.some(r => r.path.endsWith('/verify'))).toBe(false)
  expect(f.store.getSnapshot().state).toBe('INPUT')
  await f.store.load()
  expect(f.requests.at(-1)?.init?.signal?.aborted).toBe(false)
})
it('server rejection never grants success and expiration clears the visible verification', async () => {
  const f = fixture(); await f.store.issue('가상', '01012345678')
  f.fetcher.mockImplementation(async path => String(path).endsWith('/csrf') ? json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) : json({ code: 'CONFLICT' }, 409))
  await f.store.verify('123456')
  expect(f.store.getSnapshot().state).toBe('SENT'); expect(f.store.getSnapshot().error).not.toBe('')
  const time = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 181000)
  f.store.expire(); expect(f.store.getSnapshot().state).toBe('INPUT'); expect(f.store.getSnapshot().attempt).toBe('')
  time.mockRestore()
})
it('restores a pending attempt after refresh and requires a fresh attempt after interrupted exchange', async () => {
  const f = fixture()
  f.fetcher.mockResolvedValueOnce(json({ ...status(), phone: '+821012345678' }))
  await f.store.load()
  expect(f.store.getSnapshot().state).toBe('SENT')
  await f.store.verify('123456')
  expect(f.store.getSnapshot().state).toBe('VERIFIED')
  const interrupted = fixture()
  interrupted.fetcher.mockResolvedValueOnce(json({ ...status('EXCHANGED'), phone: '' }))
  await interrupted.store.load()
  expect(interrupted.store.getSnapshot().state).toBe('INPUT')
  expect(interrupted.store.getSnapshot().error).toContain('새 인증번호')
})
it('SDK failure never confirms and explicit new authentication recovers', async () => {
  const f = fixture(); await f.store.issue('가상', '01012345678')
  f.sdk.mockRejectedValueOnce(new Error('private SDK detail'))
  await f.store.verify('123456')
  expect(f.requests.some(r => r.path.endsWith('/verify'))).toBe(false)
  expect(f.store.getSnapshot().error).not.toContain('private SDK detail')
  await f.store.reset(); expect(f.store.getSnapshot().state).toBe('INPUT')
  await f.store.issue('새 가상', '01012345678'); await f.store.verify('123456')
  expect(f.store.getSnapshot().state).toBe('VERIFIED')
})
