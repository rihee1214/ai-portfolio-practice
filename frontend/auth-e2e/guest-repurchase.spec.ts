import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { request as httpRequest } from 'node:http'
const password = 'existing group password'
async function csrf(context: BrowserContext) { const value = await (await context.request.get('/api/auth/csrf')).json(); return { [value.headerName]: value.token } }
// Real Java login from a dedicated loopback client keeps the suite's aggregate source quota isolated.
// Cookies, CSRF, body and server response stay intact; no rate-limit configuration is changed.
async function isolateLoginSource(page: Page) {
  await page.route('**/api/guest/access/login', async route => {
    const originalHeaders = await route.request().allHeaders()
    expect(Object.hasOwn(originalHeaders, 'cookie')).toBe(true)
    expect(Object.hasOwn(originalHeaders, 'x-csrf-token')).toBe(true)
    const result = await new Promise<{ status: number; headers: Record<string, string>; body: Buffer }>((resolve, reject) => {
      const request = httpRequest({ hostname: '127.0.0.1', port: process.env.A3_API_PORT, localAddress: '127.0.0.3', path: '/api/guest/access/login', method: 'POST', headers: { ...originalHeaders, host: '127.0.0.1:' + process.env.A3_API_PORT } }, response => {
        const chunks: Buffer[] = []; response.on('data', chunk => chunks.push(Buffer.from(chunk))); response.on('error', reject)
        response.on('end', () => {
          const headers: Record<string, string> = {}
          for (const [name, value] of Object.entries(response.headers)) if (value !== undefined && name !== 'transfer-encoding') headers[name] = Array.isArray(value) ? value.join('\n') : value
          resolve({ status: response.statusCode ?? 500, headers, body: Buffer.concat(chunks) })
        })
      }); request.on('error', reject); request.end(route.request().postData() ?? '')
    })
    await route.fulfill(result)
  })
}
async function login(page: Page, orderNumber: string, phone: string, secret: string) {
  await page.goto('/guest/orders/login'); await page.getByLabel('주문번호', { exact: true }).fill(orderNumber); await page.getByLabel('등록 전화번호').fill(phone); await page.getByLabel('조회 비밀번호', { exact: true }).fill(secret)
  const response = page.waitForResponse('**/api/guest/access/login'); await page.getByRole('button', { name: '주문 조회 인증' }).click(); expect((await response).status()).toBe(200); await expect(page.getByRole('heading', { name: '연결된 주문' })).toBeVisible()
}
async function historical(page: Page, context: BrowserContext, phone: string) {
  await isolateLoginSource(page)
  await page.goto('/guest/orders/login'); const orderNumber = 'E-' + crypto.randomUUID()
  const response = await context.request.post(`http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-recovery-order`, { headers: await csrf(context), data: { phone, password, orderNumber } })
  expect(response.status()).toBe(200)
  await login(page, orderNumber, phone, password)
  const old = (await context.cookies()).find(row => row.name === 'GUEST_ACCESS')!.value
  const headers = await csrf(context)
  const status = await page.evaluate(async ({ headers, orderNumber, phone, password }) => (await fetch('/api/guest/access/login', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ orderNumber, phone, password }) })).status, { headers, orderNumber, phone, password })
  expect(status).toBe(200)
  const current = (await context.cookies()).find(row => row.name === 'GUEST_ACCESS')!.value
  expect(current === old).toBe(false)
  expect((await context.request.get('/api/guest/orders', { headers: { Cookie: 'GUEST_ACCESS=' + old } })).status()).toBe(401)
  expect((await context.request.get('/api/guest/orders')).status()).toBe(200)
  return orderNumber
}
async function purchase(page: Page, phone: string) {
  await page.goto('/checkout/guest'); await page.getByLabel('구매자 이름').fill('재구매 가상'); await page.getByLabel('전화번호', { exact: true }).fill(phone); await page.getByRole('button', { name: '모의 인증번호 요청', exact: true }).click(); await expect(page.getByLabel('인증번호', { exact: true })).toBeVisible()
  const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT
  if (!host || !project?.startsWith('demo-')) throw new Error('Fresh emulator required')
  const response = await (await fetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as { verificationCodes: { phoneNumber: string; code: string }[] }
  const otp = response.verificationCodes.find(row => row.phoneNumber === phone)?.code; expect(otp).toMatch(/^\d{6}$/)
  await page.getByLabel('인증번호', { exact: true }).fill(otp!); await page.getByRole('button', { name: '인증 확인', exact: true }).click(); await expect(page.getByRole('heading', { name: '주문 묶음 선택' })).toBeVisible()
}
async function attach(context: BrowserContext, input?: { attemptId: string; orderNumber: string; loseResponse: boolean }) {
  const state = input ?? await (await context.request.get('/api/guest/checkout')).json()
  return context.request.post(`http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-linked-order`, { headers: await csrf(context), data: input ?? { attemptId: state.attemptId, orderNumber: null, loseResponse: false } })
}
test('재구매 연결은 조회 인증과 현재 전화 인증을 요구하고 응답 유실·다른 탭 충돌을 복구한다', async ({ page, context }, info) => {
  const phone = info.project.name === 'desktop' ? '+821065000001' : '+821065000002'
  const old = await historical(page, context, phone); await purchase(page, phone)
  const tab = await context.newPage(); await tab.goto('/checkout/guest'); await expect(tab.getByRole('heading', { name: '주문 묶음 선택' })).toBeVisible()
  await page.route('**/api/guest/checkout/link', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') })
  await page.getByRole('button', { name: '기존 묶음 연결', exact: true }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toHaveCount(0)
  await page.unroute('**/api/guest/checkout/link'); await page.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toBeVisible(); await expect(page.getByLabel('조회 비밀번호', { exact: true })).toHaveCount(0)
  await tab.getByRole('button', { name: '새 묶음 선택' }).click(); await expect(tab.getByRole('alert')).toBeVisible(); await tab.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(tab.getByText(/기존 묶음 연결 준비 완료/)).toBeVisible(); await tab.close()
  await page.route('**/api/guest/checkout/link/*', route => route.abort('failed'))
  await page.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(page.getByRole('alert')).toContainText('서버 준비 상태'); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toHaveCount(0); await expect(page.getByLabel('조회 비밀번호', { exact: true })).toHaveCount(0)
  await page.unroute('**/api/guest/checkout/link/*'); await page.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toBeVisible()
  const state = await (await context.request.get('/api/guest/checkout')).json(), value = { orderNumber: 'E-LOST-' + crypto.randomUUID() }
  const input = { attemptId: state.attemptId, orderNumber: value.orderNumber, loseResponse: true }
  expect((await attach(context, input)).status()).toBe(503); expect((await attach(context, input)).status()).toBe(409)
  const orders = await (await context.request.get('/api/guest/orders')).json(); expect(orders.orders.map((row: { orderNumber: string }) => row.orderNumber).sort()).toEqual([old, value.orderNumber].sort())
  await page.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toHaveCount(0)
  await context.request.post('/api/guest/checkout', { headers: await csrf(context), data: {} }); await page.reload(); await expect(page.getByLabel('구매자 이름')).toBeVisible()
  expect(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))).not.toContain(password)
})
test('같은 번호의 새 구매도 별도 묶음이며 조회 로그아웃 뒤 기존 연결을 사용할 수 없다', async ({ page, context }, info) => {
  const phone = info.project.name === 'desktop' ? '+821065000011' : '+821065000012'
  const old = await historical(page, context, phone); await purchase(page, phone)
  await page.getByRole('button', { name: '기존 묶음 연결', exact: true }).click(); await expect(page.getByText(/기존 묶음 연결 준비 완료/)).toBeVisible()
  expect((await context.request.post('/api/guest/access/logout', { headers: await csrf(context), data: {} })).status()).toBe(200)
  await page.getByRole('button', { name: '서버 준비 상태 다시 확인' }).click(); await expect(page.getByText(/기존 묶음 연결 자격을 다시/)).toBeVisible(); expect((await attach(context)).status()).toBe(401)
  await page.getByRole('button', { name: '새 묶음 선택' }).click(); await page.getByLabel('조회 비밀번호', { exact: true }).fill('separate group password'); await page.getByLabel('조회 비밀번호 확인').fill('separate group password'); await page.getByRole('button', { name: '조회 비밀번호 준비', exact: true }).click(); await expect(page.getByText(/조회 비밀번호 준비 완료/)).toBeVisible()
  const state = await (await context.request.get('/api/guest/checkout')).json(), value = { orderNumber: 'E-NEW-LOST-' + crypto.randomUUID() }
  const input = { attemptId: state.attemptId, orderNumber: value.orderNumber, loseResponse: true }
  expect((await attach(context, input)).status()).toBe(503); expect((await attach(context, input)).status()).toBe(409)
  await login(page, value.orderNumber, phone, 'separate group password')
  const orders = await (await context.request.get('/api/guest/orders')).json(); expect(orders.orders).toEqual([{ orderNumber: value.orderNumber }]); expect((await context.request.get('/api/guest/orders/' + old)).status()).toBe(404)
})
test('등록 번호와 다른 구매 번호는 기존 묶음 연결을 거부하고 새 묶음으로 안내한다', async ({ page, context }, info) => {
  const phone = info.project.name === 'desktop' ? '+821065000021' : '+821065000022', other = info.project.name === 'desktop' ? '+821065000031' : '+821065000032'
  const old = await historical(page, context, phone); await purchase(page, other)
  await page.getByRole('button', { name: '기존 묶음 연결', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('같은 번호'); await expect(page.getByText(/번호가 다르면 새 묶음/)).toBeVisible()
  await page.getByRole('button', { name: '새 묶음 선택' }).click(); await page.getByLabel('조회 비밀번호', { exact: true }).fill('different number password'); await page.getByLabel('조회 비밀번호 확인').fill('different number password'); await page.getByRole('button', { name: '조회 비밀번호 준비', exact: true }).click(); await expect(page.getByText(/조회 비밀번호 준비 완료/)).toBeVisible()
  const created = await attach(context); expect(created.status()).toBe(200); const value = await created.json()
  const orders = await (await context.request.get('/api/guest/orders')).json(); expect(orders.orders).toEqual([{ orderNumber: old }]); expect((await context.request.get('/api/guest/orders/' + value.orderNumber)).status()).toBe(404)
})
