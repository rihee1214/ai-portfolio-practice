import { test, expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const password = 'p3-browser-member-password'
async function token(page: Page) { return (await (await page.request.get('/api/auth/csrf')).json()).token as string }
async function login(page: Page, email: string, secret: string) {
  await page.goto('/login')
  await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled()
  await page.getByLabel('이메일', { exact: true }).fill(email)
  await page.getByLabel('비밀번호', { exact: true }).fill(secret)
  await page.getByLabel('비밀번호', { exact: true }).press('Enter')
  await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
}
test('모의 판매 신청·반려·재신청·승인과 구매자 자격 유지', async ({ page, browser }, info) => {
  const email = 'p3-browser-' + randomUUID() + '@example.com'
  const headers = { 'X-CSRF-TOKEN': await token(page) }
  expect((await page.request.post('/api/auth/signup', { headers, data: { email, password } })).status()).toBe(201)
  const mail = await page.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } })
  expect((await page.request.post('/api/auth/verify-email', { headers, data: { token: (await mail.json()).token } })).status()).toBe(200)
  await login(page, email, password)
  await page.getByRole('link', { name: '판매 신청·이력' }).click()
  const name = '모의 매대 ' + randomUUID().slice(0, 8)
  async function apply() {
    await expect(page.getByLabel('모의 매대 이름')).toBeEnabled()
    await page.getByLabel('모의 매대 이름').fill(name)
    await page.getByLabel('판매 계획').fill('가상 상품만 판매하는 모의 계획')
    await page.getByRole('button', { name: '판매 신청하기', exact: true }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText('처리했습니다.', { exact: true })).toBeVisible()
  }
  await apply()
  await page.reload()
  await expect(page.getByRole('article', { name }).getByText('심사 대기', { exact: true })).toBeVisible()
  const operatorContext = await browser.newContext({ viewport: page.viewportSize() ?? { width: 1280, height: 720 } })
  const operator = await operatorContext.newPage()
  try {
    await login(operator, 'p3-admin-' + info.project.name + '@example.com', ' A1-private-passphrase-가나다 ')
    await operator.goto('/admin/sellers')
    let card = operator.getByRole('article', { name }).filter({ has: operator.getByRole('button', { name: '심사 결과 저장' }) })
    await expect(card).toBeVisible()
    await card.getByLabel('심사 결과').selectOption('REJECTED')
    await card.getByLabel('심사 사유').fill('모의 계획 보완')
    await card.getByRole('button', { name: '심사 결과 저장' }).click()
    await expect(operator.getByText('처리했습니다.', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '목록 새로고침' }).click()
    await expect(page.getByRole('article', { name }).getByText('반려', { exact: true })).toBeVisible()
    await apply()
    await operator.getByRole('button', { name: '목록 새로고침' }).click()
    card = operator.getByRole('article', { name }).filter({ has: operator.getByRole('button', { name: '심사 결과 저장' }) })
    await card.getByLabel('심사 결과').selectOption('APPROVED')
    await card.getByLabel('심사 사유').fill('모의 승인 완료')
    await card.getByRole('button', { name: '심사 결과 저장' }).click()
    await expect(operator.getByText('처리했습니다.', { exact: true })).toBeVisible()
    await operator.reload()
    await expect(operator.getByRole('article', { name }).getByText('승인', { exact: true })).toBeVisible()
    expect(await operator.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await operator.screenshot({ path: info.outputPath('seller-review.png') })
    await page.reload()
    await expect(page.getByText(/판매자 자격이 있습니다/)).toBeVisible()
    expect((await (await page.request.get('/api/auth/me')).json()).roles.sort()).toEqual(['BUYER', 'SELLER'])
    await page.goto('/seller/products')
    await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible()
    expect((await page.request.get('/api/account/profile')).status()).toBe(200)
  } finally { await operatorContext.close() }
})
