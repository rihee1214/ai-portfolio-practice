import { expect, it, vi } from 'vitest'
import { GuestAccessStore } from '../../src/guest/GuestAccessStore'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function fixture() {
  const fetcher = vi.fn(async (path: RequestInfo | URL) => {
    if (String(path).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (String(path).endsWith('/login')) return json({ status: 'AUTHENTICATED' })
    if (String(path).endsWith('/logout')) return json({ status: 'LOGGED_OUT' })
    return json({ orders: [{ orderNumber: 'FIXTURE-A' }] })
  })
  return { fetcher, store: new GuestAccessStore(fetcher) }
}
it('requires server authentication and clears orders on logout without retaining passwords', async () => {
  const f = fixture(); await f.store.login('FIXTURE-A', '01012345678', '  secret password  ')
  expect(f.store.getSnapshot().orders).toEqual(['FIXTURE-A'])
  expect(JSON.stringify(f.store.getSnapshot())).not.toContain('secret')
  const pending = f.store.logout(); expect(f.store.getSnapshot().orders).toEqual([]); await pending
  expect(f.store.getSnapshot().authenticated).toBe(false)
})
it('blocks duplicate submissions and stale responses after leaving', async () => {
  const f = fixture(); let complete!: (response: Response) => void
  f.fetcher.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const pending = f.store.login('FIXTURE-A', '01012345678', 'long password')
  await f.store.login('FIXTURE-A', '01012345678', 'long password')
  expect(f.fetcher).toHaveBeenCalledTimes(1)
  f.store.dispose(); complete(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })); await pending
  expect(f.fetcher).toHaveBeenCalledTimes(1); expect(f.store.getSnapshot().orders).toEqual([])
})
it('does not retain protected data after session expiry or transport failure', async () => {
  const f = fixture(); await f.store.load(); expect(f.store.getSnapshot().authenticated).toBe(true)
  f.fetcher.mockResolvedValueOnce(json({}, 401)); await f.store.load()
  expect(f.store.getSnapshot()).toMatchObject({ authenticated: false, orders: [] })
  f.fetcher.mockRejectedValueOnce(new Error('private detail')); await f.store.load()
  expect(f.store.getSnapshot().error).not.toContain('private detail'); expect(f.store.getSnapshot().error).not.toBe('')
})
it('retains an explicit logout retry after failure without restoring protected orders', async () => {
  const f = fixture(); await f.store.load()
  f.fetcher.mockRejectedValueOnce(new Error('network'))
  await f.store.logout()
  expect(f.store.getSnapshot()).toMatchObject({ logoutPending: true, authenticated: false, orders: [] })
  const calls = f.fetcher.mock.calls.length; await f.store.load(); expect(f.fetcher).toHaveBeenCalledTimes(calls)
  await f.store.logout(); expect(f.store.getSnapshot().logoutPending).toBe(false)
})
