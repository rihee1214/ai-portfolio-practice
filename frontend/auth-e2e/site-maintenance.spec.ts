import { randomUUID, createHash } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { test, expect, operation, prepare, cleanup, bindDeadline, currentDeadline, type BrowserContext, type Page } from './business-deadline'
import { headers, login } from './purchase-helpers'
import type { Quote } from '../src/promotion/QuoteStore'

type Identity = { databaseName: string; project: string; pid: number; apiPort: number; media: string; initialPolicy: { version: number; baseFee: number; freeThreshold: number }; members: Record<string,string> }
type Row = Record<string, unknown>
type Database = Identity & { tables: Record<string, Row[]>; matchingPolicy: Row[]; matchingPosts: Row[]; matchingInfo: Row[] }
type Product = { id: string; slug: string; title: string; description: string; version: number }
type Sku = { id: string; options: { name: string; value: string }[]; version: number }
type Order = { id: string; orderNumber: string; state: string; total: string; stores: number }
const password = ' A1-private-passphrase-가나다 '
const contact = { buyerName: '정책 구매자', buyerPhone: '01012345678', recipientName: '정책 수령인', recipientPhone: '01087654321', postalCode: '01234', addressLine1: '격리 정책 검증 주소', addressLine2: '101호' }
const financialTables = ['purchase_order','seller_order','order_item','order_unit','order_contact','order_checkout_attempt','order_event','order_reservation','order_aftercare_policy','order_settlement_policy','payment_attempt','payment_event','payment_mock','payment_refund_budget','payment_refund_attempt','payment_refund_event','inventory_reservation','inventory_reservation_event','coupon_reservation','coupon_reservation_event','coupon_restoration','settlement_ledger']

async function get<T>(c: BrowserContext, path: string): Promise<T> {
  const response = await c.request.get(path)
  expect(response.status(), path + ' ' + await response.text()).toBe(200)
  expect(response.headers()['cache-control']).toContain('no-store')
  return response.json() as Promise<T>
}
async function change<T>(c: BrowserContext, method: 'POST'|'PUT', path: string, data: unknown, status = 200): Promise<T> {
  const response = await c.request.fetch(path, { method, headers: await headers(c), data })
  expect(response.status(), path + ' ' + await response.text()).toBe(status)
  return response.json() as Promise<T>
}
async function signIn(c: BrowserContext, actor: string, project: string) {
  return operation('site-maintenance: authenticate owned actor', async () => {
    expect((await c.request.post('/api/auth/login', { headers: await headers(c), form: { email: `p12b-${actor}-${project}@example.com`, password } })).status()).toBe(200)
  })
}
function sameOwner(actual: Identity, expected: Identity) {
  expect(actual).toMatchObject({ databaseName: expected.databaseName, project: expected.project, pid: expected.pid, apiPort: expected.apiPort, media: expected.media, members: expected.members })
}
function financial(db: Database) {
  for(const table of financialTables)expect(Array.isArray(db.tables[table]), 'complete financial observer table '+table).toBe(true)
  return Object.fromEntries(financialTables.map(table => [table, db.tables[table]]))
}
async function screenshot(page: Page, path: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path, scale: 'css' })
}

