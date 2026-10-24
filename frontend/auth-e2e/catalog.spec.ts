import { test, expect, type Page } from '@playwright/test'
import { resolve } from 'node:path'

async function login(page: Page, email: string) {
  await page.goto('/login')
  await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled()
  await page.getByLabel('이메일', { exact: true }).fill(email)
  await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
}
test('카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다', async ({ page, browser }, info) => {
  test.setTimeout(60000)
  page.setDefaultTimeout(10000)
  const suffix = info.project.name
  const category = 'P04 browser ' + suffix
  const title = 'P04 상품 ' + suffix
  const slug = 'p04-browser-product-' + suffix
  await login(page, 'c-admin-' + suffix + '@example.com')
  await page.goto('/admin/catalog')
  await page.getByLabel('카테고리 이름').fill(category)
  await page.getByRole('button', { name: '카테고리 등록', exact: true }).click()
  await expect(page.getByRole('button', { name: category + ' · 활성', exact: true })).toBeVisible()
  const sellerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:24176', viewport: page.viewportSize() ?? undefined })
  const seller = await sellerContext.newPage()
  seller.setDefaultTimeout(10000)
  try {
    await login(seller, 'p4-seller-' + suffix + '@example.com')
    await seller.goto('/seller/products')
    await seller.getByLabel('상품 주소').fill(slug)
    await seller.getByLabel('상품명', { exact: true }).fill(title)
    await seller.getByLabel('설명', { exact: true }).fill('브라우저에서 등록한 상품\n둘째 줄 설명')
    await seller.getByLabel('카테고리', { exact: true }).selectOption({ label: category })
    await seller.getByRole('button', { name: '상품 등록', exact: true }).click()
    await seller.getByRole('button', { name: title + ' · 초안', exact: true }).click()
    await expect(seller.getByLabel('설명', { exact: true })).toHaveValue('브라우저에서 등록한 상품\n둘째 줄 설명')
    await seller.getByLabel('상품 상태').selectOption('ACTIVE')
    await seller.getByRole('button', { name: '상품 저장', exact: true }).click()
    await expect(seller.getByRole('button', { name: title + ' · 공개 등록', exact: true })).toBeVisible()
    expect((await seller.request.get('/api/products/' + slug)).status()).toBe(200)
    await page.getByRole('button', { name: category + ' · 활성', exact: true }).click()
    await page.getByLabel('활성 카테고리').uncheck()
    await page.getByRole('button', { name: '카테고리 저장', exact: true }).click()
    await expect(page.getByRole('button', { name: category + ' · 비활성', exact: true })).toBeVisible()
    expect((await seller.request.get('/api/products/' + slug)).status()).toBe(404)
    await page.getByLabel('활성 카테고리').check()
    await page.getByRole('button', { name: '카테고리 저장', exact: true }).click()
    await expect(page.getByRole('button', { name: category + ' · 활성', exact: true })).toBeVisible()
    expect((await seller.request.get('/api/products/' + slug)).status()).toBe(200)
    await seller.getByRole('button', { name: title + ' · 공개 등록', exact: true }).click()
    await seller.getByLabel('상품 상태').selectOption('STOPPED')
    await seller.getByRole('button', { name: '상품 저장', exact: true }).click()
    await expect(seller.getByRole('button', { name: title + ' · 판매 중지', exact: true })).toBeVisible()
    expect((await seller.request.get('/api/products/' + slug)).status()).toBe(404)
    await seller.getByLabel('상품 상태').selectOption('ARCHIVED')
    await seller.getByRole('button', { name: '상품 저장', exact: true }).click()
    await expect(seller.getByRole('alert')).toHaveText('보관 후 복구할 수 없음을 확인해 주세요.')
    await seller.getByLabel('보관 후 복구할 수 없음을 확인합니다').check()
    await seller.getByRole('button', { name: '상품 저장', exact: true }).click()
    await expect(seller.getByText('보관된 상품입니다. 기록은 유지됩니다.')).toBeVisible()
    await expect(seller.getByRole('button', { name: '상품 저장', exact: true })).toBeDisabled()
    await seller.reload()
    await seller.getByRole('button', { name: title + ' · 보관', exact: true }).click()
    await expect(seller.getByText('보관된 상품입니다. 기록은 유지됩니다.')).toBeVisible()
    for (const width of [375, 768, 1440]) {
      await seller.setViewportSize({ width, height: 900 })
      expect(await seller.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
    await seller.setViewportSize({ width: suffix === 'mobile' ? 375 : 1440, height: 1000 })
    await seller.locator('.store-card').screenshot({ path: resolve(process.env.A3_EVIDENCE!, 'p04-product-' + suffix + '.png') })
    await page.locator('.store-card').screenshot({ path: resolve(process.env.A3_EVIDENCE!, 'p04-category-' + suffix + '.png') })
  } catch (error) {
    await seller.screenshot({ path: resolve(process.env.A3_EVIDENCE!, 'p04-seller-failure-' + suffix + '.png') }).catch(() => {})
    throw error
  } finally { await sellerContext.close().catch(() => {}) }
})
