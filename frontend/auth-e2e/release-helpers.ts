import { createHash, randomUUID } from 'node:crypto'
import { appendFileSync, readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { devices } from '@playwright/test'
import { bindDeadline, boundedFetch, currentDeadline, operation, prepare, expect, type Browser, type BrowserContext, type Page } from './business-deadline'
import { contract, type Stack } from './release-contract'

export const input = contract()
export const digest = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
export function evidence(name: string, value: unknown) {
  appendFileSync(resolve(process.env.A3_EVIDENCE!, name + '.ndjson'), JSON.stringify(value) + '\n')
}
export function settings(stack: Stack, mobile = false) {
  return { baseURL: stack.baseURL, ignoreHTTPSErrors: true, ...(mobile ? devices['Pixel 7'] : devices['Desktop Chrome']),
    ...(stack.gateUser ? { httpCredentials: { username: stack.gateUser, password: stack.gatePassword!, send: 'always' as const } } : {}) }
}
export async function session(browser: Browser, stack: Stack, mobile = false) {
  return prepare('release: open isolated browser context', async () => {
    const context = await browser.newContext(settings(stack, mobile))
    return { context, page: await context.newPage() }
  })
}
export async function csrf(c: BrowserContext) {
  const response = await c.request.get('/api/auth/csrf'); expect(response.status()).toBe(200)
  const body = await response.json(); expect(body.headerName).toBe('X-CSRF-TOKEN')
  return { 'X-CSRF-TOKEN': body.token as string }
}
export async function read<T>(c: BrowserContext, path: string): Promise<T> {
  return operation('release: read current authorized API state', async () => {
    const response = await c.request.get(path)
    expect(response.status(), path + ' ' + await response.text()).toBe(200)
    expect(response.headers()['cache-control']).toContain('no-store')
    return response.json() as Promise<T>
  })
}
export async function command<T>(c: BrowserContext, method: 'POST' | 'PUT', path: string, data: unknown, status = 200): Promise<T> {
  return operation('release: execute real producer API command', async () => {
    const response = await c.request.fetch(path, { method, headers: await csrf(c), data })
    expect(response.status(), path + ' ' + await response.text()).toBe(status)
    return response.json() as Promise<T>
  })
}
export async function login(page: Page, email: string) {
  return operation('release: individual login through actual UI', async () => {
    await page.goto('/login'); await page.getByLabel('이메일', { exact: true }).fill(email)
    await page.getByLabel('비밀번호', { exact: true }).fill(input.passwords[email])
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
    const cookie = (await page.context().cookies()).find(value => value.name === 'SESSION')
    expect(cookie && { secure: cookie.secure, httpOnly: cookie.httpOnly, sameSite: cookie.sameSite }).toEqual({ secure: true, httpOnly: true, sameSite: 'Lax' })
  })
}
export async function assets(page: Page, release: 'R0' | 'R1') {
  return operation('release: verify loaded production index and network asset bytes', async () => {
    const responses = new Map<string, Buffer>()
    const loaded: Promise<void>[] = []
    // Keep actual response bytes, rather than merely downloading a different asset after UI boot.
    const listener = (response: import('@playwright/test').Response) => {
      if (new URL(response.url()).pathname.startsWith('/assets/') && response.status() === 200)
        loaded.push(response.body().then(body => { responses.set(new URL(response.url()).pathname, body) }))
    }
    page.on('response', listener)
    try {
      const html = await page.goto('/login'); expect(html?.status()).toBe(200)
      expect(digest(await html!.body())).toBe(digest(readFileSync(resolve(input.dist[release], 'index.html'))))
      expect(html!.headers()['cache-control']).toMatch(/no-cache|no-store/)
      await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeVisible()
      const scripts = await page.locator('script[src]').evaluateAll(nodes => nodes.map(node => node.getAttribute('src')!))
      expect(scripts.length).toBeGreaterThan(0)
      await Promise.all(loaded)
      for (const path of scripts) {
        expect(path).toMatch(/^\/assets\/[\w.-]+\.js$/)
        expect(responses.has(path), 'Production entry actually loaded over HTTPS: ' + path).toBe(true)
        const sha256 = digest(readFileSync(resolve(input.dist[release], '.' + path)))
        expect(digest(responses.get(path)!)).toBe(sha256)
        evidence('release-assets', { release, path, sha256, networkLoaded: true })
      }
      const absent = await page.context().request.get('/assets/missing-release-' + randomUUID() + '.js')
      expect(absent.status()).toBe(404); expect(absent.headers()['content-type']).not.toContain('text/html')
    } finally { page.off('response', listener) }
  })
}
export async function retainedAssets(context: BrowserContext, release: 'R0' | 'R1') {
  return operation('release: previously published lazy asset bytes remain immutable and never HTML', async () => {
    const names = readdirSync(resolve(input.dist[release], 'assets')).filter(name => /\.(js|css)$/.test(name))
    expect(names.length).toBeGreaterThan(0)
    for (const name of names) {
      expect(name).toMatch(/^[\w.-]+\.(js|css)$/)
      const response = await context.request.get('/assets/' + name)
      expect(response.status()).toBe(200)
      expect(response.headers()['cache-control']).toContain('immutable')
      expect(response.headers()['content-type']).not.toContain('text/html')
      const sha256 = digest(readFileSync(resolve(input.dist[release], 'assets', name)))
      expect(digest(await response.body())).toBe(sha256)
      evidence('release-retained-assets', { release, name, sha256 })
    }
  })
}
type Product = { id: string; slug: string; title: string; description: string; categoryId: string; state: string; version: number }
type Sku = { id: string; options: { name: string; value: string }[]; version: number }
export type ProductFixture = { product: Product; seller: BrowserContext; privateAssetId: string; privateHash: string; publicAssetId: string; publicHash: string }
export async function product(browser: Browser, project: 'desktop' | 'mobile'): Promise<ProductFixture> {
  const actors = input.accounts[project], id = 'p12d-' + project + '-' + randomUUID().slice(0, 8)
  const admin = await session(browser, input.source), seller = await session(browser, input.source)
  await login(admin.page, actors.admin); await login(seller.page, actors.seller)
  const category = await command<{ id: string }>(admin.context, 'POST', '/api/admin/content/categories', { name: id, active: true, version: 0 }, 201)
  const store = await read<{ store: { id: string } | null }>(seller.context, '/api/seller/stores')
  if (!store.store) await command(seller.context, 'POST', '/api/seller/stores', { slug: id, name: id, introduction: '복구 검증 매대', address: '합성 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 0 }, 201)
  const create = () => command<Product>(seller.context, 'POST', '/api/seller/products', { categoryId: category.id, slug: id + '-' + randomUUID().slice(0, 8), title: id, description: '복구 검증 상품', state: 'DRAFT', version: 0, returnFee: '3000' }, 201)
  const p = await create(), privateProduct = await create()
  const initial = (await read<{ items: Sku[] }>(seller.context, '/api/seller/products/' + p.id + '/skus')).items[0]
  const sku = await command<Sku>(seller.context, 'PUT', '/api/seller/products/' + p.id + '/skus/' + initial.id, { options: initial.options, price: 10000, active: true, version: initial.version })
  const inventory = (await read<{ items: { version: number }[] }>(seller.context, '/api/seller/products/' + p.id + '/inventory')).items[0]
  await command(seller.context, 'PUT', '/api/seller/products/' + p.id + '/inventory/' + sku.id, { onHand: 8, version: inventory.version, requestId: randomUUID(), reason: '새 복구 검증 재고' })
  const upload = async (owner: Product) => operation('release: upload actual media and record authorized bytes', async () => {
    const first = (await read<{ version: number }[]>(seller.context, '/api/media/products/' + owner.id))[0]
    const response = await seller.context.request.put('/api/media/products/' + owner.id + '/0', { headers: await csrf(seller.context), multipart: {
      version: String(first.version), alt: '릴리스 복구 이미지', file: { name: 'synthetic.png', mimeType: 'image/png',
        buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64') },
    } })
    expect(response.status(), await response.text()).toBe(200)
    const { assetId } = await response.json() as { assetId: string }
    const preview = await seller.context.request.get('/api/media/owner/' + assetId + '/preview')
    expect(preview.status()).toBe(200)
    return { assetId, hash: digest(await preview.body()) }
  })
  const publicMedia = await upload(p), privateMedia = await upload(privateProduct)
  const active = await command<Product>(seller.context, 'PUT', '/api/seller/products/' + p.id, {
    categoryId: p.categoryId, slug: p.slug, title: p.title, description: p.description, version: p.version, state: 'ACTIVE', returnFee: '3000' })
  return { product: active, seller: seller.context, publicAssetId: publicMedia.assetId, publicHash: publicMedia.hash,
    privateAssetId: privateMedia.assetId, privateHash: privateMedia.hash }
}
export async function purchase(page: Page, fixture: ProductFixture, guest = false, loseResponse = false, retainedBundle = false) {
  await operation('release: real storefront cart command and independent quote amount', async () => {
    if (retainedBundle) {
      await page.getByRole('navigation', { name: '주요 화면', exact: true }).getByRole('link', { name: '상품', exact: true }).click()
      await page.getByLabel('상품 검색', { exact: true }).fill(fixture.product.title)
      await page.getByRole('button', { name: '검색 적용', exact: true }).click()
      const productLink = page.getByRole('heading', { name: fixture.product.title, exact: true })
        .getByRole('link', { name: fixture.product.title, exact: true })
      await expect(productLink).toHaveAttribute('href', new RegExp('^/products/' + fixture.product.slug + '\\?'))
      await productLink.click()
    } else await page.goto('/products/' + fixture.product.slug)
    await page.getByRole('button', { name: '장바구니 담기', exact: true }).click()
    await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible()
    if (retainedBundle) {
      await page.getByRole('link', { name: '장바구니 보기', exact: true }).click()
      await page.getByRole('link', { name: '주문서 준비', exact: true }).click()
    } else await page.goto('/checkout')
    await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 13,000원')
  })
  await prepare('release: enter synthetic shipping contact', async () => {
    for (const [label, value] of [...(guest ? [] : [['주문 구매자 이름', '복구 구매자'], ['주문 구매자 전화번호', '01012345678']]),
      ['수령인 이름', '복구 수령인'], ['수령인 전화번호', '01087654321'], ['우편번호', '01234'], ['기본 주소', '합성 복구 배송 주소'], ['상세 주소', '101호']])
      await page.getByLabel(label, { exact: true }).fill(value)
    await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
  })
  const number = await operation('release: submit durable UI order and same-key response-loss recovery', async () => {
    let orderNumber = '', count = 0, body: unknown
    const endpoint = '/api/orders'
    const expectedRequests = loseResponse ? 2 : 1
    const completions = Array.from({ length: expectedRequests }, () => {
      let complete!: (number: string) => void, fail!: (error: unknown) => void
      const finished = new Promise<string>((resolve, reject) => { complete = resolve; fail = reject })
      // Route failures are rethrown by the initiating command's await, even if the click has not returned yet.
      void finished.catch(() => {})
      return { finished, complete, fail }
    })
    await page.route('**' + endpoint, bindDeadline(async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const index = count++
      try {
        expect(count).toBeLessThanOrEqual(expectedRequests)
        const response = await route.fetch({ timeout: currentDeadline().remaining(10_000) }); expect(response.status()).toBe(200)
        const saved = await response.json()
        expect(saved.orderNumber).toMatch(/^O[0-9a-f]{32}$/)
        if (index === 0) { body = route.request().postDataJSON(); orderNumber = saved.orderNumber }
        else { expect(route.request().postDataJSON()).toEqual(body); expect(saved.orderNumber).toBe(orderNumber) }
        if (loseResponse && index === 0) await route.abort('failed')
        else await route.fulfill({ response })
        completions[index].complete(saved.orderNumber)
      } catch (error) {
        completions[Math.min(index, completions.length - 1)].fail(error)
        await route.abort('failed').catch(() => {})
      }
    }))
    await page.getByRole('button', { name: '주문 생성', exact: true }).click()
    const firstNumber = await completions[0].finished
    if (loseResponse) {
      await page.getByRole('button', { name: '같은 요청으로 주문 결과 확인', exact: true }).click()
      expect(await completions[1].finished).toBe(firstNumber)
    }
    expect(orderNumber).toMatch(/^O[0-9a-f]{32}$/)
    await expect(page.getByTestId('created-order-number')).toHaveText(orderNumber)
    expect(count).toBe(expectedRequests); await page.unroute('**' + endpoint)
    return orderNumber
  })
  await operation('release: approve payment through UI and verify original-unit amount', async () => {
    await page.getByRole('link', { name: '결제로 이동', exact: true }).click()
    await page.getByRole('button', { name: '모의 결제 승인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '결제가 완료되었습니다', exact: true })).toBeVisible()
    const payment = await read(page.context(), '/api/payments/' + number)
    expect(payment).toMatchObject({ orderNumber: number, orderState: 'PAID', paymentState: 'SUCCEEDED', amount: '13000' })
    type Proof = { order: { state: string; amount: number }; inventory: { status: string }[];
      attempts: { approval_effects: number; success_events: number }[]; shipments: unknown[] }
    await expect.poll(async () => (await observation<Proof>(input.source, '/purchase?orderNumber=' + number)).shipments.length).toBe(1)
    const proof = await observation<Proof>(input.source, '/purchase?orderNumber=' + number)
    expect(proof.order).toMatchObject({ state: 'PAID', amount: 13000 })
    expect(proof.inventory).toEqual([expect.objectContaining({ status: 'COMMITTED' })])
    expect(proof.attempts).toEqual([expect.objectContaining({ approval_effects: 1, success_events: 1 })])
    expect(proof.shipments).toHaveLength(1)
    evidence('release-payment-command', { number, proof })
  })
  // Guest purchase-session authority pays the order; history needs its separate lookup login.
  if (!guest) await purchaseAmounts(page.context(), number)
  evidence('release-orders', { number, productId: fixture.product.id, guest, expectedProduct: '10000', expectedShipping: '3000', expectedTotal: '13000' })
  return number
}
export async function purchaseAmounts(context: BrowserContext, number: string) {
  return operation('release: verify authorized original-unit order amounts', async () => {
    const detail = await read<{ total: string; state: string; stores: { shippingBase: string; items: { unitPrice: string; quantity: number; units: { gross: string; net: string }[] }[] }[] }>(context, '/api/orders/' + number)
    expect(detail).toMatchObject({ total: '13000', state: 'PAID' })
    expect(detail.stores).toHaveLength(1); expect(detail.stores[0].shippingBase).toBe('3000')
    expect(detail.stores[0].items).toEqual([expect.objectContaining({ unitPrice: '10000', quantity: 1,
      units: [expect.objectContaining({ gross: '10000', net: '10000' })] })])
  })
}
export async function retainedOrder(page: Page, number: string) {
  return operation('release: retained production UI navigates actual account order links', async () => {
    await page.getByRole('navigation', { name: '회원 서비스', exact: true }).getByRole('link', { name: '내 계정', exact: true }).click()
    await page.getByRole('link', { name: '주문·결제 이력', exact: true }).click()
    await page.getByRole('link', { name: number, exact: true }).click()
    await expect(page.getByTestId('order-number')).toHaveText(number)
    await expect(page.getByTestId('order-total')).toHaveText('전체 주문 금액 13,000원')
  })
}
export async function observation<T>(stack: Stack, path: string): Promise<T> {
  return operation('release: compare actual database observer evidence', async () => {
    const response = await boundedFetch(stack.observerURL + path, { headers: { 'X-Observer-Key': stack.observerKey } })
    expect(response.status).toBe(200); return response.json() as Promise<T>
  })
}
export async function order(page: Page, number: string, guest = false, retained = false) {
  return operation('release: consume same business ID amount and payment history in UI', async () => {
    if (!retained) await page.goto((guest ? '/guest/orders/' : '/account/orders/') + number)
    else await page.getByRole('button', { name: '주문 상세 다시 조회', exact: true }).click()
    await expect(page.getByTestId('order-number')).toHaveText(number)
    await expect(page.getByTestId('order-total')).toHaveText('전체 주문 금액 13,000원')
    await expect(page.getByTestId('payment-history-state')).toHaveText('결제 승인 성공')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
export async function media(owner: BrowserContext, outsider: BrowserContext, fixture: ProductFixture) {
  return operation('release: preserve public and private media bytes and owner boundary', async () => {
    for (const [assetId, hash] of [[fixture.privateAssetId, fixture.privateHash], [fixture.publicAssetId, fixture.publicHash]]) {
      const own = await owner.request.get('/api/media/owner/' + assetId + '/preview')
      expect(own.status()).toBe(200); expect(digest(await own.body())).toBe(hash)
      expect((await outsider.request.get('/api/media/owner/' + assetId + '/preview')).status()).toBe(404)
    }
    expect((await outsider.request.get('/api/media/' + fixture.privateAssetId + '/content')).status()).toBe(404)
    const publicImage = await outsider.request.get('/api/media/' + fixture.publicAssetId + '/content')
    expect(publicImage.status()).toBe(200); expect(digest(await publicImage.body())).toBe(fixture.publicHash)
  })
}
