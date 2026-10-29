import { test, expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { openManagedStore } from './managed-store'

async function login(page: Page, email: string) {
  await page.goto('/login')
  await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled()
  await page.getByLabel('이메일', { exact: true }).fill(email)
  await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ')
  await page.getByLabel('비밀번호', { exact: true }).press('Enter')
  await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
}
test('판매 정지·복구·폐점은 구매 권한을 유지하고 기능과 공개 화면을 제한한다', async ({ page, browser }, info) => {
  await login(page, 'p3c-seller-' + info.project.name + '@example.com')
  await page.goto('/seller/store')
  const slug = 'lifecycle-' + randomUUID().slice(0, 8)
  await page.getByLabel('매대 URL 주소').fill(slug)
  await page.getByLabel('매대 이름', { exact: true }).fill('운영 검증 매대')
  await page.getByLabel('매대 소개', { exact: true }).fill('가상 소개')
  await page.getByLabel('공개용 주소').fill('가상 주소')
  await page.getByLabel('매대 공개', { exact: true }).check()
  await page.getByRole('button', { name: '매대 만들기' }).click()
  await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible()
  const context = await browser.newContext({ viewport: page.viewportSize() ?? { width: 1280, height: 720 } })
  let failed = false
  try {
    const admin = await context.newPage()
    await login(admin, 'p3-admin-' + info.project.name + '@example.com')
    const { card, pageIndex } = await openManagedStore(admin, slug)
    expect(pageIndex, '첫 페이지를 채운 회귀 fixture가 실제 페이지 이동을 강제한다').toBeGreaterThan(0)
    await card.getByLabel('조치 사유').fill('모의 정지 사유')
    await card.getByRole('button', { name: '판매 정지하기' }).focus()
    await admin.keyboard.press('Enter')
    await expect(card.getByRole('button', { name: '판매 복구하기' })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: '매대 저장', exact: true })).toHaveCount(0)
    expect((await page.request.get('/api/stores/' + slug)).status()).toBe(404)
    expect((await page.request.get('/api/account/profile')).status()).toBe(200)
    await page.goto('/seller/products')
    await expect(page.getByRole('alert')).toContainText('이 기능을 이용할 수 없습니다')
    await page.goto('/seller/orders')
    await expect(page.getByRole('heading', { name: '판매 주문·모의 배송', exact: true })).toBeVisible()
    await expect(page.getByText('조회할 판매 주문이 없습니다.', { exact: true })).toBeVisible()
    expect((await page.request.get('/api/seller/orders')).status()).toBe(200)
    await card.getByLabel('조치 사유').fill('모의 복구 사유')
    await card.getByRole('button', { name: '판매 복구하기' }).click()
    await expect(card.getByRole('button', { name: '판매 정지하기' })).toBeVisible()
    await page.goto('/seller/store')
    await expect(page.getByLabel('매대 공개', { exact: true })).not.toBeChecked()
    expect((await page.request.get('/api/stores/' + slug)).status()).toBe(404)
    await page.getByLabel('폐점 사유').fill('모의 폐점 사유')
    await page.getByLabel('복구할 수 없음을 확인했습니다').check()
    await page.getByRole('button', { name: '매대 폐점하기' }).click()
    await expect(page.getByText('매대를 폐점했습니다.')).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: '매대 폐점하기' })).toHaveCount(0)
    await expect(page.getByText(/모의 폐점 사유/)).toBeVisible()
    expect((await page.request.get('/api/account/profile')).status()).toBe(200)
    await admin.getByRole('button', { name: '목록 새로고침' }).click()
    await expect(card.getByRole('button', { name: '판매 정지하기' })).toHaveCount(0)
    await expect(card.getByText(/모의 폐점 사유/)).toBeVisible()
    for (const target of [page, admin]) expect(await target.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await page.locator('.store-card').screenshot({ path: info.outputPath('closed-store.png') })
    await card.screenshot({ path: info.outputPath('store-operations.png') })
  } catch (error) {
    failed = true
    throw error
  } finally {
    await context.close().catch(error => {
      if (!failed) throw error
      console.warn('LIFECYCLE cleanup failed after the original test failure', error)
    })
  }
})
