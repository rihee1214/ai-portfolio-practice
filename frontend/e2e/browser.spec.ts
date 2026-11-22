import { expect, test } from '@playwright/test'

test('홈 공통 헤더에서 확인된 비회원은 회원가입·로그인으로 바로 이동한다', async ({ page }) => {
  await page.route('**/api/auth/me', route => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }))
  for (const [name, path] of [['회원가입', '/signup'], ['로그인', '/login']]) {
    await page.goto('/')
    const menu = page.getByRole('navigation', { name: '회원 서비스', exact: true })
    const link = menu.getByRole('link', { name, exact: true })
    await expect(link).toBeVisible()
    await expect(link).toHaveAttribute('href', path)
    await expect(menu.getByRole('link', { name: /내 계정|판매 신청 상태|판매자 센터|운영자 센터/ })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await link.focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(new RegExp(path + '$'))
    await expect(page.getByRole('heading', { level: 1, name, exact: true })).toBeVisible()
  }
})

test('실제 React → Vite → Java health 응답과 재시도', async ({ page, request }) => {
  const java = await request.get('http://127.0.0.1:18080/actuator/health', { headers: { Accept: 'application/json' } })
  expect(java.status()).toBe(200)
  expect(await java.json()).toEqual({ status: 'UP' })
  await page.goto('/')
  await expect(page.getByRole('status')).toHaveText('아직 서버 연결을 확인하지 않았습니다.')
  for (const name of ['연결 확인', '다시 시도']) {
    const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === '/actuator/health')
    await page.getByRole('button', { name, exact: true }).click()
    const response = await responsePromise
    expect(response.status()).toBe(200)
    expect(await response.json()).toEqual({ status: 'UP' })
    await expect(page.getByRole('status')).toHaveText('서버와 정상적으로 연결되었습니다.')
  }
})

test('구매자·판매자·운영자의 직접 URL과 새로고침', async ({ page }) => {
  const routes = [
    ['/account', '내 계정'], ['/account/orders', '주문·결제 이력'],
    ['/account/addresses', '배송지'], ['/account/wishlist', '찜한 상품'], ['/account/coupons', '내 쿠폰'],
    ['/seller', '매대 운영'], ['/seller/store', '내 매대'], ['/seller/products', '상품 관리'],
    ['/seller/orders', '판매 주문'], ['/seller/settlements', '매출·모의 정산'],
    ['/admin', '사이트 운영'], ['/admin/users', '회원 관리'], ['/admin/sellers', '판매자 심사'],
    ['/admin/moderation', '콘텐츠 조치'], ['/admin/orders', '거래 관리'],
  ]
  for (const [path, title] of routes) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
    await page.reload()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
    await expect(page).toHaveTitle(`${title} · 모아마켓`)
  }
})

test('없는 경로 안내와 홈 복귀', async ({ page }) => {
  await page.goto('/seller/not-a-real-page')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('아직 없는 길이에요.')
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('아직 없는 길이에요.')
  await page.getByRole('link', { name: '홈으로 돌아가기' }).click()
  await expect(page).toHaveURL('http://127.0.0.1:24174/')
  await expect(page.getByRole('textbox', { name: '매대 이름' })).toBeVisible()
})

test('키보드 건너뛰기·입력·초기화·경로 이동 포커스', async ({ page }) => {
  await page.goto('/')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '본문으로 건너뛰기' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  const input = page.getByRole('textbox', { name: '매대 이름' })
  await input.focus()
  await page.keyboard.press('ControlOrMeta+A')
  await page.keyboard.type('Keyboard shop')
  await expect(page.getByText('Keyboard shop', { exact: true })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: '초기화' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(input).toHaveValue('나의 작은 매대')
  const products = page.getByRole('navigation', { name: '주요 화면' }).getByRole('link', { name: '상품', exact: true })
  await products.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('상품 둘러보기')
  await expect(page.getByRole('main')).toBeFocused()
  await page.goBack()
  await expect(input).toHaveValue('나의 작은 매대')
})

test('화면 폭에 맞는 주요 화면과 클릭 가능한 이동', async ({ page }, testInfo) => {
  for (const path of ['/', '/account', '/seller/products', '/admin/moderation']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    const nav = page.getByRole('navigation', { name: '주요 화면' })
    for (const name of ['둘러보기', '상품']) {
      await expect(nav.getByRole('link', { name, exact: true })).toBeInViewport()
    }
    const account = page.getByRole('navigation', { name: '회원 서비스' })
    await expect(account.getByRole('link', { name: '장바구니', exact: true })).toBeInViewport()
    await expect(account.getByRole('link', { name: /내 계정|판매 신청 상태|판매자 센터|운영자 센터/ })).toHaveCount(0)
  }
  await page.getByRole('navigation', { name: '주요 화면' }).getByRole('link', { name: '상품', exact: true }).click()
  await expect(page).toHaveURL(/\/products$/)
  await testInfo.attach('실제 브라우저 화면', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('새 브라우저 문맥은 입력·저장소를 공유하지 않는다', async ({ page, browser }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '매대 이름' }).fill('첫 문맥 매대')
  await page.evaluate(() => localStorage.setItem('d4-context-marker', 'first'))
  const context = await browser.newContext()
  try {
    const fresh = await context.newPage()
    await fresh.goto('http://127.0.0.1:24174/')
    await expect(fresh.getByRole('textbox', { name: '매대 이름' })).toHaveValue('나의 작은 매대')
    expect(await fresh.evaluate(() => localStorage.getItem('d4-context-marker'))).toBeNull()
    await fresh.getByRole('textbox', { name: '매대 이름' }).fill('새 문맥 매대')
    await expect(page.getByRole('textbox', { name: '매대 이름' })).toHaveValue('첫 문맥 매대')
  } finally { await context.close() }
  await page.reload()
  await expect(page.getByRole('textbox', { name: '매대 이름' })).toHaveValue('나의 작은 매대')
})
