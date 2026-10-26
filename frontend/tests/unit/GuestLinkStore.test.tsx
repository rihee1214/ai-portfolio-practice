import { expect, it, vi } from 'vitest'
import { GuestStore } from '../../src/guest/GuestStore'
const id = '12345678-1234-1234-1234-123456789012'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function fixture() {
  let mode = 'NEW', revision = 0
  const fetcher = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
    if (String(path).endsWith('/csrf')) return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (String(path).endsWith('/checkout')) return json({ state: 'VERIFIED', attemptId: id, maskedPhone: '010-****-5678', expiresAt: new Date(Date.now() + 180000).toISOString(), passwordReady: false })
    if (String(path).includes('/link')) {
      if (init?.method === 'POST') { mode = JSON.parse(String(init.body)).mode; ++revision }
      return json({ mode, ready: mode === 'EXISTING', revision })
    }
    return json({ status: 'PASSWORD_READY' })
  })
  return { store: new GuestStore(fetcher), fetcher }
}
it('restores the server link mode and rejects password preparation for an existing group', async () => {
  const f = fixture(); await f.store.load(); await f.store.selectLink('EXISTING')
  expect(f.store.getSnapshot()).toMatchObject({ linkMode: 'EXISTING', linkReady: true, passwordReady: false, linkRevision: 1 })
  const count = f.fetcher.mock.calls.length
  await f.store.preparePassword('private password', 'private password'); expect(f.fetcher.mock.calls).toHaveLength(count)
  await f.store.load(); expect(f.store.getSnapshot().linkMode).toBe('EXISTING')
  await f.store.selectLink('NEW'); expect(f.store.getSnapshot()).toMatchObject({ linkMode: 'NEW', linkReady: false, passwordReady: false })
  expect(JSON.stringify(f.store.getSnapshot())).not.toContain('private password')
})
it('does not claim a lost selection response succeeded and restores it through explicit refresh', async () => {
  const f = fixture(); await f.store.load(); const original = f.fetcher.getMockImplementation()!
  f.fetcher.mockImplementation(async (path, init) => { const result = await original(path, init); if (String(path).endsWith('/link') && init?.method === 'POST') throw new Error('lost'); return result })
  await f.store.selectLink('EXISTING'); expect(f.store.getSnapshot().linkReady).toBe(false); expect(f.store.getSnapshot().error).not.toBe('')
  await f.store.load(); expect(f.store.getSnapshot()).toMatchObject({ linkMode: 'EXISTING', linkReady: true })
})
it('suppresses duplicate link requests and discards the response after disposal', async () => {
  const f = fixture(); await f.store.load(); let finish!: (value: Response) => void
  f.fetcher.mockImplementation(async path => String(path).endsWith('/csrf') ? json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) : new Promise(resolve => { finish = resolve }))
  const pending = f.store.selectLink('EXISTING'); await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
  await f.store.selectLink('NEW'); f.store.dispose(); finish(json({ mode: 'EXISTING', ready: true, revision: 1 })); await pending
  expect(f.store.getSnapshot()).toMatchObject({ state: 'INPUT', linkReady: false })
})
it('keeps an existing selection unconfirmed on link lookup failure and clears readiness on reset', async () => {
  const f = fixture(); await f.store.load(); await f.store.selectLink('EXISTING'); const original = f.fetcher.getMockImplementation()!
  f.fetcher.mockImplementation(async (path, init) => String(path).includes('/link/') ? json({}, 409) : original(path, init))
  await f.store.load(); expect(f.store.getSnapshot()).toMatchObject({ linkMode: 'EXISTING', linkReady: false, passwordReady: false, linkKnown: false }); expect(f.store.getSnapshot().error).not.toBe('')
  f.fetcher.mockImplementation(original); await f.store.load(); expect(f.store.getSnapshot()).toMatchObject({ linkMode: 'EXISTING', linkReady: true, linkKnown: true })
  f.fetcher.mockResolvedValue(json({ state: 'OPEN' })); await f.store.load(); expect(f.store.getSnapshot()).toMatchObject({ state: 'INPUT', linkReady: false, passwordReady: false })
})
it('clears a previous ready state when checkout refresh fails or expires', async () => {
  for (const result of [json({}, 401), json({ state: 'EXPIRED' }), json({ state: 'CONSUMED' })]) {
    const f = fixture(); await f.store.load(); await f.store.selectLink('EXISTING'); f.fetcher.mockResolvedValue(result)
    await f.store.load(); expect(f.store.getSnapshot().linkReady).toBe(false); expect(f.store.getSnapshot().passwordReady).toBe(false); expect(f.store.getSnapshot().error).not.toBe('')
  }
})
