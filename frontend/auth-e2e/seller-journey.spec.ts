import { test, expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

async function csrf(page: Page) { return (await (await page.request.get('/api/auth/csrf')).json()).token as string }
async function login(page: Page, email: string, password: string) {
  await page.goto('/login')
  await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled()
  await page.getByLabel('이메일', { exact: true }).fill(email)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByLabel('비밀번호', { exact: true }).press('Enter')
  await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
}

test('동일 회원이 신청·승인·매대 공개·정지·복구·재공개·폐점까지 이어간다', async ({ page, browser }, info) => {
  // Only the operator is seeded. This member gains SELLER exclusively through approval.
  const email = 'journey-' + randomUUID() + '@example.com'
  const password = 'journey-test-password'
  const headers = { 'X-CSRF-TOKEN': await csrf(page) }
  expect((await page.request.post('/api/auth/signup', { headers, data: { email, password } })).status()).toBe(201)
  const mail = await page.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } })
  expect((await page.request.post('/api/auth/verify-email', { headers, data: { token: (await mail.json()).token } })).status()).toBe(200)
  await login(page, email, password)
  const member = await (await page.request.get('/api/auth/me')).json()
  expect(member.roles).toEqual(['BUYER'])
  const slug = 'journey-' + randomUUID().slice(0, 8)
  const fields = { slug, name: '연속 흐름 매대', introduction: '모의 소개', address: '가상 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 0 }
  expect((await page.request.post('/api/seller/stores', { headers: { 'X-CSRF-TOKEN': await csrf(page) }, data: fields })).status()).toBe(403)
  await page.goto('/account/seller-application')
  await page.getByLabel('모의 매대 이름').fill(slug)
  await page.getByLabel('판매 계획').fill('동일 회원의 가상 판매 계획')
  await page.getByRole('button', { name: '판매 신청하기', exact: true }).click()
  await expect(page.getByText('처리했습니다.', { exact: true })).toBeVisible()

  const viewport = page.viewportSize() ?? { width: 1280, height: 720 }
  const operatorContext = await browser.newContext({ viewport })
  const visitorContext = await browser.newContext({ viewport })
  try {
    const operator = await operatorContext.newPage()
    const visitor = await visitorContext.newPage()
    await login(operator, 'p3-admin-' + info.project.name + '@example.com', ' A1-private-passphrase-가나다 ')
    const operatorId = (await (await operator.request.get('/api/auth/me')).json()).id
    await operator.goto('/admin/sellers')
    const application = operator.getByRole('article', { name: slug })
    await application.getByLabel('심사 결과').selectOption('APPROVED')
    await application.getByLabel('심사 사유').fill('연속 흐름 승인')
    await application.getByRole('button', { name: '심사 결과 저장' }).click()
    await expect(operator.getByText('처리했습니다.', { exact: true })).toBeVisible()
    // No logout, new member context, or second login after approval.
    async function buyerRetained() {
      const me = await page.request.get('/api/auth/me')
      expect(me.status()).toBe(200)
      const current = await me.json()
      expect(current.id).toBe(member.id)
      expect(current.roles.sort()).toEqual(['BUYER', 'SELLER'])
      expect((await page.request.get('/api/account/profile')).status()).toBe(200)
    }
    await buyerRetained()
    await page.reload()
    await expect(page.getByText(/판매자 자격이 있습니다/)).toBeVisible()
    const applications = (await (await page.request.get('/api/seller/applications')).json()).items
    expect(applications).toHaveLength(1)
    expect(applications[0]).toMatchObject({ applicantId: member.id, status: 'APPROVED' })
    expect(applications[0].events).toEqual(expect.arrayContaining([expect.objectContaining({ actorId: operatorId, status: 'APPROVED', reason: '연속 흐름 승인' })]))
    const ownBefore = await page.request.get('/api/seller/stores')
    expect(ownBefore.status()).toBe(200)
    expect((await ownBefore.json()).store).toBeNull()

    await page.goto('/seller/store')
    await page.getByLabel('매대 URL 주소').fill(slug)
    await page.getByLabel('매대 이름', { exact: true }).fill(fields.name)
    await page.getByLabel('매대 소개', { exact: true }).fill(fields.introduction)
    await page.getByLabel('공개용 주소').fill(fields.address)
    await page.getByLabel('매대 공개', { exact: true }).check()
    await page.getByRole('button', { name: '매대 만들기' }).click()
    await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible()
    const own = (await (await page.request.get('/api/seller/stores')).json()).store
    expect(own).toMatchObject({ slug, state: 'OPEN', visible: true })
    await visitor.goto('/stores/' + slug)
    await expect(visitor.getByRole('heading', { name: fields.name, exact: true })).toBeVisible()
    await operator.goto('/admin/stores')
    const card = operator.getByRole('article', { name: slug })
    await card.getByLabel('조치 사유').fill('연속 흐름 정지')
    await card.getByRole('button', { name: '판매 정지하기' }).click()
    await expect(card.getByRole('button', { name: '판매 복구하기' })).toBeVisible()
    await buyerRetained()
    await visitor.reload()
    await expect(visitor.getByRole('alert')).toContainText('찾을 수 없습니다')
    await page.reload()
    await expect(page.getByRole('button', { name: '매대 저장', exact: true })).toHaveCount(0)
    await card.getByLabel('조치 사유').fill('연속 흐름 복구')
    await card.getByRole('button', { name: '판매 복구하기' }).click()
    await expect(card.getByRole('button', { name: '판매 정지하기' })).toBeVisible()
    await buyerRetained()
    await page.reload()
    await expect(page.getByLabel('매대 공개', { exact: true })).not.toBeChecked()
    await visitor.reload()
    await expect(visitor.getByRole('alert')).toContainText('찾을 수 없습니다')
    await page.getByLabel('매대 공개', { exact: true }).check()
    await page.getByRole('button', { name: '매대 저장', exact: true }).click()
    await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible()
    await visitor.reload()
    await expect(visitor.getByRole('heading', { name: fields.name, exact: true })).toBeVisible()
    await page.getByLabel('폐점 사유').fill('연속 흐름 폐점')
    await page.getByLabel('복구할 수 없음을 확인했습니다').check()
    await page.getByRole('button', { name: '매대 폐점하기' }).click()
    await expect(page.getByText('매대를 폐점했습니다.')).toBeVisible()
    await buyerRetained()
    await page.reload()
    await expect(page.getByText('폐점', { exact: true })).toBeVisible()
    await visitor.reload()
    await expect(visitor.getByRole('alert')).toContainText('찾을 수 없습니다')
    expect((await page.request.post('/api/seller/stores', {
      headers: { 'X-CSRF-TOKEN': await csrf(page) }, data: { ...fields, slug: 'reopen-' + randomUUID().slice(0, 8) },
    })).status()).toBe(409)
    expect((await (await page.request.get('/api/seller/stores')).json()).store).toMatchObject({ id: own.id, slug, state: 'CLOSED', visible: false })
    const history = await page.request.get('/api/seller/stores/' + own.id + '/history')
    expect(history.status()).toBe(200)
    const events = await history.json()
    expect(events).toHaveLength(3)
    expect(events.map((event: { action: string }) => event.action)).toEqual(['close', 'restore', 'suspend'])
    expect(events[0]).toMatchObject({ actorId: member.id, beforeState: 'OPEN', afterState: 'CLOSED', reason: '연속 흐름 폐점' })
    expect(events[1]).toMatchObject({ actorId: operatorId, beforeState: 'SUSPENDED', afterState: 'OPEN', reason: '연속 흐름 복구' })
    expect(events[2]).toMatchObject({ actorId: operatorId, beforeState: 'OPEN', afterState: 'SUSPENDED', reason: '연속 흐름 정지' })
  } finally { await operatorContext.close(); await visitorContext.close() }
})
