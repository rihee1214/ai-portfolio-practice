import { createHash, randomUUID } from 'node:crypto'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test, expect, operation, prepare, type BrowserContext, type Page, boundedFetch } from './business-deadline'

type Accounts = { buyer: string; seller: string; other: string; admin: string }
type Product = { id: string; slug: string; title: string; description: string; categoryId: string; state: string; version: number }
type Sku = { id: string; options: { name: string; value: string }[]; version: number }
type Slot = { slot: number; version: number; assetId: string | null; alt: string }
const passwords = JSON.parse(process.env.P12_C_PASSWORDS!) as Record<string, string>
function passwordFor(email: string) {
  const value = passwords[email]
  if (typeof value !== 'string' || value.length < 12) throw new Error('Individual prepared account credential missing')
  return value
}
const accounts = (project: string) => (JSON.parse(process.env.P12_C_ACCOUNTS!) as Record<string, Accounts>)[project]
function productInput(product: Product, state: string) {
  return { categoryId: product.categoryId, slug: product.slug, title: product.title, description: product.description,
    state, version: product.version, returnFee: '3000' }
}
async function headers(c: BrowserContext) {
  const response = await c.request.get('/api/auth/csrf'); expect(response.status()).toBe(200)
  const body = await response.json(); expect(body.headerName).toBe('X-CSRF-TOKEN')
  return { 'X-CSRF-TOKEN': body.token as string }
}
async function read<T>(c: BrowserContext, path: string): Promise<T> {
  return operation('package: read API state', async () => {
    const response = await c.request.get(path); expect(response.status(), path + ' ' + await response.text()).toBe(200)
    expect(response.headers()['cache-control']).toContain('no-store')
    return response.json() as Promise<T>
  })
}
async function change<T>(c: BrowserContext, method: 'POST' | 'PUT', path: string, data: unknown, status = 200): Promise<T> {
  return operation('package: issue real API command', async () => {
    const response = await c.request.fetch(path, { method, headers: await headers(c), data })
    expect(response.status(), path + ' ' + await response.text()).toBe(status)
    expect(response.headers()['cache-control']).toContain('no-store')
    return response.json() as Promise<T>
  })
}
async function login(c: BrowserContext, email: string) {
  return operation('package: login individual prepared account', async () => {
    const response = await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password: passwordFor(email) } })
    expect(response.status()).toBe(200)
    const me = await read<{ id: string; roles: string[] }>(c, '/api/auth/me')
    return me
  })
}
async function fresh(page: Page, email: string) {
  return prepare('package: separate individual browser session', async () => {
    const c = await page.context().browser()!.newContext({ baseURL: process.env.P12_C_BASE_URL, ignoreHTTPSErrors: true,
      httpCredentials: { username: process.env.P12_C_GATE_USER!, password: process.env.P12_C_GATE_PASSWORD!, send: 'always' } })
    await login(c, email)
    return c
  })
}
async function product(page: Page, project: string) {
  const namespace = 'p12c-' + project + '-' + randomUUID().slice(0, 8), actors = accounts(project)
  const admin = await fresh(page, actors.admin), seller = await fresh(page, actors.seller)
  const category = await change<{ id: string }>(admin, 'POST', '/api/admin/content/categories', { name: namespace, active: true, version: 0 }, 201)
  // A seller owns one store. Each project has its own account; multiple scenarios reuse only that account's store.
  const { store } = await read<{ store: { id: string } | null }>(seller, '/api/seller/stores')
  if (!store) await change(seller, 'POST', '/api/seller/stores', { slug: namespace, name: namespace, introduction: '패키지 실제 API 매대', address: '합성 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 0 }, 201)
  const p = await change<Product>(seller, 'POST', '/api/seller/products', { categoryId: category.id, slug: namespace, title: namespace, description: '패키지 실제 API 상품', state: 'DRAFT', version: 0, returnFee: '3000' }, 201)
  const initial = (await read<{ items: Sku[] }>(seller, '/api/seller/products/' + p.id + '/skus')).items[0]
  const sku = await change<Sku>(seller, 'PUT', '/api/seller/products/' + p.id + '/skus/' + initial.id, { options: initial.options, price: 10000, active: true, version: initial.version })
  const inventory = (await read<{ items: { version: number }[] }>(seller, '/api/seller/products/' + p.id + '/inventory')).items[0]
  await change(seller, 'PUT', '/api/seller/products/' + p.id + '/inventory/' + sku.id, { onHand: 5, version: inventory.version, requestId: randomUUID(), reason: '새 패키지 합성 재고 입고' })
  return { seller, admin, p, sku }
}

test('P12-C package preserves exact SPA assets health and individual auth boundaries', async ({ page, context }, info) => {
  await operation('package: reload static deep link and verify exact assets', async () => {
    await context.setExtraHTTPHeaders({ Forwarded: 'for=198.51.100.42;host=evil.invalid;proto=http',
      'X-Forwarded-For': '198.51.100.42', 'X-Forwarded-Proto': 'http', 'X-Forwarded-Host': 'evil.invalid',
      'X-Forwarded-Port': '80', 'X-Forwarded-Prefix': '/untrusted', 'X-Forwarded-Ssl': 'on' })
    const response = await page.goto('/products'); expect(response?.status()).toBe(200)
    await expect(page.getByRole('heading', { name: '상품 둘러보기', exact: true })).toBeVisible()
    expect((await page.reload())?.status()).toBe(200)
    const scripts = await page.locator('script[src]').evaluateAll(nodes => nodes.map(node => node.getAttribute('src')!))
    expect(scripts.some(path => /^\/assets\/.*-[\w-]+\.js$/.test(path))).toBe(true)
    for (const path of scripts) {
      expect(path).toMatch(/^\/assets\/[\w.-]+\.js$/)
      const asset = await context.request.get(path); expect(asset.status()).toBe(200)
      expect(asset.headers()['content-type']).toMatch(/javascript/)
      expect(asset.headers()['cache-control']).toContain('immutable')
      const expected = createHash('sha256').update(readFileSync(resolve(process.env.P12_C_DIST!, '.' + path))).digest('hex')
      expect(createHash('sha256').update(await asset.body()).digest('hex')).toBe(expected)
      appendFileSync(resolve(process.env.A3_EVIDENCE!, 'package-assets.ndjson'), JSON.stringify({ project: info.project.name, path, sha256: expected }) + '\n')
    }
    const missing = await context.request.get('/assets/missing-p12c.js'); expect(missing.status()).toBe(404)
    expect(missing.headers()['content-type']).not.toContain('text/html')
    const html = await context.request.get('/index.html'); expect(html.headers()['cache-control']).toContain('no-cache')
  })
  await operation('package: exact health traverses proxy into existing UI', async () => {
    const response = await context.request.get('/actuator/health'); expect(response.status()).toBe(200)
    expect(await response.json()).toEqual({ status: 'UP' }); expect(response.headers()['cache-control']).toContain('no-store')
    for (const path of ['/actuator/env', '/actuator/health/details', '/ACTUATOR/health']) {
      const error = await context.request.get(path); expect(error.status()).toBe(404)
      expect(error.headers()['content-type']).toContain('application/json')
      expect(await error.text()).not.toContain('<!doctype')
    }
    await page.goto('/'); await page.getByRole('button', { name: '연결 확인', exact: true }).click()
    await expect(page.getByText('서버와 정상적으로 연결되었습니다.', { exact: true })).toBeVisible()
  })
  await operation('package: individual passwords cannot authenticate another account', async () => {
    const actor = accounts(info.project.name)
    expect(passwordFor(actor.buyer)).not.toBe(passwordFor(actor.other))
    const rejected = await context.request.post('/api/auth/login', { headers: await headers(context), form: { email: actor.buyer, password: passwordFor(actor.other) } })
    expect(rejected.status()).toBe(401)
    expect((await context.request.get('/api/auth/me')).status()).toBe(401)
  })
  await operation('package: individual login secure cookie CSRF and role denial', async () => {
    expect((await context.request.get('/api/account/addresses')).status()).toBe(401)
    expect((await context.request.get('/api/missing-p12c')).status()).toBe(401)
    await page.goto('/login'); await page.getByLabel('이메일', { exact: true }).fill(accounts(info.project.name).buyer)
    await page.getByLabel('비밀번호', { exact: true }).fill(passwordFor(accounts(info.project.name).buyer))
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
    const session = (await context.cookies()).find(cookie => cookie.name === 'SESSION')
    expect(session).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Lax', path: '/' })
    expect((await context.request.post('/api/auth/logout')).status()).toBe(403)
    expect((await context.request.get('/api/admin/content/categories')).status()).toBe(403)
    for (const path of ['/api/missing-p12c', '/api/__test/package']) {
      const missing = await context.request.get(path); expect(missing.status()).toBe(404)
      expect(missing.headers()['content-type']).toContain('application/json')
      expect(await missing.text()).not.toContain('<!doctype')
    }
    const me = await context.request.get('/api/auth/me'); expect(me.headers()['cache-control']).toContain('no-store')
    expect((await me.json()).roles).toEqual(['BUYER'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
})

test('P12-C public demo disables issuance and rejects valid session unsupported commands', async ({ page, context }, info) => {
  await login(context, accounts(info.project.name).buyer)
  await operation('package: server capability restricts public demo issuance', async () => {
    expect(await read(context, '/api/runtime-policy')).toEqual({ mode: 'public-demo', restricted: true })
    for (const [path, title] of [['/signup', '회원가입'], ['/verify-email', '이메일 확인'], ['/password/request', '복구 코드 요청'], ['/password/reset', '비밀번호 재설정'], ['/checkout/guest', '방문자 구매 인증'], ['/guest/recovery', '비회원 조회 비밀번호 복구']]) {
      await page.goto(path); await expect(page.getByRole('button', { name: title, exact: true })).toBeDisabled()
      await expect(page.getByText(/초대받은 검토자의 기존 개별 계정/)).toBeVisible()
      await expect(page.locator('form')).toHaveCount(0)
    }
  })
  await operation('package: valid session unsupported commands preserve server refusal', async () => {
    const csrf = await headers(context)
    for (const path of ['/api/auth/signup', '/api/auth/verify-email', '/api/auth/password/request', '/api/auth/password/reset']) {
      const response = await context.request.post(path, { headers: csrf, data: {} })
      expect(response.status(), path + ' ' + await response.text()).toBe(503)
      expect(response.headers()['content-type']).toContain('application/json')
      expect(response.headers()['cache-control']).toContain('no-store')
      expect((await response.json()).code).toBe('DEMO_FEATURE_UNAVAILABLE')
    }
  })
})

test('P12-C packaged commerce persists one order through response loss and autonomous worker', async ({ page, context }, info) => {
  const { seller, p } = await product(page, info.project.name)
  await change(seller, 'PUT', '/api/seller/products/' + p.id, productInput(p, 'ACTIVE'))
  await login(context, accounts(info.project.name).buyer)
  await operation('package: storefront adds real product and reads server quote', async () => {
    await page.goto('/products/' + p.slug); await page.getByRole('button', { name: '장바구니 담기', exact: true }).click()
    await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible()
    await page.goto('/checkout'); await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 13,000원')
  })
  await prepare('package: synthetic order contact input', async () => {
    for (const [label, value] of [['주문 구매자 이름', '패키지 구매자'], ['주문 구매자 전화번호', '01012345678'], ['수령인 이름', '패키지 수령인'], ['수령인 전화번호', '01087654321'], ['우편번호', '01234'], ['기본 주소', '합성 배송 주소'], ['상세 주소', '101호']])
      await page.getByLabel(label, { exact: true }).fill(value)
    await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
  })
  let orderNumber = '', firstBody: unknown
  await operation('package: lost response recovery reuses one durable order command', async () => {
    let count = 0
    // Fetch reaches the actual HTTPS proxy/API before only the browser's first response is discarded.
    await page.route('**/api/orders', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const body = route.request().postDataJSON(), response = await route.fetch()
      expect(response.status()).toBe(200)
      const saved = await response.json()
      if (++count === 1) { firstBody = body; orderNumber = saved.orderNumber; await route.abort('failed') }
      else { expect(body).toEqual(firstBody); expect(saved.orderNumber).toBe(orderNumber); await route.fulfill({ response }) }
    })
    await page.getByRole('button', { name: '주문 생성', exact: true }).click()
    await page.getByRole('button', { name: '같은 요청으로 주문 결과 확인', exact: true }).click()
    await expect(page.getByTestId('created-order-number')).toHaveText(orderNumber)
    expect(count).toBe(2); await page.unroute('**/api/orders')
    const replay = await context.request.post('/api/orders', { headers: await headers(context), data: { ...(firstBody as object), contact: { ...((firstBody as { contact: object }).contact), recipientName: '다른 수령인' } } })
    expect(replay.status()).toBe(409); expect(replay.headers()['content-type']).toContain('application/json')
    expect(replay.headers()['cache-control']).toContain('no-store')
  })
  await operation('package: DEMO approval and autonomous worker produce delivery and notification', async () => {
    await page.getByRole('link', { name: '결제로 이동', exact: true }).click()
    await page.getByRole('button', { name: '모의 결제 승인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '결제가 완료되었습니다', exact: true })).toBeVisible()
    await expect.poll(async () => (await read<{ state: string }[]>(context, '/api/orders/' + orderNumber + '/shipments')).length).toBe(1)
    await expect.poll(async () => (await read<{ items: { kind: string }[] }>(context, '/api/account/notifications')).items.some(item => item.kind === 'PAYMENT_BUYER')).toBe(true)
    const detail = await read<{ state: string; total: string }>(context, '/api/orders/' + orderNumber)
    expect(detail).toMatchObject({ state: 'PAID', total: '13000' })
    if (!process.env.P12_C_OBSERVER_URL) throw new Error('Package read-only database observer is required')
    const proofResponse = await boundedFetch(process.env.P12_C_OBSERVER_URL + '/purchase?orderNumber=' + orderNumber, { headers: { 'X-Observer-Key': process.env.P12_C_OBSERVER_KEY! } })
    expect(proofResponse.status).toBe(200)
    const proof = await proofResponse.json()
    expect(proof.order).toMatchObject({ state: 'PAID', amount: 13000 })
    expect(proof.inventory).toEqual([expect.objectContaining({ status: 'COMMITTED' })])
    expect(proof.attempts).toEqual([expect.objectContaining({ approval_effects: 1, success_events: 1 })])
    expect(proof.shipments).toHaveLength(1)
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'package-commerce.ndjson'), JSON.stringify({ project: info.project.name, orderNumber, product: p.id, proof }) + '\n')
  })
  const other = await fresh(page, accounts(info.project.name).other)
  await operation('package: another individual cannot read durable purchase', async () => {
    expect((await other.request.get('/api/orders/' + orderNumber)).status()).toBe(404)
    await page.goto('/account/orders/' + orderNumber); await expect(page.getByTestId('order-number')).toHaveText(orderNumber)
    await page.goto('/account/notifications'); await expect(page.getByRole('link', { name: '주문 결제가 완료되었습니다.', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
})

test('P12-C packaged media preserves bytes ownership and failed upload state', async ({ page }, info) => {
  const { seller, p } = await product(page, info.project.name)
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
  let slot: Slot
  await operation('package: real upload persists normalized bytes behind owner authorization', async () => {
    const initial = (await read<Slot[]>(seller, '/api/media/products/' + p.id))[0]
    const response = await seller.request.put('/api/media/products/' + p.id + '/0', { headers: await headers(seller), multipart: { version: String(initial.version), alt: '패키지 실제 이미지', file: { name: 'synthetic.png', mimeType: 'image/png', buffer: png } } })
    expect(response.status(), await response.text()).toBe(200); slot = await response.json()
    const owner = await seller.request.get('/api/media/owner/' + slot.assetId + '/preview'); expect(owner.status()).toBe(200)
    expect(owner.headers()['content-type']).toBe('image/png'); expect(owner.headers()['cache-control']).toContain('no-store')
    const hash = createHash('sha256').update(await owner.body()).digest('hex')
    const again = await seller.request.get('/api/media/owner/' + slot.assetId + '/preview')
    expect(createHash('sha256').update(await again.body()).digest('hex')).toBe(hash)
    const publicImage = await page.context().request.get('/api/media/' + slot.assetId + '/content'); expect(publicImage.status()).toBe(404)
    const forbidden = await page.context().request.get('/api/media/owner/' + slot.assetId + '/preview'); expect([401, 403, 404]).toContain(forbidden.status())
  })
  await operation('package: rejected MIME and oversized proxy upload preserve existing media', async () => {
    const before = await read<Slot[]>(seller, '/api/media/products/' + p.id), csrf = await headers(seller)
    const invalid = await seller.request.put('/api/media/products/' + p.id + '/0', { headers: csrf, multipart: { version: String(slot.version), alt: '실패 이미지', file: { name: 'invalid.png', mimeType: 'image/png', buffer: Buffer.from('<html>not an image</html>') } } })
    expect(invalid.status()).toBe(400)
    const oversize = await seller.request.put('/api/media/products/' + p.id + '/0', { headers: csrf, multipart: { version: String(slot.version), alt: '한도 초과', file: { name: 'oversize.png', mimeType: 'image/png', buffer: Buffer.alloc(7 * 1024 * 1024) } } })
    expect(oversize.status()).toBe(413); expect(oversize.headers()['content-type']).toContain('application/json')
    expect(oversize.headers()['cache-control']).toContain('no-store')
    expect(await read(seller, '/api/media/products/' + p.id)).toEqual(before)
    expect((await seller.request.get('/api/media/owner/' + slot.assetId + '/preview')).status()).toBe(200)
  })
  await operation('package: public media consumer receives original normalized bytes until visibility changes', async () => {
    const active = await change<Product>(seller, 'PUT', '/api/seller/products/' + p.id, productInput(p, 'ACTIVE'))
    const owner = await seller.request.get('/api/media/owner/' + slot.assetId + '/preview')
    const published = await page.context().request.get('/api/media/' + slot.assetId + '/content')
    expect(published.status()).toBe(200)
    expect(createHash('sha256').update(await published.body()).digest('hex')).toBe(createHash('sha256').update(await owner.body()).digest('hex'))
    await page.goto('/products/' + p.slug)
    const image = page.getByRole('img', { name: '패키지 실제 이미지', exact: true })
    await expect(image).toBeVisible(); await expect.poll(() => image.evaluate(node => (node as HTMLImageElement).naturalWidth)).toBe(1)
    await change(seller, 'PUT', '/api/seller/products/' + p.id, productInput(active, 'STOPPED'))
    expect((await page.context().request.get('/api/media/' + slot.assetId + '/content')).status()).toBe(404)
    expect((await seller.request.get('/api/media/owner/' + slot.assetId + '/preview')).status()).toBe(200)
  })
})

test('P12-C previous bundle consumes current login session and public demo refusal', async ({ page, context }, info) => {
  const legacy = process.env.P12_C_LEGACY_BASE_URL!, dist = process.env.P12_C_LEGACY_DIST!
  const legacyEmail = 'p12c-legacy-' + info.project.name + '-' + randomUUID().slice(0, 8) + '@example.com'
  const observation = async () => {
    const response = await boundedFetch(process.env.P12_C_OBSERVER_URL + '/legacy-issuance?email=' + encodeURIComponent(legacyEmail), { headers: { 'X-Observer-Key': process.env.P12_C_OBSERVER_KEY! } })
    expect(response.status).toBe(200)
    return response.json() as Promise<{ users: number; signupTokens: number; resetTokens: number }>
  }
  await operation('package: previous bundle loads exact preserved asset', async () => {
    const manifest = JSON.parse(readFileSync(resolve(dist, '.vite/manifest.json'), 'utf8')) as Record<string, { file: string }>
    const entry = manifest['index.html'].file; expect(entry).toMatch(/^assets\/[\w.-]+\.js$/)
    const expectedHash = createHash('sha256').update(readFileSync(resolve(dist, entry))).digest('hex')
    // This is the preserved P12-B full1 browser artifact, not a new build of current sources.
    expect(expectedHash).toBe('5adf88f54b46639f8379446cfc8aa76d5146f0a9aa642adb11f730b4180e336f')
    const requested = page.waitForResponse(response => response.url() === legacy + '/' + entry)
    expect((await page.goto(legacy + '/login'))?.status()).toBe(200)
    const loaded = await requested; expect(loaded.status()).toBe(200)
    expect(createHash('sha256').update(await loaded.body()).digest('hex')).toBe(expectedHash)
    await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
    expect(await page.locator('script[src]').evaluateAll(nodes => nodes.map(node => node.getAttribute('src')))).toContain('/' + entry)
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'package-legacy.ndjson'), JSON.stringify({ project: info.project.name, legacy, entry, sha256: expectedHash, provenance: 'P12-B full1 browser-api preserved development-mode browser build' }) + '\n')
  })
  await operation('package: previous login UI connects CSRF and current individual session', async () => {
    await page.getByLabel('이메일', { exact: true }).fill(accounts(info.project.name).buyer)
    await page.getByLabel('비밀번호', { exact: true }).fill(passwordFor(accounts(info.project.name).buyer))
    const submitted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    expect((await submitted).status()).toBe(200)
    await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
    const session = (await context.cookies()).find(cookie => cookie.name === 'SESSION')
    expect(session).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Lax', path: '/' })
    const me = await context.request.get(legacy + '/api/auth/me'); expect(me.status()).toBe(200)
    expect((await me.json()).roles).toEqual(['BUYER'])
  })
  await operation('package: previous issuance UI consumes new 503 without success or database issuance', async () => {
    const before = await observation(); expect(before).toEqual({ users: 0, signupTokens: 0, resetTokens: 0 })
    await page.goto(legacy + '/signup')
    await page.getByLabel('이메일', { exact: true }).fill(legacyEmail)
    await page.getByLabel('비밀번호', { exact: true }).fill('P12C rejected synthetic signup ' + randomUUID())
    expect((await context.request.get(legacy + '/api/auth/me')).status()).toBe(200)
    const rejected = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/signup' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '회원가입', exact: true }).click()
    const response = await rejected
    expect(response.status()).toBe(503); expect(response.headers()['content-type']).toContain('application/json')
    expect(response.headers()['cache-control']).toContain('no-store')
    expect((await response.json()).code).toBe('DEMO_FEATURE_UNAVAILABLE')
    await expect(page.getByRole('alert')).toContainText('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    await expect(page.getByRole('link', { name: '이메일 확인하기', exact: true })).toHaveCount(0)
    expect(await page.getByLabel('비밀번호', { exact: true }).inputValue()).toBe('')
    const after = await observation(); expect(after).toEqual(before)
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'package-legacy.ndjson'), JSON.stringify({ project: info.project.name, path: '/api/auth/signup', status: 503, code: 'DEMO_FEATURE_UNAVAILABLE', email: legacyEmail, before, after, providerEvidence: 'Separate same-source PublicDemoRuntimeIT spy assertions; no provider metric is fabricated here' }) + '\n')
  })
  await operation('package: previous logout UI consumes current session revocation', async () => {
    await page.goto(legacy + '/account'); await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
    const loggedOut = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/logout' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '로그아웃', exact: true }).click()
    expect((await loggedOut).status()).toBe(200)
    await expect(page.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible()
    const me = await context.request.get(legacy + '/api/auth/me'); expect(me.status()).toBe(401)
    expect(me.headers()['content-type']).toContain('application/json'); expect(me.headers()['cache-control']).toContain('no-store')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
})
