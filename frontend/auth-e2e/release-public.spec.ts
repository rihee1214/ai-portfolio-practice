import { test, expect, operation } from './business-deadline'
import { assets, csrf, evidence, input, login, observation, order, product, purchase, read, session } from './release-helpers'
import { infrastructure, type Snapshot } from './release-infrastructure'
import { completedCheckpoint, preservation, stoppedSourceUnchanged } from './release-preservation'

type PublicSnapshot = Snapshot & { guest: { verifications: number; checkouts: number; accessSessions: number; providerCalls: number } }
test('P12-D public release rejects guest issuance and lookup without database or provider effects', async ({ browser }) => {
  expect(input.mode).toBe('P'); expect(input.emulator).toBeUndefined()
  const purchases: { project: 'desktop' | 'mobile'; number: string; cookies: Awaited<ReturnType<import('@playwright/test').BrowserContext['cookies']>> }[] = []
  for (const project of ['desktop', 'mobile'] as const) {
    const fixture = await product(browser, project)
    const actor = await session(browser, input.source, project === 'mobile')
    await assets(actor.page, 'R1'); await login(actor.page, input.accounts[project].buyer)
    expect(await read(actor.context, '/api/runtime-policy')).toEqual({ mode: 'public-demo', restricted: true })
    const number = await purchase(actor.page, fixture)
    await order(actor.page, number)
    await operation('release: public member commerce persists one autonomous worker effect', async () => {
      await expect.poll(async () => (await observation<{ shipments: unknown[] }>(input.source, '/purchase?orderNumber=' + number)).shipments.length).toBe(1)
      expect(await observation(input.source, '/purchase?orderNumber=' + number)).toMatchObject({
        order: { state: 'PAID', amount: 13000 }, attempts: [{ approval_effects: 1, success_events: 1 }],
      })
    })
    purchases.push({ project, number, cookies: await actor.context.cookies() })
    const visitor = await session(browser, input.source, project === 'mobile')
    // Establish a legitimate CSRF session before measuring guest/provider effects.
    const headers = await operation('release: obtain public guest rejection CSRF session', () => csrf(visitor.context))
    const before = await observation<PublicSnapshot>(input.source, '/snapshot')
    expect(before.business).toBeDefined()
    expect(before.guest).toEqual({ verifications: expect.any(Number), checkouts: expect.any(Number),
      accessSessions: expect.any(Number), providerCalls: 0 })
    await operation('release: public guest issuance and lookup receive explicit 503 JSON', async () => {
      for (const [path, data] of [
        ['/api/guest/verifications', { name: '합성 공개 차단', phone: '+821069160009', purpose: 'PURCHASE' }],
        ['/api/guest/access/login', { orderNumber: 'O' + '1'.repeat(32), phone: '+821069160009', password: 'public rejection credential' }],
      ] as const) {
        const response = await visitor.context.request.post(path, { headers, data })
        expect(response.status(), path + ' ' + await response.text()).toBe(503)
        expect(response.headers()['content-type']).toContain('application/json')
        expect(response.headers()['cache-control']).toContain('no-store')
        expect((await response.json()).code).toBe('DEMO_FEATURE_UNAVAILABLE')
      }
    })
    await operation('release: public guest lookup UI preserves explicit restriction and never displays order', async () => {
      await visitor.page.goto('/guest/orders/login')
      await expect(visitor.page.getByRole('button', { name: '비회원 주문 조회 인증', exact: true })).toBeDisabled()
      await expect(visitor.page.getByText(/초대받은 검토자의 기존 개별 계정/)).toBeVisible()
      await expect(visitor.page.locator('form')).toHaveCount(0)
      await expect(visitor.page.getByRole('heading', { name: '연결된 주문', exact: true })).toHaveCount(0)
      await expect(visitor.page.getByTestId('order-number')).toHaveCount(0)
    })
    const after = await observation<PublicSnapshot>(input.source, '/snapshot')
    expect(after.guest).toEqual(before.guest); expect(after.business).toEqual(before.business)
    evidence('release-public-boundary', { project, before, after, expectedGuestIssuance: 503, expectedGuestLookup: 503, providerCalls: 0 })
  }
  const originalCheckpoint = await completedCheckpoint(await infrastructure(input.source, 'Checkpoint'))
  const checkpointGuest = (await observation<PublicSnapshot>(input.source, '/snapshot')).guest
  expect(await infrastructure(input.source, 'Activate')).toMatchObject({ ready: true, releaseId: 'R1' })
  const sourceStopped = await completedCheckpoint(await infrastructure(input.source, 'Checkpoint'))
  expect(sourceStopped.reference.commandId).not.toBe(originalCheckpoint.reference.commandId)
  expect(sourceStopped.reference.sourceIdentity).toBe(originalCheckpoint.reference.sourceIdentity)
  const sourceBeforeRestore = await observation<Snapshot>(input.source, '/snapshot')
  expect(sourceBeforeRestore.full).toEqual(sourceStopped.snapshot.full)
  const restoreResult = await infrastructure(input.source, 'Restore', { checkpointRef: originalCheckpoint.reference,
    beforeSnapshotId: sourceStopped.snapshot.snapshotId })
  expect(restoreResult).toMatchObject({ ready: true })
  await preservation(restoreResult, originalCheckpoint.snapshot.snapshotId, originalCheckpoint, sourceStopped.snapshot)
  await stoppedSourceUnchanged(sourceBeforeRestore, await observation<Snapshot>(input.source, '/snapshot'))
  const restored = await observation<PublicSnapshot>(input.restore, '/snapshot')
  expect(restored.guest).toEqual(checkpointGuest)
  for (const purchase of purchases) {
    const old = await session(browser, input.restore, purchase.project === 'mobile')
    await operation('release: public restored member snapshot cannot revive prior session', async () => {
      await old.context.addCookies(purchase.cookies)
      expect((await old.context.request.get('/api/auth/me')).status()).toBe(401)
    })
    const current = await session(browser, input.restore, purchase.project === 'mobile')
    await login(current.page, input.accounts[purchase.project].buyer)
    await order(current.page, purchase.number)
  }
  evidence('release-public-restore', { runId: input.runId, originalCheckpoint, business: restored.business, guest: restored.guest,
    purchases: purchases.map(({ project, number }) => ({ project, number })) })
})