test('SYSTEM policy UI advances new quotes while stale confirmation and paid snapshots stay protected', async ({ browser, context, page }, info) => {
  const project = info.project.name, namespace = 'p12b-' + project + '-' + randomUUID().slice(0,8)
  const { system, content, seller, admin } = await prepare('site-maintenance: create isolated policy sessions', async () => {
    const options = { baseURL: 'http://127.0.0.1:24176', ...info.project.use }
    const system = await browser.newContext(options), content = await browser.newContext(options), seller = await browser.newContext(options)
    return { system, content, seller, admin: await system.newPage() }
  })
  const trace: unknown[] = []
  try {
    await signIn(system, 'system', project); await signIn(content, 'content', project); await signIn(seller, 'seller', project)
    await prepare('site-maintenance: establish buyer purchase session', async () => login(page, `p12b-buyer-${project}@example.com`))
    const owner = await prepare('site-maintenance: confirm owned initial policy', async () => {
      const identity = await get<Identity>(context, '/api/__test/site-maintenance/identity')
      expect(identity.project).toBe(project); expect(identity.apiPort).toBe(Number(process.env.A3_API_PORT))
      expect(identity.initialPolicy).toEqual({ version: 1, baseFee: 3000, freeThreshold: 50000 })
      expect(await get(context, '/api/site/shipping-policy')).toMatchObject(identity.initialPolicy)
      return identity
    })
    await operation('site-maintenance: verify observation is read-only and rejects unrelated identifiers', async () => {
      const before=await get<Database>(context,'/api/__test/site-maintenance/database')
      expect((await context.request.get('/api/__test/site-maintenance/database?orderNumber=O'+'0'.repeat(32))).status()).toBe(404)
      expect((await context.request.get('/api/__test/site-maintenance/database?requestKey='+randomUUID())).status()).toBe(404)
      expect((await context.request.post('/api/__test/site-maintenance/database',{headers:await headers(context),data:{baseFee:1}})).status()).toBe(403)
      const after=await get<Database>(context,'/api/__test/site-maintenance/database'); sameOwner(after,owner); expect(after.tables).toEqual(before.tables)
    })
    const category = await operation('site-maintenance: create category through content API', async () => change<{id:string}>(content, 'POST', '/api/admin/content/categories', { name: namespace, active: true, version: 0 }, 201))
    await operation('site-maintenance: create seller store', async () => change(seller, 'POST', '/api/seller/stores', { slug: namespace, name: namespace, introduction: '실제 API 생산 매대', address: '가상 검증 주소', notice: '', imageKey: '', imageAlt: '', visible: true, version: 0 }, 201))
    const { product, initial } = await operation('site-maintenance: create draft product and inspect initial SKU', async () => {
      const product = await change<Product>(seller, 'POST', '/api/seller/products', { categoryId: category.id, slug: namespace, title: '정책 검증 상품', description: '실제 판매 명령', state: 'DRAFT', version: 0, returnFee: '3000' }, 201)
      const initial = (await get<{items:Sku[]}>(seller, '/api/seller/products/' + product.id + '/skus')).items[0]
      return { product, initial }
    })
    const sku = await operation('site-maintenance: set actual SKU price', async () => change<Sku>(seller, 'PUT', `/api/seller/products/${product.id}/skus/${initial.id}`, { options: initial.options, price: 49999, active: true, version: initial.version }))
    await operation('site-maintenance: receive actual inventory', async () => {
      const balance = (await get<{items:{version:number}[]}>(seller, `/api/seller/products/${product.id}/inventory`)).items[0]
      await change(seller, 'PUT', `/api/seller/products/${product.id}/inventory/${sku.id}`, { onHand: 5, version: balance.version, requestId: randomUUID(), reason: '정책 검증 최초 입고' })
    })
    await operation('site-maintenance: publish actual product', async () => change(seller, 'PUT', '/api/seller/products/' + product.id, { categoryId: category.id, slug: product.slug, title: product.title, description: product.description, state: 'ACTIVE', version: product.version, returnFee: '3000' }))
    const lines = [{ skuId: sku.id, quantity: 1 }]
    const old = await operation('site-maintenance: create original order from actual quote', async () => {
      const quote = await change<Quote>(context, 'POST', '/api/quotes', { lines, coupons: [] })
      expect(quote).toMatchObject({ total: '52999', policyVersion: 'P07-C-1' })
      const order = await change<Order>(context, 'POST', '/api/orders', { lines, coupons: [], fingerprint: quote.fingerprint, contact, guestAttemptId: null, linkRevision: null, requestKey: randomUUID() })
      expect(order).toMatchObject({ state: 'PENDING_PAYMENT', total: '52999', stores: 1 })
      trace.push({ oldQuote: quote, oldOrder: order }); return order
    })
    const before = await operation('site-maintenance: approve original payment and capture durable financial rows', async () => {
      await change(context, 'POST', '/api/payments/' + old.orderNumber + '/approve', {})
      expect(await get(context, '/api/orders/' + old.orderNumber)).toMatchObject({ state: 'PAID', total: '52999', policyVersion: 'P07-C-1' })
      const db = await get<Database>(context, '/api/__test/site-maintenance/database?orderNumber=' + old.orderNumber)
      sameOwner(db, owner); expect(db.tables.purchase_order).toHaveLength(1)
      expect(db.tables.purchase_order[0]).toMatchObject({id:old.id,state:'PAID',amount:52999})
      expect(db.tables.order_unit).toHaveLength(1); expect(db.tables.payment_attempt).toHaveLength(1)
      expect(db.tables.inventory_reservation).toHaveLength(1); expect(db.tables.order_settlement_policy).toHaveLength(1)
      trace.push({ originalPaidDatabase: db }); return financial(db)
    })
    await operation('site-maintenance: add current product to buyer cart', async () => {
      await page.goto('/products/' + namespace); await page.getByRole('button', { name: '장바구니 담기', exact: true }).click()
      await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible()
    })
    const stale = await operation('site-maintenance: open original checkout quote', async () => {
      const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/quotes' && r.request().method() === 'POST' && r.status() === 200)
      await page.goto('/checkout'); await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 52,999원')
      return (await response).json() as Promise<Quote>
    })
    await prepare('site-maintenance: enter shipping and policy draft', async () => {
      for (const [label,value] of Object.entries({ '주문 구매자 이름': contact.buyerName, '주문 구매자 전화번호':contact.buyerPhone, '수령인 이름':contact.recipientName, '수령인 전화번호':contact.recipientPhone, '우편번호':contact.postalCode, '기본 주소':contact.addressLine1, '상세 주소':contact.addressLine2 })) await page.getByLabel(label, { exact: true }).fill(value)
      await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
      await admin.goto('/admin/settings'); await expect(admin.getByLabel('기본 배송비 (원)')).toHaveValue('3000')
      await admin.getByLabel('기본 배송비 (원)').fill('4567'); await admin.getByLabel('무료배송 기준 (원)').fill('51000')
      await admin.getByLabel('변경 사유').fill('전향 정책 변경 ' + '긴사유'.repeat(80))
    })
    await operation('site-maintenance: reject CONTENT shipping change without database effects', async () => {
      const denied = await content.request.post('/api/admin/system/shipping-policy', { headers: await headers(content), data: { key: randomUUID(), expectedVersion: 1, baseFee: 9999, freeThreshold: 1, reason: 'CONTENT 금지 확인' } })
      expect(denied.status()).toBe(403)
      expect(await get(context, '/api/site/shipping-policy')).toMatchObject({ version:1, baseFee:3000, freeThreshold:50000 })
    })
    await operation('site-maintenance: save policy UI and recover original lost response', async () => {
      const payloads: Row[] = [], replies: unknown[] = []
      const handler = bindDeadline(async (route: import('@playwright/test').Route) => {
        if (route.request().method() !== 'POST') { await route.fallback(); return }
        payloads.push(route.request().postDataJSON() as Row)
        const response = await route.fetch({ timeout:currentDeadline().remaining(10000) }); expect(response.status()).toBe(200); replies.push(await response.json())
        if (payloads.length === 1) await route.abort('failed'); else await route.fulfill({ response })
      })
      await admin.route('**/api/admin/system/shipping-policy', handler)
      await admin.getByRole('button', { name: '설정 저장', exact: true }).dblclick()
      await expect(admin.getByRole('button', { name:'같은 요청 다시 확인' })).toBeVisible()
      await expect(admin.getByLabel('기본 배송비 (원)')).toBeDisabled(); expect(payloads).toHaveLength(1)
      await admin.getByRole('button', { name:'같은 요청 다시 확인' }).click()
      await expect(admin.getByRole('list', { name:'배송 정책 이력' })).toContainText('버전 2')
      expect(payloads).toHaveLength(2); expect(payloads[1]).toEqual(payloads[0]); expect(replies[1]).toEqual(replies[0])
      expect(payloads[0]).toMatchObject({ expectedVersion:1, baseFee:4567, freeThreshold:51000 })
      expect(Object.keys(payloads[0]).sort()).toEqual(['baseFee','expectedVersion','freeThreshold','key','reason'])
      const db = await get<Database>(context, `/api/__test/site-maintenance/database?orderNumber=${old.orderNumber}&requestKey=${payloads[0].key}`)
      sameOwner(db, owner); expect(db.matchingPolicy).toHaveLength(1); expect(db.tables.site_shipping_policy).toHaveLength(2)
      expect(db.matchingPolicy[0]).toMatchObject({ version:Number(payloads[0].expectedVersion)+1, base_fee:payloads[0].baseFee, free_threshold:payloads[0].freeThreshold,
        request_key:payloads[0].key, reason:payloads[0].reason, actor_id:owner.members.system })
      expect(db.tables.site_shipping_head).toEqual([{ singleton:true, version:db.matchingPolicy[0].version }])
      expect(financial(db)).toEqual(before)
      expect(await get(context, '/api/site/shipping-policy')).toMatchObject({ version:2, baseFee:4567, freeThreshold:51000 })
      await screenshot(admin, info.outputPath('policy-long-history.png'))
      trace.push({ policyPayloads:payloads, policyReplies:replies, policyDatabase:db }); await admin.unroute('**/api/admin/system/shipping-policy', handler)
    })
    await operation('site-maintenance: reject old quote at the actual order UI', async () => {
      const result = page.waitForResponse(r => new URL(r.url()).pathname === '/api/orders' && r.request().method() === 'POST')
      await page.getByRole('button', { name:'주문 생성', exact:true }).click()
      const response = await result; expect(response.status()).toBe(409)
      expect(response.request().postDataJSON().fingerprint).toBe(stale.fingerprint)
      await expect(page.getByRole('alert')).toContainText('견적이나 구매 상태가 변경되었습니다')
      await expect(page.getByTestId('created-order-number')).toHaveCount(0)
      expect(financial(await get<Database>(context, '/api/__test/site-maintenance/database?orderNumber=' + old.orderNumber))).toEqual(before)
    })
    const newQuote = await operation('site-maintenance: explicitly refresh quote and require new buyer confirmation', async () => {
      const result = page.waitForResponse(r => new URL(r.url()).pathname === '/api/quotes' && r.request().method() === 'POST' && r.status() === 200)
      await page.getByRole('button', { name:'새 견적을 확인하고 다시 주문 준비' }).click()
      const quote = await (await result).json() as Quote
      expect(quote).toMatchObject({ total:'54566', policyVersion:'SHIP-2-4567-51000' }); expect(quote.fingerprint).not.toBe(stale.fingerprint)
      expect(quote.stores[0]).toMatchObject({ subtotal:'49999', shippingBase:'4567', shippingDiscount:'0', total:'54566' })
      await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 54,566원')
      await expect(page.getByRole('checkbox', { name:'현재 상품·수량·견적과 배송 정보를 확인했습니다' })).not.toBeChecked()
      await expect(page.getByRole('button', { name:'주문 생성', exact:true })).toBeDisabled(); trace.push({ newQuote:quote })
      return quote
    })
    await operation('site-maintenance: create explicitly reconfirmed order with new financial terms', async () => {
      await page.getByRole('checkbox', { name:'현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check()
      const result = page.waitForResponse(r => new URL(r.url()).pathname === '/api/orders' && r.request().method() === 'POST')
      await page.getByRole('button', { name:'주문 생성', exact:true }).click()
      const response = await result; expect(response.status()).toBe(200); const order = await response.json() as Order
      const command = response.request().postDataJSON() as { requestKey:string; fingerprint:string; lines:{skuId:string;quantity:number}[]; coupons:unknown[] }
      expect(command.fingerprint).toBe(newQuote.fingerprint); expect(command.lines).toEqual(lines); expect(command.coupons).toEqual([])
      expect(order).toMatchObject({ state:'PENDING_PAYMENT', total:'54566', stores:1 }); expect(order.orderNumber).not.toBe(old.orderNumber)
      await expect(page.getByTestId('created-order-number')).toHaveText(order.orderNumber)
      expect(await get(context, '/api/orders/' + order.orderNumber)).toMatchObject({ total:'54566', policyVersion:'SHIP-2-4567-51000' })
      const created = await get<Database>(context, '/api/__test/site-maintenance/database?orderNumber=' + order.orderNumber)
      sameOwner(created,owner); expect(created.tables.purchase_order).toHaveLength(1)
      const persisted = created.tables.purchase_order[0]
      expect(persisted).toMatchObject({ id:order.id, order_number:order.orderNumber, member_id:owner.members.buyer,
        amount:Number(newQuote.total), state:order.state, policy_version:newQuote.policyVersion })
      expect(created.tables.order_checkout_attempt).toHaveLength(1)
      expect(created.tables.order_checkout_attempt[0]).toMatchObject({ id:persisted.checkout_id, request_key:command.requestKey })
      expect(persisted.quote_snapshot).toMatchObject({ fingerprint:command.fingerprint, policyVersion:newQuote.policyVersion, memberId:owner.members.buyer, total:Number(newQuote.total),
        stores:[{ storeId:newQuote.stores[0].storeId, subtotal:Number(newQuote.stores[0].subtotal), shippingBase:Number(newQuote.stores[0].shippingBase),
          shippingDiscount:Number(newQuote.stores[0].shippingDiscount), total:Number(newQuote.stores[0].total), lines:[{skuId:command.lines[0].skuId,quantity:command.lines[0].quantity}] }] })
      const oldAfter = await get<Database>(context, '/api/__test/site-maintenance/database?orderNumber=' + old.orderNumber)
      sameOwner(oldAfter, owner); expect(financial(oldAfter)).toEqual(before)
      trace.push({ newOrder:order, newOrderCommand:command, newOrderDatabase:created, oldFinancialAfter:oldAfter }); await screenshot(page, info.outputPath('new-policy-order.png'))
    })
  } finally {
    writeFileSync(info.outputPath('policy-api-database-trace.json'), JSON.stringify(trace,null,2))
    await cleanup('site-maintenance: close policy sessions', async () => { await system.close(); await content.close(); await seller.close() })
  }
})

test('CONTENT banner upload and SYSTEM site-info UI persist exact public values with failure recovery', async ({ browser, context, page }, info) => {
  const project = info.project.name, title = '실제 배너 ' + project, siteName = '실제 사이트 ' + project
  const guide = '<script>문자 그대로의 지원 안내</script>\n' + '좁은 화면에서도 확인할 긴안내'.repeat(35)
  const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
  const { system, content, editor } = await prepare('site-maintenance: create isolated publication sessions', async () => {
    const system = await browser.newContext({ baseURL:'http://127.0.0.1:24176', ...info.project.use }), content = await browser.newContext({ baseURL:'http://127.0.0.1:24176', ...info.project.use })
    return { system, content, editor:await content.newPage() }
  })
  const trace: unknown[] = []
  try {
    await signIn(system,'system',project); await signIn(content,'content',project)
    const {owner,initialInfo} = await prepare('site-maintenance: establish independent site-info preconditions', async () => {
      const owner = await get<Identity>(context, '/api/__test/site-maintenance/identity')
      expect(owner.project).toBe(project); expect(owner.apiPort).toBe(Number(process.env.A3_API_PORT))
      // This scenario neither consumes nor assumes M1's policy version or money values.
      const initialInfo = await get<{version:number;siteName:string;supportGuide:string}>(context, '/api/site/info')
      expect(initialInfo.version).toBe(1)
      trace.push({ owner }); await editor.goto('/admin/publications')
      await editor.getByRole('combobox', { name:'종류', exact:true }).selectOption('BANNER'); await editor.getByRole('button', { name:'새 배너 작성' }).click()
      await editor.getByLabel('제목', { exact:true }).fill(title); await editor.getByLabel('내용', { exact:true }).fill('공개 배너 원문')
      await editor.getByLabel('대체 텍스트').fill('공개 배너 대체텍스트 ' + '긴문구'.repeat(35)); await editor.getByLabel('내부 이동 주소').fill('/faq')
      await editor.getByLabel('배너 이미지').setInputFiles({ name:'maintenance.png', mimeType:'image/png', buffer:image })
      await editor.getByLabel('게시 상태').selectOption('PUBLISHED'); await editor.getByLabel('정렬 순서').fill('7'); await editor.getByLabel('변경 사유').fill('배너 실제 업로드')
      return {owner,initialInfo}
    })
    await operation('site-maintenance: upload banner UI and recover the same multipart request', async () => {
      const commands: Row[] = [], bodies: string[] = [], replies: Row[] = []
      const handler = bindDeadline(async (route: import('@playwright/test').Route) => {
        if (route.request().method() !== 'POST') { await route.fallback(); return }
        const body = route.request().postDataBuffer()!.toString('utf8'); bodies.push(body)
        const match = /\r\n\r\n(\{"key"[\s\S]+?\})\r\n--/.exec(body); expect(match).not.toBeNull(); commands.push(JSON.parse(match![1]) as Row)
        const response = await route.fetch({ timeout:currentDeadline().remaining(10000) }); expect(response.status()).toBe(200); replies.push(await response.json() as Row)
        if(commands.length===1)await route.abort('failed');else await route.fulfill({response})
      })
      await editor.route('**/api/admin/content/site-posts/*', handler)
      await editor.getByRole('button', { name:'게시 설정 저장' }).dblclick(); await expect(editor.getByRole('button', { name:'같은 요청 다시 확인' })).toBeVisible()
      expect(commands).toHaveLength(1); await expect(editor.getByLabel('제목', {exact:true})).toBeDisabled()
      await editor.getByRole('button', { name:'같은 요청 다시 확인' }).click(); await expect(editor.getByRole('button', { name:title+' · PUBLISHED' })).toBeVisible()
      expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]); expect(replies[1]).toEqual(replies[0])
      expect(commands[0]).toMatchObject({ expectedVersion:0,kind:'BANNER',state:'PUBLISHED',title,priority:7,destination:'/faq',body:'공개 배너 원문' })
      const post = replies[0], db = await get<Database>(context, '/api/__test/site-maintenance/database?requestKey=' + commands[0].key)
      sameOwner(db,owner)
      expect(db.matchingPosts).toHaveLength(1); expect(db.tables.site_post).toHaveLength(1); expect(db.tables.media_site_image).toHaveLength(1)
      expect(db.matchingPosts[0]).toMatchObject({ post_id:post.id,version:1,kind:'BANNER',state:'PUBLISHED',title,body:'공개 배너 원문',priority:7,asset_id:post.asset })
      expect(await get(context, '/api/site/posts/' + post.id)).toEqual(post)
      const loaded = await context.request.get(`/api/site/posts/${post.id}/images/${post.asset}`)
      const preview = await content.request.get(`/api/admin/content/site-posts/${post.id}/images/${post.asset}`)
      expect(loaded.status()).toBe(200); expect(preview.status()).toBe(200)
      const publicBytes=await loaded.body(), previewBytes=await preview.body()
      expect(publicBytes.subarray(0,8).toString('hex')).toBe('89504e470d0a1a0a')
      const publicImageHash=createHash('sha256').update(publicBytes).digest('hex')
      expect(publicImageHash).toBe(createHash('sha256').update(previewBytes).digest('hex'))
      await page.goto('/'); await expect(page.getByRole('img', {name:String(post.alt),exact:true})).toBeVisible()
      expect(await page.getByRole('img',{name:String(post.alt),exact:true}).evaluate(image=>(image as HTMLImageElement).naturalWidth)).toBe(1)
      await expect(page.getByRole('link').filter({has:page.getByRole('img',{name:String(post.alt),exact:true})})).toHaveAttribute('href','/faq')
      await screenshot(page,info.outputPath('public-uploaded-banner.png'))
      trace.push({ commands,replies,multipartBodies:bodies,bannerDatabase:db,publicImageHash,uploadedSourceHash:createHash('sha256').update(image).digest('hex') }); await editor.unroute('**/api/admin/content/site-posts/*',handler)
    })
    await operation('site-maintenance: reject CONTENT site-info command with unchanged rows', async () => {
      const before = await get<Database>(context,'/api/__test/site-maintenance/database')
      const response = await content.request.post('/api/admin/system/site-info',{headers:await headers(content),data:{key:randomUUID(),expectedVersion:1,siteName:'권한없는이름',supportGuide:'권한없는안내',reason:'CONTENT 금지 확인'}})
      expect(response.status()).toBe(403)
      const after = await get<Database>(context,'/api/__test/site-maintenance/database'); expect(after.tables.site_info_revision).toEqual(before.tables.site_info_revision)
    })
    const admin = await prepare('site-maintenance: enter unsaved site-info text', async () => {
      const admin = await system.newPage(); await admin.goto('/admin/settings'); await admin.getByLabel('설정 종류').selectOption('site-info')
      await admin.getByLabel('사이트 이름', {exact:true}).fill('폐기되어야 할 편집'); await admin.getByLabel('고객지원 안내').fill('접근 거부 전 비공개 초안'); await admin.getByLabel('변경 사유').fill('HTTP 거부 폐기 검증')
      return admin
    })
    await operation('site-maintenance: discard editor fields after injected HTTP 403', async () => {
      // The actual CONTENT denial above proves server permission; this controlled response proves the open editor's 403 consumer contract.
      const handler = bindDeadline(async (route: import('@playwright/test').Route) => {
        if(route.request().method()!=='POST'){await route.fallback();return}
        await route.fulfill({status:403,contentType:'application/json',body:'{"error":"FORBIDDEN"}'})
      })
      await admin.route('**/api/admin/system/site-info',handler)
      await admin.getByRole('button',{name:'설정 저장',exact:true}).click()
      await expect(admin.getByLabel('설정 종류')).toHaveValue('shipping-policy')
      await admin.getByLabel('설정 종류').selectOption('site-info')
      await expect(admin.getByLabel('사이트 이름',{exact:true})).toHaveValue(initialInfo.siteName)
      await expect(admin.getByLabel('고객지원 안내')).toHaveValue(initialInfo.supportGuide)
      await expect(admin.getByLabel('변경 사유')).toHaveValue(''); await expect(admin.getByRole('button',{name:'같은 요청 다시 확인'})).toHaveCount(0)
      expect(await get(context,'/api/site/info')).toMatchObject({version:1}); await admin.unroute('**/api/admin/system/site-info',handler)
    })
    await prepare('site-maintenance: enter new explicit site-info draft', async () => {
      await admin.getByLabel('설정 종류').selectOption('site-info'); await admin.getByLabel('사이트 이름',{exact:true}).fill(siteName)
      await admin.getByLabel('고객지원 안내').fill(guide); await admin.getByLabel('변경 사유').fill('새로 확인한 공개 안내 ' + '긴사유'.repeat(70))
    })
    await operation('site-maintenance: save recovered site-info UI through exact public and database values', async () => {
      const response = admin.waitForResponse(r=>new URL(r.url()).pathname==='/api/admin/system/site-info'&&r.request().method()==='POST')
      await admin.getByRole('button',{name:'설정 저장',exact:true}).click(); const result = await response
      expect(result.status()).toBe(200); const command = result.request().postDataJSON() as Row
      expect(command).toMatchObject({expectedVersion:1,siteName,supportGuide:guide}); expect(Object.keys(command).sort()).toEqual(['expectedVersion','key','reason','siteName','supportGuide'])
      await expect(admin.getByRole('list',{name:'사이트 안내 이력'})).toContainText('버전 2')
      expect(await get(context,'/api/site/info')).toEqual({version:2,siteName,supportGuide:guide})
      const db = await get<Database>(context,'/api/__test/site-maintenance/database?requestKey='+command.key)
      sameOwner(db,owner)
      expect(db.matchingInfo).toHaveLength(1); expect(db.tables.site_info_revision).toHaveLength(2)
      expect(db.matchingInfo[0]).toMatchObject({version:2,site_name:siteName,support_guide:guide,reason:command.reason,request_key:command.key})
      await page.goto('/'); await expect(page.getByRole('heading',{name:siteName,exact:true})).toBeVisible(); await expect(page.getByText(guide,{exact:true})).toBeVisible()
      await screenshot(page,info.outputPath('public-site-info-long-body.png')); trace.push({ siteInfoCommand:command,siteInfoReply:await result.json(),siteInfoDatabase:db })
    })
  } finally {
    writeFileSync(info.outputPath('publication-api-database-trace.json'),JSON.stringify(trace,null,2))
    await cleanup('site-maintenance: close publication sessions',async()=>{await system.close();await content.close()})
  }
})
