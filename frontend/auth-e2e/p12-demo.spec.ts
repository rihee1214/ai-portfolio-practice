import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { headers, verifyDatabase, verifyUI } from './purchase-helpers'

type Actor = { context: BrowserContext; id: string; email: string }
type Product = { id: string; slug: string; title: string; version: number; categoryId: string; description: string; state: string }
type Sku = { id: string; options: { name: string; value: string }[]; price: number; active: boolean; version: number }
type Order = { orderNumber: string; total: string; state: string; stores: number }
const password = 'P12 isolated demonstration password'

async function settled<T>(tasks: Promise<T>[]): Promise<T[]> {
  const results = await Promise.allSettled(tasks)
  const failure = results.find(result => result.status === 'rejected')
  if (failure?.status === 'rejected') throw failure.reason
  return results.map(result => (result as PromiseFulfilledResult<T>).value)
}

async function read<T>(c: BrowserContext, path: string): Promise<T> {
  const response = await c.request.get(path)
  expect(response.status(), path).toBe(200)
  return response.json() as Promise<T>
}
async function change<T>(c: BrowserContext, method: 'POST' | 'PUT', path: string, data: unknown, status = 200): Promise<T> {
  const response = await c.request.fetch(path, { method, headers: await headers(c), data })
  expect(response.status(), path + ' ' + await response.text()).toBe(status)
  return response.json() as Promise<T>
}
async function actor(c: BrowserContext, name: string): Promise<Actor> {
  const email = name + '@example.com'
  await change(c, 'POST', '/api/auth/signup', { email, password }, 201)
  // Only email delivery is local: account verification still uses the public command.
  const mail = await c.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } })
  expect(mail.status()).toBe(200)
  await change(c, 'POST', '/api/auth/verify-email', { token: (await mail.json()).token })
  const login = await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password } })
  expect(login.status()).toBe(200)
  const me = await read<{ id: string; roles: string[] }>(c, '/api/auth/me')
  expect(me.roles).toEqual(['BUYER'])
  return { context: c, id: me.id, email }
}

