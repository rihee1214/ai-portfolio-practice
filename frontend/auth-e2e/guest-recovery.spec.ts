import { test, expect } from '@playwright/test'
test('복구 코드와 별도 전화 재인증으로 비밀번호를 바꾸고 이전 세션을 폐기한다', async ({ page, context, browser }, info) => {
  const phone = info.project.name === 'desktop' ? '+821064000001' : '+821064000002'
  const password = 'guest recovery initial password', next = 'guest recovery replacement password'
  const otp = async () => {
    const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT
    if (!host || !project?.startsWith('demo-')) throw new Error('Fresh emulator required')
    const result = await (await fetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as { verificationCodes: { phoneNumber: string; code: string }[] }
    const value = result.verificationCodes.find(row => row.phoneNumber === phone)?.code
    expect(value).toMatch(/^\d{6}$/); return value!
  }
  // An explicitly historical test-only order avoids reusing a just-issued purchase phone proof.
  await page.goto('/guest/orders/login')
  const csrf = await (await context.request.get('/api/auth/csrf')).json()
  const orderNumber = 'D-' + crypto.randomUUID()
  const created = await context.request.post(`http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-recovery-order`, { data: { phone, password, orderNumber }, headers: { [csrf.headerName]: csrf.token } })
  expect(created.status()).toBe(200); const { recoveryCode } = await created.json()
  await page.goto('/guest/orders/login'); await page.getByLabel('주문번호', { exact: true }).fill(orderNumber); await page.getByLabel('등록 전화번호').fill(phone); await page.getByLabel('조회 비밀번호', { exact: true }).fill(password); await page.getByRole('button', { name: '주문 조회 인증' }).click()
  await expect(page.getByRole('heading', { name: '연결된 주문' })).toBeVisible()
  const old = (await context.cookies()).find(row => row.name === 'GUEST_ACCESS')!.value
  const separate = await browser.newContext({ baseURL: new URL(page.url()).origin })
  try {
    const recovery = await separate.newPage(); await recovery.goto('/guest/recovery')
    await expect(recovery.getByText(/복구 코드도 잃었다면 지원 문의/)).toBeVisible()
    expect((await separate.request.post('/api/guest/recovery/start', { data: { orderNumber, phone, recoveryCode } })).status()).toBe(403)
    await recovery.getByLabel('주문번호', { exact: true }).fill(orderNumber); await recovery.getByLabel('등록 전화번호').fill(phone); await recovery.getByLabel('복구 코드', { exact: true }).fill(recoveryCode)
    await recovery.getByRole('button', { name: '복구용 모의 인증 요청' }).click(); await expect(recovery.getByLabel('인증번호', { exact: true })).toBeVisible()
    await recovery.getByLabel('인증번호', { exact: true }).fill(await otp()); await recovery.getByRole('button', { name: '복구 인증 확인' }).click()
    await expect(recovery.getByLabel('새 조회 비밀번호', { exact: true })).toBeVisible()
    await recovery.getByLabel('새 조회 비밀번호', { exact: true }).fill(next); await recovery.getByLabel('새 조회 비밀번호 확인').fill(next)
    await recovery.route('**/api/guest/recovery/reset', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') })
    await recovery.getByRole('button', { name: '조회 비밀번호 재설정' }).click(); await expect(recovery.getByRole('alert')).toBeVisible()
    await expect(recovery.getByLabel('새 조회 비밀번호', { exact: true })).toHaveValue('')
    expect((await context.request.get('/api/guest/access/references', { headers: { Cookie: 'GUEST_ACCESS=' + old } })).status()).toBe(401)
    await recovery.unroute('**/api/guest/recovery/reset'); await recovery.goto('/guest/orders/login')
    await recovery.getByLabel('주문번호', { exact: true }).fill(orderNumber); await recovery.getByLabel('등록 전화번호').fill(phone); await recovery.getByLabel('조회 비밀번호', { exact: true }).fill(next); await recovery.getByRole('button', { name: '주문 조회 인증' }).click()
    await expect(recovery.getByRole('heading', { name: '연결된 주문' })).toBeVisible()
    await recovery.getByRole('link', { name: '비밀번호 변경·복구 코드 관리' }).click(); await recovery.getByLabel('현재 조회 비밀번호').fill(next); await recovery.getByRole('button', { name: '복구 코드 재발급' }).click()
    const issued = recovery.getByLabel('새 복구 코드', { exact: true }); await expect(issued).toHaveValue(/^[A-Za-z0-9_-]{43}$/)
    const newCode = await issued.inputValue(); expect(newCode).not.toBe(recoveryCode)
    const stored = await recovery.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))
    for (const secret of [password, next, recoveryCode, newCode]) { expect(stored).not.toContain(secret); expect(recovery.url()).not.toContain(secret) }
    await recovery.getByRole('button', { name: '복구 코드 숨기기' }).click(); await expect(issued).toHaveCount(0)
    await recovery.reload(); await expect(recovery.getByLabel('새 복구 코드', { exact: true })).toHaveCount(0)
  } finally { await separate.close() }
})
test('최초 코드·재발급·변경 응답 유실은 현재 비밀번호로 로그인하여 복구한다', async ({ page, context }, info) => {
  const phone = info.project.name === 'desktop' ? '+821064000011' : '+821064000012'
  const password = 'initial management password', next = 'new management password', orderNumber = 'D-' + crypto.randomUUID()
  await page.goto('/guest/orders/login'); const csrf = await (await context.request.get('/api/auth/csrf')).json()
  const created = await context.request.post(`http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-recovery-order`, { data: { phone, password, orderNumber }, headers: { [csrf.headerName]: csrf.token } })
  expect(created.status()).toBe(200)
  // Deliberately discard the committed first-order body, including its one-time code.
  const login = async (secret: string) => {
    await page.goto('/guest/orders/login'); await page.getByLabel('주문번호', { exact: true }).fill(orderNumber); await page.getByLabel('등록 전화번호').fill(phone); await page.getByLabel('조회 비밀번호', { exact: true }).fill(secret); await page.getByRole('button', { name: '주문 조회 인증' }).click(); await expect(page.getByRole('heading', { name: '연결된 주문' })).toBeVisible()
    await page.getByRole('link', { name: '비밀번호 변경·복구 코드 관리' }).click()
  }
  await login(password)
  await page.route('**/api/guest/access/recovery-code', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') })
  await page.getByLabel('현재 조회 비밀번호').fill(password); await page.getByRole('button', { name: '복구 코드 재발급' }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByLabel('현재 조회 비밀번호')).toHaveValue('')
  await page.unroute('**/api/guest/access/recovery-code')
  await page.getByLabel('현재 조회 비밀번호').fill(password); await page.getByRole('button', { name: '복구 코드 재발급' }).click(); await expect(page.getByLabel('새 복구 코드', { exact: true })).toHaveValue(/^[A-Za-z0-9_-]{43}$/)
  const oldCode = await page.getByLabel('새 복구 코드', { exact: true }).inputValue()
  await page.reload(); await page.getByLabel('현재 조회 비밀번호').fill(password); await page.getByLabel('새 조회 비밀번호', { exact: true }).fill(next); await page.getByLabel('새 조회 비밀번호 확인').fill(next)
  await page.route('**/api/guest/access/password', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') })
  await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByLabel('새 조회 비밀번호', { exact: true })).toHaveValue('')
  expect((await context.request.get('/api/guest/access/references')).status()).toBe(401); await page.unroute('**/api/guest/access/password')
  await login(next); await page.getByLabel('현재 조회 비밀번호').fill(next); await page.getByRole('button', { name: '복구 코드 재발급' }).click(); await expect(page.getByLabel('새 복구 코드', { exact: true })).toHaveValue(/^[A-Za-z0-9_-]{43}$/)
  expect(await page.getByLabel('새 복구 코드', { exact: true }).inputValue()).not.toBe(oldCode)
  const newCsrf = await (await context.request.get('/api/auth/csrf')).json()
  expect((await context.request.post('/api/guest/recovery/start', { data: { orderNumber, phone, recoveryCode: oldCode }, headers: { [newCsrf.headerName]: newCsrf.token } })).status()).toBe(401)
})
test('재설정 권한 응답을 잃으면 성공을 표시하지 않고 새 인증의 재요청 제한을 안내한다', async ({ page, context }, info) => {
  const phone = info.project.name === 'desktop' ? '+821064000021' : '+821064000022'
  const orderNumber = 'D-' + crypto.randomUUID(), password = 'lost grant password D'
  await page.goto('/guest/recovery'); const csrf = await (await context.request.get('/api/auth/csrf')).json()
  const created = await context.request.post(`http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-recovery-order`, { data: { phone, password, orderNumber }, headers: { [csrf.headerName]: csrf.token } })
  expect(created.status()).toBe(200); const { recoveryCode } = await created.json()
  const start = async () => { await page.getByLabel('주문번호', { exact: true }).fill(orderNumber); await page.getByLabel('등록 전화번호').fill(phone); await page.getByLabel('복구 코드', { exact: true }).fill(recoveryCode); await page.getByRole('button', { name: '복구용 모의 인증 요청' }).click() }
  await start(); await expect(page.getByLabel('인증번호', { exact: true })).toBeVisible()
  const data = await (await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/emulator/v1/projects/${process.env.GCLOUD_PROJECT}/verificationCodes`)).json() as { verificationCodes: { phoneNumber: string; code: string }[] }
  const code = data.verificationCodes.find(row => row.phoneNumber === phone)?.code; expect(code).toMatch(/^\d{6}$/)
  await page.route('**/api/guest/recovery/*/verify', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') })
  await page.getByLabel('인증번호', { exact: true }).fill(code!); await page.getByRole('button', { name: '복구 인증 확인' }).click(); await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByLabel('새 조회 비밀번호', { exact: true })).toHaveCount(0); await page.unroute('**/api/guest/recovery/*/verify')
  await page.getByRole('link', { name: '복구를 처음부터 시작' }).click(); await start(); await expect(page.getByRole('alert')).toContainText('60초'); await expect(page.getByRole('alert')).toContainText('15분'); await expect(page.getByLabel('인증번호', { exact: true })).toHaveCount(0)
})

