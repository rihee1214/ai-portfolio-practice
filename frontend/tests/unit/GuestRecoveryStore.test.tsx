import { expect, it, vi } from 'vitest'
import { GuestRecoveryStore } from '../../src/guest/GuestRecoveryStore'
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
it('requires server grant and clears one-time secrets on leaving', async () => {
  const fetcher = vi.fn(async (path: RequestInfo | URL) => {
    if (String(path).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (String(path).endsWith('/start')) return json({ attemptId: 'id', expiresAt: new Date(Date.now() + 180000).toISOString() })
    if (String(path).endsWith('/exchange')) return json({ customToken: 'custom', host: 'host', project: 'project' })
    if (String(path).endsWith('/verify')) return json({ grant: 'g'.repeat(43) })
    return json({ status: 'PASSWORD_RESET', recoveryCode: 'r'.repeat(43) })
  })
  const store = new GuestRecoveryStore(fetcher, async () => 'proof')
  await store.start('order', '01012345678', 'old-code'); await store.verify('123456')
  expect(store.getSnapshot().step).toBe('READY'); expect(JSON.stringify(store.getSnapshot())).not.toContain('g'.repeat(43))
  await store.reset('new password', 'new password'); expect(store.getSnapshot().recoveryCode).toBe('r'.repeat(43))
  store.dispose(); expect(store.getSnapshot().recoveryCode).toBe('')
})
it('blocks duplicates and ignores late responses after disposal', async () => {
  let done!: (response: Response) => void
  const fetcher = vi.fn(() => new Promise<Response>(resolve => { done = resolve }))
  const store = new GuestRecoveryStore(fetcher, async () => 'proof')
  const pending = store.start('order', 'phone', 'code'); await store.start('order', 'phone', 'code')
  expect(fetcher).toHaveBeenCalledTimes(1); store.dispose(); done(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })); await pending
  expect(fetcher).toHaveBeenCalledTimes(1); expect(store.getSnapshot().step).toBe('INPUT')
})
it('does not claim success after a lost response and permits explicit retry', async () => {
  const fetcher = vi.fn(async (path: RequestInfo | URL) => {
    if (String(path).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    throw new Error('secret transport details')
  })
  const store = new GuestRecoveryStore(fetcher, async () => 'proof')
  await store.manage('current', '', '', false)
  expect(store.getSnapshot().step).toBe('INPUT'); expect(store.getSnapshot().error).not.toContain('secret'); expect(store.getSnapshot().error).not.toBe('')
  expect(store.getSnapshot().busy).toBe(false)
  fetcher.mockImplementation(async path => String(path).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) : json({ status: 'CODE_REPLACED', recoveryCode: 'r'.repeat(43) }))
  await store.manage('current', '', '', false)
  expect(store.getSnapshot().step).toBe('COMPLETE'); expect(store.getSnapshot().recoveryCode).toBe('r'.repeat(43))
})
it('explains the resend and window limits after a 429 without exposing protected data', async () => {
  const store = new GuestRecoveryStore(async path => String(path).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) : new Response('{}', { status: 429, headers: { 'Content-Type': 'application/json' } }))
  await store.start('order', 'phone', 'code')
  expect(store.getSnapshot().error).toContain('60초'); expect(store.getSnapshot().error).toContain('15분')
  expect(store.getSnapshot()).toMatchObject({ step: 'INPUT', recoveryCode: '', busy: false })
})
