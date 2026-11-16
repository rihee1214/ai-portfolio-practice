import { readFileSync } from 'node:fs'
import { expect, operation, type Browser } from './business-deadline'
import { csrf, digest, evidence, input, login, observation, read, session } from './release-helpers'
import type { Reconciliation, Stack } from './release-contract'
import type { OrderDetail, PaymentHistory } from '../src/order/OrderQueryStore'
import type { Refund } from '../src/payment/RefundStatusStore'

const money = (value: string) => BigInt(value).toLocaleString('ko-KR') + '원'
const requiredScenarios = ['MEMBER', 'TWO_SELLERS', 'PARTIAL_REFUND', 'RETURN_FEE',
  'CONFIRMED_EXCEPTION_REFUND', 'NEGATIVE_CARRYOVER', 'UNKNOWN_REFUND', 'FAILED_JOB']
export function reconciliationContract(): Reconciliation {
  const fixture = input.reconciliation
  if (!fixture || fixture.expectationSource !== 'approved-p09-p11-independent-calculation'
    || !fixture.producerEvidencePath || fixture.orders.length < 2 || fixture.settlements.length < 2 || !fixture.failedJobs.length
    || requiredScenarios.some(scenario => !fixture.scenarios.includes(scenario)))
    throw new Error('Mandatory D06 actual producer and independent original-unit reconciliation fixture missing')
  // Preserve the producer artifact identity, independently of UI/API calculated totals.
  const sha256 = digest(readFileSync(fixture.producerEvidencePath))
  evidence('release-reconciliation-producer', { expectationSource: fixture.expectationSource, sha256, scenarios: fixture.scenarios })
  return fixture
}
export async function reconciliation(browser: Browser, stack: Stack, phase: 'source' | 'restored', fixture: Reconciliation) {
  const admin = await session(browser, stack)
  await login(admin.page, input.accounts.desktop.admin)
  const outsider = await session(browser, stack)
  await login(outsider.page, input.accounts.desktop.other)
  for (const expected of fixture.orders) {
    const buyer = await session(browser, stack)
    await login(buyer.page, expected.buyerEmail)
    await operation('release: reconcile independent original-unit amounts and refund IDs in actual UI API', async () => {
      await buyer.page.goto('/account/orders/' + expected.orderNumber)
      await expect(buyer.page.getByTestId('order-number')).toHaveText(expected.orderNumber)
      await expect(buyer.page.getByTestId('order-total')).toHaveText('전체 주문 금액 ' + money(expected.total))
      const detail = await read<OrderDetail>(buyer.context, '/api/orders/' + expected.orderNumber)
      expect(detail).toMatchObject({ orderNumber: expected.orderNumber, total: expected.total, state: expected.state })
      const units = detail.stores.flatMap(store => store.items.flatMap(item => item.units.map(unit => ({ itemId: item.id, ...unit }))))
      const sortUnits = (rows: typeof units) => [...rows].sort((a, b) => (a.itemId + ':' + a.ordinal).localeCompare(b.itemId + ':' + b.ordinal))
      expect(sortUnits(units)).toEqual(sortUnits(expected.units))
      const history = await read<PaymentHistory>(buyer.context, '/api/payments/' + expected.orderNumber + '/history')
      expect(history).toMatchObject({ orderNumber: expected.orderNumber, amount: expected.total, paymentState: expected.paymentState })
      const refunds = await read<Refund[]>(buyer.context, '/api/orders/' + expected.orderNumber + '/refunds')
      const actual = refunds.map(({ attemptId, state, amount }) => ({ attemptId, state, amount })).sort((a, b) => a.attemptId.localeCompare(b.attemptId))
      expect(actual).toEqual([...expected.refunds].sort((a, b) => a.attemptId.localeCompare(b.attemptId)))
      for (const refund of expected.refunds) {
        const row = buyer.page.getByTestId('refund-status-' + refund.attemptId)
        await expect(row).toContainText(money(refund.amount))
        if (refund.state === 'SUCCEEDED') await expect(row).toContainText('제공자 환불 완료')
        if (refund.state === 'UNKNOWN') await expect(row).toContainText(/환불 확인 중|운영 확인 필요/)
        if (refund.state === 'FAILED') await expect(row).toContainText('환불 확정 실패')
      }
      expect((await outsider.context.request.get('/api/orders/' + expected.orderNumber)).status()).toBe(404)
      evidence('release-unit-reconciliation', { phase, orderNumber: expected.orderNumber, units, refunds: actual })
    })
  }
  for (const expected of fixture.settlements) {
    const seller = await session(browser, stack)
    await login(seller.page, expected.sellerEmail)
    await operation('release: seller own settlement UI preserves exact payout and carryover', async () => {
      await seller.page.goto('/seller/settlements')
      const statement = seller.page.getByRole('article').filter({ has: seller.page.getByRole('heading', { name: new RegExp('^' + expected.weekStart + ' · ') }) })
      await expect(statement).toContainText('판매자 주문 ' + expected.sellerOrderId)
      for (const value of [expected.net, expected.payout, expected.opening, expected.closing]) await expect(statement).toContainText(money(value))
      const weeks = await read<{ weekStart: string; net: string; payout: string; opening: string; closing: string; lines: { sellerOrderId: string }[] }[]>(seller.context, '/api/seller/settlements')
      const week = weeks.find(week => week.weekStart === expected.weekStart)
      expect(week).toMatchObject({ net: expected.net, payout: expected.payout, opening: expected.opening, closing: expected.closing })
      expect(week!.lines.some(line => line.sellerOrderId === expected.sellerOrderId)).toBe(true)
      expect((await outsider.context.request.get('/api/seller/settlements')).status()).toBe(403)
    })
    await operation('release: settlement operator UI traces same batch store and original seller order', async () => {
      await admin.page.goto('/admin/settlements')
      await admin.page.getByRole('button', { name: expected.weekStart + ' 정산 상세', exact: true }).click()
      const region = admin.page.getByRole('region', { name: '배치 상세', exact: true })
      await expect(region).toContainText('매대 ' + expected.storeId)
      await expect(region).toContainText(expected.sellerOrderId)
      const detail = await read<{ id: string; weekStart: string; items: { sellerOrderId: string }[]; balances: { storeId: string; net: string; opening: string; payout: string; closing: string }[] }>(admin.context, '/api/admin/settlements/batches/' + expected.batchId)
      expect(detail).toMatchObject({ id: expected.batchId, weekStart: expected.weekStart })
      expect(detail.items.some(item => item.sellerOrderId === expected.sellerOrderId)).toBe(true)
      expect(detail.balances.find(balance => balance.storeId === expected.storeId)).toEqual({ storeId: expected.storeId, net: expected.net, opening: expected.opening, payout: expected.payout, closing: expected.closing })
      expect((await seller.context.request.get('/api/admin/settlements/batches/' + expected.batchId)).status()).toBe(403)
      evidence('release-settlement-reconciliation', { phase, ...expected })
    })
  }
  for (const expected of fixture.failedJobs) {
    await operation('release: system operator reads preserved terminal failure and safe followup UI', async () => {
      await admin.page.goto('/admin/jobs')
      await admin.page.getByRole('button', { name: expected.type + ' · ' + expected.id, exact: true }).click()
      await expect(admin.page.getByRole('heading', { name: '작업 상세', exact: true })).toBeVisible()
      await expect(admin.page.getByText(expected.id + ' · ' + expected.type + ' · FAILED', { exact: true })).toBeVisible()
      await expect(admin.page.getByText('오류 코드 ' + expected.error, { exact: true })).toBeVisible()
      const job = await read(admin.context, '/api/admin/system/site-jobs/' + expected.id)
      expect(job).toMatchObject(expected)
      expect((await outsider.context.request.get('/api/admin/system/site-jobs/' + expected.id)).status()).toBe(403)
      evidence('release-failed-job-reconciliation', { phase, ...expected })
    })
    if (phase === 'restored') {
      if (expected.type !== 'NOTIFICATION_DELIVER.v1') throw new Error('Mandatory restored notification safe-retry fixture required')
      await operation('release: actual restored notification retry preserves one effect and one operator audit', async () => {
        await admin.page.goto('/admin/notifications')
        await admin.page.getByLabel('재처리 사유', { exact: true }).fill('격리 복원 후 원 실패 작업의 안전한 알림 재처리')
        const pending = admin.page.waitForResponse(response => /\/api\/admin\/system\/notifications\/[0-9a-f-]{36}\/retry$/.test(new URL(response.url()).pathname)
          && response.request().method() === 'POST')
        const row = admin.page.getByRole('listitem').filter({ hasText: '실패 작업 ' + expected.id })
        await expect(row).toHaveCount(1)
        await row.getByRole('button', { name: '알림 재처리', exact: true }).click()
        const response = await pending
        expect(response.status()).toBe(200)
        const body = response.request().postDataJSON() as { key: string; failedJob: string; reason: string }
        const endpoint = new URL(response.url()).pathname
        expect(body.failedJob).toBe(expected.id)
        expect(body.key).toMatch(/^[0-9a-f-]{36}$/)
        const { job } = await response.json() as { job: string }
        expect(job).not.toBe(expected.id)
        await expect(admin.page.getByText('알림 재처리를 요청했습니다.', { exact: true })).toBeVisible()
        const replay = await admin.context.request.post(endpoint, { headers: await csrf(admin.context), data: body })
        expect(replay.status()).toBe(200); expect(await replay.json()).toEqual({ job })
        expect((await admin.context.request.post(endpoint, { headers: await csrf(admin.context), data: { ...body, reason: '같은 요청 키의 다른 사유' } })).status()).toBe(409)
        expect((await outsider.context.request.post(endpoint, { headers: await csrf(outsider.context), data: body })).status()).toBe(403)
        await expect.poll(async () => (await read<{ status: string }>(admin.context, '/api/admin/system/site-jobs/' + job)).status).toBe('SUCCEEDED')
        expect(await read(admin.context, '/api/admin/system/site-jobs/' + expected.id)).toMatchObject({ status: 'FAILED', attempts: 5 })
        const proof = await observation<{ auditRows: number; rows: { previous_job: string; job_id: string; status: string; inbox_rows: number }[] }>(stack, '/notification-retry?failedJob=' + expected.id)
        expect(proof.auditRows).toBe(1)
        expect(proof.rows).toEqual([expect.objectContaining({ previous_job: expected.id, job_id: job, status: 'SUCCEEDED', inbox_rows: 1 })])
        evidence('release-restored-notification-retry', { previousJob: expected.id, job, commandKey: body.key, sameKeyReplay: true, differentInputRejected: true, unauthorizedRejected: true, proof })
      })
    }
  }
}
