import { expect, test } from '@playwright/test'

test('실제 Java 공통404 오류와 요청 ID를 표시하고 재시도한다', async ({ page }, testInfo) => {
  await page.goto('/dev/api-errors')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('오류 안내 확인')
  const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === '/api/error-preview')
  await page.getByRole('button', { name: '오류 응답 확인' }).click()
  const response = await responsePromise
  expect(response.status()).toBe(404)
  const body = await response.json()
  expect(body.code).toBe('NOT_FOUND')
  expect(body.requestId).toBe(response.headers()['x-request-id'])
  await expect(page.getByRole('alert')).toContainText('요청한 대상을 찾을 수 없습니다.')
  await expect(page.getByRole('alert')).toContainText(body.requestId)
  const retry = page.waitForResponse(response => new URL(response.url()).pathname === '/api/error-preview')
  await page.getByRole('button', { name: '다시 시도' }).focus()
  await page.keyboard.press('Enter')
  const next = await (await retry).json()
  expect(next.requestId).not.toBe(body.requestId)
  await expect(page.getByRole('alert')).toContainText(next.requestId)
  await expect(page.getByRole('alert')).not.toContainText(body.requestId)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await testInfo.attach('공통 오류와 요청 ID', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('production 빌드에서는 개발 오류 확인 화면을 제공하지 않는다', async ({ page }) => {
  await page.goto('http://127.0.0.1:24175/dev/api-errors')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('아직 없는 길이에요.')
  await expect(page.getByRole('button', { name: '오류 응답 확인' })).toHaveCount(0)
})