test('P12 API-produced sellers and buyer retain one trace through storefront purchase and seller purchase', async ({ browser, context, page }, info) => {
  const namespace = 'p12-' + info.project.name + '-' + randomUUID().slice(0, 8)
  const contexts: BrowserContext[] = []
  const fresh = async () => {
    const c = await browser.newContext({ baseURL: info.project.use.baseURL, viewport: page.viewportSize() ?? undefined,
      isMobile: info.project.use.isMobile, hasTouch: info.project.use.hasTouch,
      deviceScaleFactor: info.project.use.deviceScaleFactor, userAgent: info.project.use.userAgent })
    contexts.push(c)
    return c
  }
  const accounts = await fresh(), content = await fresh()
  // Harness bootstrap only: no seller, product, inventory, order or payment fixture is used here.
  try {
  await settled(([[accounts, 'p3-admin-' + info.project.name + '@example.com'], [content, 'c-admin-' + info.project.name + '@example.com']] as const).map(async ([c, email]) => {
    expect((await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200)
  }))
    const category = await change<{ id: string }>(content, 'POST', '/api/admin/content/categories', { name: namespace, active: true, version: 0 }, 201)
    const [ownerA, ownerB, buyer] = await settled([
      fresh().then(c => actor(c, namespace + '-a')),
      fresh().then(c => actor(c, namespace + '-b')),
      actor(context, namespace + '-buyer'),
    ])
    const applications = await settled([{ owner: ownerA, side: 'a' }, { owner: ownerB, side: 'b' }].map(async ({ owner, side }) => {
      const application = await change<{ id: string }>(owner.context, 'POST', '/api/seller/applications', { displayName: namespace + side, description: '새 격리 시연 판매 신청' }, 201)
      return { owner, side, application }
    }))
    // The shared ACCOUNTS session remains sequential; each seller owns its later commands.
    for (const { application } of applications) {
      await change(accounts, 'POST', '/api/admin/accounts/seller-applications/' + application.id + '/decision', { decision: 'APPROVED', reason: 'P12 모의 시연 승인' })
    }
    const sellers = await settled(applications.map(async ({ owner, side }) => {
      expect((await read<{ roles: string[] }>(owner.context, '/api/auth/me')).roles.sort()).toEqual(['BUYER', 'SELLER'])
      const store = await change<{ id: string }>(owner.context, 'POST', '/api/seller/stores', { slug: namespace + '-' + side, name: namespace + '-' + side, introduction: 'API 생산 시연', address: '가상 시연 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 0 }, 201)
      const product = await change<Product>(owner.context, 'POST', '/api/seller/products', { categoryId: category.id, slug: namespace + '-' + side, title: 'P12 상품 ' + side, description: '실제 판매 명령으로 준비한 모의 상품', state: 'DRAFT', version: 0, returnFee: '3000' }, 201)
      const initial = (await read<{ items: Sku[] }>(owner.context, '/api/seller/products/' + product.id + '/skus')).items[0]
      const sku = await change<Sku>(owner.context, 'PUT', '/api/seller/products/' + product.id + '/skus/' + initial.id, { options: initial.options, price: 10000, active: true, version: initial.version })
      const balance = (await read<{ items: { version: number }[] }>(owner.context, '/api/seller/products/' + product.id + '/inventory')).items[0]
      await change(owner.context, 'PUT', '/api/seller/products/' + product.id + '/inventory/' + sku.id, { onHand: 5, version: balance.version, requestId: randomUUID(), reason: 'P12 격리 시연 최초 입고' })
      await change(owner.context, 'PUT', '/api/seller/products/' + product.id, { categoryId: category.id, slug: product.slug, title: product.title, description: product.description, state: 'ACTIVE', version: product.version, returnFee: '3000' })
      const promotionInput = { title: namespace + ' 공개 행사', scope: 'PRODUCT', productIds: [product.id], startsAt: new Date(Date.now() - 60000).toISOString(), endsAt: new Date(Date.now() + 86400000).toISOString(), kind: 'FIXED', value: 1000, cap: null, state: 'DRAFT', version: 0 }
      const promotion = await change<{ id: string; version: number }>(owner.context, 'POST', '/api/seller/promotions', promotionInput, 201)
      await change(owner.context, 'PUT', '/api/seller/promotions/' + promotion.id, { ...promotionInput, state: 'ACTIVE', version: promotion.version })
      return { ...owner, store: store.id, product, sku }
    }))
    const savedAddress = await change<{ id: string }>(context, 'POST', '/api/account/addresses', {
      label: '시연주소'.repeat(10), recipient: 'P12 저장 수령인', phone: '01087654321', postalCode: '01234',
      addressLine1: '실제 주소 API로 저장한 가상 시연 주소', addressLine2: '101호', isDefault: true,
    }, 201)
    for (const seller of sellers) {
      await page.goto('/products/' + seller.product.slug)
      await page.getByRole('button', { name: '장바구니 담기', exact: true }).click()
      await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible()
    }
    await page.goto('/checkout')
    await expect(page.getByTestId('quote-total')).toBeVisible()
    for (const [label, value] of [['주문 구매자 이름', 'P12 구매자'], ['주문 구매자 전화번호', '01012345678']]) {
      await page.getByLabel(label, { exact: true }).fill(value)
    }
    await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
    await page.getByRole('button', { name: '저장 배송지 불러오기' }).click()
    await expect(page.getByRole('option', { name: '시연주소'.repeat(10) + ' · 기본 배송지' })).toBeAttached()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('combobox', { name: '저장 배송지 선택', exact: true }).selectOption(savedAddress.id)
    await expect(page.getByLabel('수령인 이름', { exact: true })).toHaveValue('P12 저장 수령인')
    await expect(page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' })).not.toBeChecked()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
    const ordered = page.waitForResponse(r => new URL(r.url()).pathname === '/api/orders' && r.request().method() === 'POST')
    await page.getByRole('button', { name: '주문 생성', exact: true }).click()
    const response = await ordered
    expect(response.status()).toBe(200)
    const order = await response.json() as Order
    expect(order).toMatchObject({ total: '24000', stores: 2, state: 'PENDING_PAYMENT' })
    await page.getByRole('link', { name: '결제로 이동' }).click()
    await page.getByRole('button', { name: '모의 결제 승인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '결제가 완료되었습니다' })).toBeVisible()
    const db = await read<{ order: { state: string; amount: number }; inventory: { status: string }[]; attempts: { approval_effects: number; success_events: number }[] }>(context, '/api/__test/purchase/' + order.orderNumber + '/database')
    expect(db.order).toMatchObject({ state: 'PAID', amount: 24000 })
    expect(db.inventory).toHaveLength(2)
    expect(db.inventory.every(row => row.status === 'COMMITTED')).toBe(true)
    expect(db.attempts).toEqual([expect.objectContaining({ approval_effects: 1, success_events: 1 })])
    const detail = await read<{ state: string; total: string; contact: Record<string, string> }>(context, '/api/orders/' + order.orderNumber)
    expect(detail).toMatchObject({ state: 'PAID', total: '24000', contact: { buyerName: 'P12 구매자', recipientName: 'P12 저장 수령인', recipientPhone: '+821087654321', postalCode: '01234', addressLine1: '실제 주소 API로 저장한 가상 시연 주소', addressLine2: '101호' } })
    await change(context, 'PUT', '/api/account/addresses/' + savedAddress.id, { label: '변경된 주소', recipient: '다른 수령인', phone: '01011112222', postalCode: '54321', addressLine1: '주문 후 변경 주소', addressLine2: '', isDefault: true })
    expect((await read<{ contact: unknown }>(context, '/api/orders/' + order.orderNumber)).contact).toEqual(detail.contact)
    // Executes the real queued job at current time; no SQL transaction or timestamp is fabricated.
    await change(context, 'POST', '/api/__test/shipments/' + order.orderNumber + '/current-worker', { action: 'prepare' })
    const shipments = await verifyDatabase(context, order.orderNumber)
    expect(shipments).toHaveLength(2)
    for (const owner of sellers) {
      const orders = await read<{ orders: { sellerOrderId: string; orderNumber: string }[] }>(owner.context, '/api/seller/orders')
      const owned = orders.orders.find(row => row.orderNumber === order.orderNumber)
      expect(owned).toBeDefined()
      const screen = await owner.context.newPage()
      await screen.goto('/seller/orders/' + owned!.sellerOrderId)
      await screen.getByRole('checkbox', { name: '원 항목과 수량을 확인했습니다' }).check()
      await screen.getByRole('button', { name: '발송 처리', exact: true }).click()
      await expect(screen.getByText('발송 완료', { exact: true }).first()).toBeVisible()
      await screen.close()
    }
    const shipped = await verifyDatabase(context, order.orderNumber)
    expect(shipped.map(s => s.state)).toEqual(['SHIPPED', 'SHIPPED'])
    await page.goto('/account/orders/' + order.orderNumber)
    await verifyUI(page, shipped)

    // The same seller who created a store, product, stock and promotion now buys from the other store.
    const seller = sellers[0], other = sellers[1]
    const lines = [{ skuId: other.sku.id, quantity: 1 }]
    const quote = await change<{ fingerprint: string; total: string }>(seller.context, 'POST', '/api/quotes', { lines, coupons: [] })
    expect(quote.total).toBe('12000')
    const input = { requestKey: randomUUID(), lines, coupons: [], fingerprint: quote.fingerprint, contact: { buyerName: 'P12 판매자 구매', buyerPhone: '01012345678', recipientName: 'P12 판매자 수령', recipientPhone: '01087654321', postalCode: '01234', addressLine1: '가상 판매자 주소', addressLine2: '102호' }, guestAttemptId: null, linkRevision: null }
    const sellerOrder = await change<Order>(seller.context, 'POST', '/api/orders', input)
    expect(await change<Order>(seller.context, 'POST', '/api/orders', input)).toEqual(sellerOrder)
    expect(sellerOrder.total).toBe('12000')
    await change(seller.context, 'POST', '/api/payments/' + sellerOrder.orderNumber + '/approve', {})
    expect(await read(seller.context, '/api/orders/' + sellerOrder.orderNumber)).toMatchObject({ state: 'PAID', total: '12000' })
    const sellerDb = await read<typeof db>(seller.context, '/api/__test/purchase/' + sellerOrder.orderNumber + '/database')
    expect(sellerDb.order).toMatchObject({ state: 'PAID', amount: 12000 })
    expect(sellerDb.inventory).toEqual([expect.objectContaining({ status: 'COMMITTED' })])
    expect(sellerDb.attempts).toEqual([expect.objectContaining({ approval_effects: 1, success_events: 1 })])
    const sellerPage: Page = await seller.context.newPage()
    await sellerPage.goto('/account/orders/' + sellerOrder.orderNumber)
    await expect(sellerPage.getByTestId('order-number')).toHaveText(sellerOrder.orderNumber)
    expect((await read<{ orders: { orderNumber: string }[] }>(seller.context, '/api/account/orders')).orders.map(o => o.orderNumber)).toContain(sellerOrder.orderNumber)
    expect((await seller.context.request.get('/api/orders/' + order.orderNumber)).status()).toBe(404)
    expect((await context.request.get('/api/orders/' + sellerOrder.orderNumber)).status()).toBe(404)
    expect((await read<{ id: string; roles: string[] }>(seller.context, '/api/auth/me')).id).toBe(seller.id)
    expect((await read<{ items: Product[] }>(seller.context, '/api/seller/products')).items.map(p => p.id)).toContain(seller.product.id)
    const proof = { namespace, mode: 'DEMO', setup: 'Only operators/mailbox are harness bootstrap; all business data uses public API commands', buyer: buyer.id, sellers: sellers.map(s => ({ member: s.id, store: s.store, product: s.product.id, sku: s.sku.id })), memberOrder: order.orderNumber, memberTotal: order.total, sellerBuyer: seller.id, sellerOrder: sellerOrder.orderNumber, sellerTotal: sellerOrder.total, shipments: shipments.map(s => s.id), actualExternalPayment: false }
    writeFileSync(resolve(process.env.A3_EVIDENCE!, namespace + '.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
    await info.attach('P12 API-produced demonstration', { contentType: 'application/json', body: JSON.stringify(proof) })
  } finally { for (const c of contexts) await c.close() }
})
