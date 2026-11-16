import { test, expect, operation, type BrowserContext, type Page } from './business-deadline'
import { assets, csrf, evidence, input, login, media, observation, order, product, purchase, purchaseAmounts, read, retainedAssets, retainedOrder, session, type ProductFixture } from './release-helpers'
import { guestLogin, prepareGuest } from './release-guest'
import { infrastructure, type Snapshot } from './release-infrastructure'
import { completedCheckpoint, preservation, stoppedSourceUnchanged } from './release-preservation'
import { reconciliation, reconciliationContract } from './release-reconciliation'

type PurchaseProof = { order: { state: string; amount: number }; inventory: { status: string }[];
  attempts: { approval_effects: number; success_events: number }[]; shipments: unknown[] }
type Journey = { project: 'desktop' | 'mobile'; fixture: ProductFixture; member: { context: BrowserContext; page: Page };
  guest: { context: BrowserContext; page: Page }; memberNumber: string; guestNumber: string; phone: string;
  oldCsrf: Record<string, string>; newMember?: { context: BrowserContext; page: Page }; postCheckpointNumber?: string }

test('P12-D actual release exchange restores isolated commerce credentials and media', async ({ browser }) => {
  expect(input.mode).toBe('L')
  const financialFixture = reconciliationContract()
  await reconciliation(browser, input.source, 'source', financialFixture)
  const journeys: Journey[] = []
  for (const project of ['desktop', 'mobile'] as const) {
    const fixture = await product(browser, project), member = await session(browser, input.source, project === 'mobile')
    await assets(member.page, 'R0'); await login(member.page, input.accounts[project].buyer)
    const memberNumber = await purchase(member.page, fixture, false, true)
    await order(member.page, memberNumber)
    if (!input.source.guestBaseURL) throw new Error('Local-test guest producer origin required')
    const guest = await session(browser, { ...input.source, baseURL: input.source.guestBaseURL }, project === 'mobile')
    const phone = await prepareGuest(guest.page, project), guestNumber = await purchase(guest.page, fixture, true)
    await guestLogin(guest.page, guestNumber, phone)
    await purchaseAmounts(guest.context, guestNumber); await order(guest.page, guestNumber, true)
    const oldCsrf = await operation('release: capture source session CSRF before checkpoint', () => csrf(member.context))
    await media(fixture.seller, member.context, fixture)
    for (const number of [memberNumber, guestNumber]) {
      await operation('release: await actual worker and verify one purchase effect in database', async () => {
        await expect.poll(async () => (await observation<PurchaseProof>(input.source, '/purchase?orderNumber=' + number)).shipments.length).toBe(1)
        const proof = await observation<PurchaseProof>(input.source, '/purchase?orderNumber=' + number)
        expect(proof.order).toMatchObject({ state: 'PAID', amount: 13000 })
        expect(proof.inventory).toEqual([expect.objectContaining({ status: 'COMMITTED' })])
        expect(proof.attempts).toEqual([expect.objectContaining({ approval_effects: 1, success_events: 1 })])
        evidence('release-database-purchases', { project, number, proof })
      })
    }
    journeys.push({ project, fixture, member, guest, memberNumber, guestNumber, phone, oldCsrf })
  }
  const checkpoint = await infrastructure(input.source, 'Checkpoint')
  expect(checkpoint.releaseId).toBe('R0')
  const originalCheckpoint = await completedCheckpoint(checkpoint)
  const activated = await infrastructure(input.source, 'Activate')
  expect(activated).toMatchObject({ ready: true, releaseId: 'R1' })
  await retainedAssets(journeys[0].member.context, 'R0')
  for (const journey of journeys) {
    // Both original tabs keep their original JS in memory; only current API data is requested.
    await order(journey.member.page, journey.memberNumber, false, true)
    await order(journey.guest.page, journey.guestNumber, true, true)
    const other = await session(browser, input.source)
    await login(other.page, input.accounts[journey.project].other)
    await operation('release: retained credentials and CSRF reject another owner', async () => {
      expect((await journey.member.context.request.get('/api/auth/me')).status()).toBe(200)
      expect((await journey.guest.context.request.get('/api/guest/access/references')).status()).toBe(200)
      expect((await journey.member.context.request.post('/api/auth/logout')).status()).toBe(403)
      expect((await other.context.request.get('/api/orders/' + journey.memberNumber)).status()).toBe(404)
      expect((await other.context.request.get('/api/orders/' + journey.guestNumber)).status()).toBe(404)
      const anotherGuest = journeys.find(candidate => candidate.project !== journey.project)!.guest
      expect((await anotherGuest.context.request.get('/api/orders/' + journey.guestNumber)).status()).toBe(404)
    })
    const oldUiNewApiOrder = await purchase(journey.member.page, journey.fixture, false, true, true)
    await retainedOrder(journey.member.page, journey.memberNumber)
    evidence('release-ui-api-matrix', { project: journey.project, ui: 'R0', api: 'R1', createdOrder: oldUiNewApiOrder })
    journey.newMember = await session(browser, input.source, journey.project === 'mobile')
    await assets(journey.newMember.page, 'R1'); await login(journey.newMember.page, input.accounts[journey.project].buyer)
    journey.postCheckpointNumber = await purchase(journey.newMember.page, journey.fixture, false, true)
    await order(journey.newMember.page, journey.postCheckpointNumber)
  }
  const beforeRollback = await observation<Snapshot>(input.source, '/snapshot')
  const rolledBack = await infrastructure(input.source, 'Rollback', { beforeSnapshotId: beforeRollback.snapshotId })
  expect(rolledBack).toMatchObject({ ready: true, releaseId: 'R0' })
  await preservation(rolledBack, beforeRollback.snapshotId)
  await retainedAssets(journeys[0].member.context, 'R1')
  for (const journey of journeys) {
    // This tab loaded the real R1 production bundle and now consumes the actual R0 API.
    await order(journey.newMember!.page, journey.postCheckpointNumber!, false, true)
    const newUiOldApiOrder = await purchase(journey.newMember!.page, journey.fixture, false, true, true)
    await retainedOrder(journey.newMember!.page, newUiOldApiOrder)
    evidence('release-ui-api-matrix', { project: journey.project, ui: 'R1', api: 'R0', createdOrder: newUiOldApiOrder })
    await order(journey.member.page, journey.memberNumber, false, true)
    const rollback = await session(browser, input.source, journey.project === 'mobile')
    await assets(rollback.page, 'R0'); await login(rollback.page, input.accounts[journey.project].buyer)
    await order(rollback.page, journey.postCheckpointNumber!)
    await media(journey.fixture.seller, rollback.context, journey.fixture)
  }
  const sourceStopped = await completedCheckpoint(await infrastructure(input.source, 'Checkpoint'))
  expect(sourceStopped.reference.commandId).not.toBe(originalCheckpoint.reference.commandId)
  expect(sourceStopped.reference.sourceIdentity).toBe(originalCheckpoint.reference.sourceIdentity)
  expect(sourceStopped.reference.manifestSha256).not.toBe(originalCheckpoint.reference.manifestSha256)
  const sourceBeforeRestore = await observation<Snapshot>(input.source, '/snapshot')
  expect(sourceBeforeRestore.full).toEqual(sourceStopped.snapshot.full)
  const restored = await infrastructure(input.source, 'Restore', { checkpointRef: originalCheckpoint.reference,
    beforeSnapshotId: sourceStopped.snapshot.snapshotId })
  expect(restored).toMatchObject({ ready: true })
  await preservation(restored, originalCheckpoint.snapshot.snapshotId, originalCheckpoint, sourceStopped.snapshot)
  await stoppedSourceUnchanged(sourceBeforeRestore, await observation<Snapshot>(input.source, '/snapshot'))
  const pendingCredentials = input.source.credentials ?? input.credentials
  if (!pendingCredentials?.pendingEmail || !pendingCredentials.emailVerificationToken || !pendingCredentials.passwordResetEmail || !pendingCredentials.passwordResetToken)
    throw new Error('Actual source-issued unused verification/reset credential fixture required')
  const credentialProbe = await session(browser, input.restore)
  await operation('release: restored target rejects source issued unused email and reset tokens', async () => {
    const headers = await csrf(credentialProbe.context)
    const verification = await credentialProbe.context.request.post('/api/auth/verify-email', { headers, data: { token: pendingCredentials.emailVerificationToken } })
    expect(verification.status()).toBe(400)
    const reset = await credentialProbe.context.request.post('/api/auth/password/reset', { headers, data: { token: pendingCredentials.passwordResetToken, password: 'rejected old checkpoint reset password' } })
    expect(reset.status()).toBe(400)
    evidence('release-rejected-pending-credentials', { oldVerificationRejected: true, oldPasswordResetRejected: true, tokenValuesRecorded: false })
  })
  await reconciliation(browser, input.restore, 'restored', financialFixture)
  for (const journey of journeys) {
    const oldMember = await session(browser, input.restore), oldGuest = await session(browser, input.restore)
    await operation('release: restored target rejects old member guest and CSRF credentials', async () => {
      await oldMember.context.addCookies(await journey.member.context.cookies())
      await oldGuest.context.addCookies(await journey.guest.context.cookies())
      expect((await oldMember.context.request.get('/api/auth/me')).status()).toBe(401)
      expect((await oldMember.context.request.post('/api/auth/logout', { headers: journey.oldCsrf })).status()).toBe(403)
      expect((await oldGuest.context.request.get('/api/guest/access/references')).status()).toBe(401)
      await oldMember.page.goto('/account/orders/' + journey.memberNumber)
      await expect(oldMember.page.getByTestId('order-number')).toHaveCount(0)
      await oldGuest.page.goto('/guest/orders/login')
      await expect(oldGuest.page.getByRole('heading', { name: '연결된 주문', exact: true })).toHaveCount(0)
    })
    const freshMember = await session(browser, input.restore, journey.project === 'mobile')
    await login(freshMember.page, input.accounts[journey.project].buyer)
    await purchaseAmounts(freshMember.context, journey.memberNumber); await order(freshMember.page, journey.memberNumber)
    const freshGuest = await session(browser, input.restore, journey.project === 'mobile')
    await guestLogin(freshGuest.page, journey.guestNumber, journey.phone)
    await purchaseAmounts(freshGuest.context, journey.guestNumber); await order(freshGuest.page, journey.guestNumber, true)
    const restoredSeller = await session(browser, input.restore)
    await login(restoredSeller.page, input.accounts[journey.project].seller)
    await media(restoredSeller.context, freshMember.context, journey.fixture)
    const other = await session(browser, input.restore)
    await login(other.page, input.accounts[journey.project].other)
    await operation('release: restored readers cannot see post-checkpoint or foreign business IDs', async () => {
      expect((await freshMember.context.request.get('/api/orders/' + journey.postCheckpointNumber)).status()).toBe(404)
      expect((await freshGuest.context.request.get('/api/orders/' + journey.memberNumber)).status()).toBe(404)
      const anotherGuestNumber = journeys.find(candidate => candidate.project !== journey.project)!.guestNumber
      expect((await freshGuest.context.request.get('/api/orders/' + anotherGuestNumber)).status()).toBe(404)
      expect((await other.context.request.get('/api/orders/' + journey.memberNumber)).status()).toBe(404)
      expect((await other.context.request.get('/api/orders/' + journey.guestNumber)).status()).toBe(404)
    })
    await operation('release: fresh restore login logout and CSRF rotation preserve commerce', async () => {
      const before = await csrf(freshMember.context)
      expect((await freshMember.context.request.post('/api/auth/logout', { headers: before })).status()).toBe(200)
      expect((await freshMember.context.request.get('/api/auth/me')).status()).toBe(401)
      await login(freshMember.page, input.accounts[journey.project].buyer)
      const after = await csrf(freshMember.context)
      expect(after['X-CSRF-TOKEN']).not.toBe(before['X-CSRF-TOKEN'])
      expect((await freshMember.context.request.post('/api/auth/logout', { headers: before })).status()).toBe(403)
      expect((await read<{ orderNumber: string }>(freshMember.context, '/api/orders/' + journey.memberNumber)).orderNumber).toBe(journey.memberNumber)
    })
  }
  evidence('release-recovery-result', { runId: input.runId, mode: input.mode, originalCheckpoint,
    sourcePreservedAfterRestore: true, projects: journeys.map(journey => ({ project: journey.project, memberNumber: journey.memberNumber,
      guestNumber: journey.guestNumber, postCheckpointNumber: journey.postCheckpointNumber })) })
})
