import { boundedFetch, operation, prepare, expect, type Page } from './business-deadline'
import { input, read } from './release-helpers'

export const guestPassword = 'p12d isolated guest lookup secret'
export async function prepareGuest(page: Page, project: 'desktop' | 'mobile') {
  if (input.mode !== 'L' || !input.source.guestBaseURL) throw new Error('Explicit L development/test phone UI origin required; production Firebase guard is preserved')
  const phone = project === 'desktop' ? '+821069160001' : '+821069160002'
  const code = await operation('release: issue actual local guest phone challenge from UI', async () => {
    await page.goto('/checkout/guest')
    await page.getByLabel('구매자 이름', { exact: true }).fill('복구 비회원')
    await page.getByLabel('전화번호', { exact: true }).fill(phone)
    await page.getByRole('button', { name: '모의 인증번호 요청', exact: true }).click()
    await expect(page.getByLabel('인증번호', { exact: true })).toBeVisible()
    const response = await boundedFetch('http://' + input.emulator!.host + '/emulator/v1/projects/' + input.emulator!.project + '/verificationCodes')
    expect(response.status).toBe(200)
    const codes = await response.json() as { verificationCodes: { phoneNumber: string; code: string }[] }
    const code = codes.verificationCodes.find(item => item.phoneNumber === phone)?.code
    expect(code).toMatch(/^\d{6}$/); return code!
  })
  await prepare('release: enter current local guest challenge', async () => { await page.getByLabel('인증번호', { exact: true }).fill(code) })
  await operation('release: verify actual guest challenge and prepare lookup password', async () => {
    await page.getByRole('button', { name: '인증 확인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '조회 비밀번호 설정', exact: true })).toBeVisible()
    await page.getByLabel('조회 비밀번호', { exact: true }).fill(guestPassword)
    await page.getByLabel('조회 비밀번호 확인', { exact: true }).fill(guestPassword)
    await page.getByRole('button', { name: '조회 비밀번호 준비', exact: true }).click()
    await expect(page.getByText(/조회 비밀번호 준비 완료/)).toBeVisible()
  })
  return phone
}
export async function guestLogin(page: Page, number: string, phone: string) {
  return operation('release: new guest lookup login consumes preserved original business ID', async () => {
    await page.goto('/guest/orders/login')
    await page.getByLabel('주문번호', { exact: true }).fill(number)
    await page.getByLabel('등록 전화번호', { exact: true }).fill(phone)
    await page.getByLabel('조회 비밀번호', { exact: true }).fill(guestPassword)
    const authenticated = page.waitForResponse(response => new URL(response.url()).pathname === '/api/guest/access/login' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '주문 조회 인증', exact: true }).click()
    expect((await authenticated).status()).toBe(200)
    await expect(page.getByRole('heading', { name: '연결된 주문', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: number, exact: true })).toBeVisible()
    const lookup = (await page.context().cookies()).find(cookie => cookie.name === 'GUEST_ACCESS')
    expect(lookup && { secure: lookup.secure, httpOnly: lookup.httpOnly, sameSite: lookup.sameSite, path: lookup.path }).toEqual({ secure: true, httpOnly: true, sameSite: 'Lax', path: '/' })
    const references = await read<{ orders: { orderNumber: string }[] }>(page.context(), '/api/guest/access/references')
    expect(references.orders.map(row => row.orderNumber)).toEqual([number])
  })
}
