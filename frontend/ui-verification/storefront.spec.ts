import { expect, operation, prepare, test, type Locator, type Page } from '../auth-e2e/business-deadline'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const before = process.env.UI_STAGE === 'before'
const evidence = resolve(process.env.UI_EVIDENCE!, process.env.UI_STAGE!)
const categoryId = '10000000-0000-4000-8000-000000000001'
const memberId = '10000000-0000-4000-8000-000000000002'
const categories = [{ id: categoryId, name: '생활용품' }]
const products = [
  { slug: 'linen-tote', title: '린넨 장바구니', minPrice: 12000 },
  { slug: 'glass-cup', title: '투명 유리컵', minPrice: 15000 },
  { slug: 'cotton-towel', title: '코튼 타월 세트', minPrice: 18000 },
  { slug: 'ceramic-bowl', title: '도자기 그릇', minPrice: 24000 },
].map(item => ({ ...item, description: item.title, categoryId, categoryName: '생활용품',
  storeSlug: 'daily-store', storeName: '일상상점', soldOut: false,
  returnPolicy: { productId: categoryId, revision: 0, source: 'PRODUCT_DEFAULT_3000', amount: '3000',
    version: 'P09-RETURN-PRODUCT-MAX-1', basis: 'ORIGINAL_SHIPMENT',
    aggregation: 'RETURNED_TARGET_CUMULATIVE_MAX', repeat: 'DELTA_ONLY' },
}))

type Session = 'guest' | 'member' | 'error'

function pendingResponse() {
  let release!: () => void
  const promise = new Promise<void>(resolve => { release = resolve })
  return { promise, release }
}

const primaryLinks = [
  ['둘러보기', '/'], ['상품', '/products'],
] as const
const accountLinks = [
  ['장바구니', '/cart'], ['로그인', '/login'], ['회원가입', '/signup'],
] as const
const guestActions = [['로그인', '/login'], ['회원가입', '/signup']] as const
const guestRoutes = [
  { path: '/account', heading: '내 계정', ready: '로그인이 필요합니다.' },
  { path: '/seller', heading: '매대 운영', ready: '로그인이 필요합니다.' },
  { path: '/admin', heading: '사이트 운영', ready: '로그인이 필요합니다.' },
  { path: '/cart', heading: '장바구니', ready: '장바구니가 비어 있습니다.' },
  { path: '/products/linen-tote', heading: '린넨 장바구니' },
]
const headerRoutes = [...guestRoutes,
  { path: '/products', heading: '상품 둘러보기', ready: '아직 등록된 상품이 없습니다' },
  { path: '/login', heading: '로그인' }, { path: '/signup', heading: '회원가입' },
]

async function fixture(page: Page) {
  const state = {
    session: 'guest' as Session,
    products: false,
    productError: false,
    categoryError: false,
    accountGate: undefined as Promise<void> | undefined,
    productGate: undefined as Promise<void> | undefined,
    queries: [] as Record<string, string>[],
    unexpected: [] as string[],
  }
  const error = { code: 'INTERNAL_ERROR', message: 'Browser test response' }

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    switch (url.pathname) {
      case '/api/runtime-policy':
        await route.fulfill({ json: { mode: 'local-full', restricted: false } }); break
      case '/api/auth/me':
        await state.accountGate
        if (state.session === 'member') await route.fulfill({ json: { id: memberId, roles: ['BUYER'] } })
        else if (state.session === 'error') await route.fulfill({ status: 503, json: error })
        else await route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED', message: 'Guest' } })
        break
      case '/api/cart':
        await route.fulfill({ json: { version: 0, items: [] } }); break
      case '/api/seller/applications':
        await route.fulfill({ json: { items: [], page: 0, hasMore: false } }); break
      case '/api/browse/products/linen-tote':
        await route.fulfill({ json: { product: products[0], offers: [] } }); break
      case '/api/media/public/products/linen-tote':
      case '/api/site/posts':
        await route.fulfill({ json: [] }); break
      case '/api/products/linen-tote/reviews':
        await route.fulfill({ json: { reviews: [], ratingSum: 0, count: 0 } }); break
      case '/api/site/info':
        await route.fulfill({ json: { version: 0, siteName: '모아마켓', supportGuide: '고객지원에 문의해 주세요.' } }); break
      case '/api/site/shipping-policy':
        await route.fulfill({ json: { version: 0, baseFee: 3000, freeThreshold: 50000 } }); break
      case '/api/categories':
        if (state.categoryError) await route.fulfill({ status: 503, json: error })
        else await route.fulfill({ json: { items: categories, page: 0, hasMore: false } })
        break
      case '/api/browse/products': {
        await state.productGate
        const query = Object.fromEntries(url.searchParams)
        state.queries.push(query)
        const number = Number(query.page ?? 0)
        const items = state.products && query.q !== '없는상품' ? (number === 0 ? products.slice(0, 3) : products.slice(3)) : []
        if (state.productError) await route.fulfill({ status: 503, json: error })
        else await route.fulfill({ json: { items, page: number, hasMore: items.length === 3 } })
        break
      }
      default:
        state.unexpected.push(url.pathname)
        await route.abort('blockedbyclient')
    }
  })
  return state
}

async function capture(page: Page, filename: string) {
  const images = join(evidence, 'images')
  await mkdir(images, { recursive: true })
  await page.evaluate(async () => {
    await document.fonts.ready
    window.scrollTo(0, 0)
  })
  await page.screenshot({ path: join(images, filename + '.png'), animations: 'disabled' })
}

async function measure(page: Page, filename: string, selectors: Record<string, string>) {
  const geometry = await page.evaluate(selectors => {
    const elements = Object.fromEntries(Object.entries(selectors).map(([name, selector]) => {
      const bounds = document.querySelector(selector)?.getBoundingClientRect()
      return [name, bounds ? { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height,
        right: bounds.right, bottom: bounds.bottom } : null]
    }))
    return { width: window.innerWidth, height: window.innerHeight, scrollWidth: document.documentElement.scrollWidth, elements }
  }, selectors)
  await writeFile(join(evidence, filename + '.json'), JSON.stringify(geometry, null, 2))
  return geometry
}

async function compareWithBefore(filename: string, metric: 'height' | 'width' | 'y', element: string, actual: number, minimumGain: number) {
  const file = join(evidence, '..', 'before', filename + '.json')
  const baseline = JSON.parse(await readFile(file, 'utf8'))
  expect(baseline.elements[element][metric] - actual).toBeGreaterThanOrEqual(minimumGain)
}

async function expectKeyboardRing(locator: Locator) {
  await expect(locator).toBeFocused()
  expect(await locator.evaluate(element => element.matches(':focus-visible'))).toBe(true)
  const style = await locator.evaluate(element => {
    const style = window.getComputedStyle(element)
    return { outlineStyle: style.outlineStyle, outlineWidth: Number.parseFloat(style.outlineWidth), boxShadow: style.boxShadow }
  })
  expect(style.outlineStyle !== 'none' && style.outlineWidth > 0 || style.boxShadow !== 'none').toBe(true)
}

type LinkContract = readonly (readonly [string, string])[]

async function expectLinkDestinations(group: Locator, contract: LinkContract) {
  await expect(group.getByRole('link')).toHaveText(contract.map(([label]) => label))
  for (const [label, href] of contract) {
    await expect(group.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
  }
}

async function navigationGeometry(page: Page, filename: string) {
  const geometry = await page.evaluate(() => {
    function bounds(element: Element) {
      const { left, top, right, bottom, width, height } = element.getBoundingClientRect()
      return { left, top, right, bottom, width, height }
    }
    function links(selector: string) {
      return Array.from(document.querySelectorAll(selector), element => {
        const range = document.createRange()
        range.selectNodeContents(element)
        const style = getComputedStyle(element)
        return { label: element.textContent, href: element.getAttribute('href'), ...bounds(element),
          textLines: new Set(Array.from(range.getClientRects(), rect => Math.round(rect.top))).size,
          underline: style.textDecorationLine, border: Number.parseFloat(style.borderTopWidth), borderStyle: style.borderTopStyle }
      })
    }
    return { width: window.innerWidth, clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth, header: bounds(document.querySelector('.site-header')!),
      brand: bounds(document.querySelector('.site-header .brand')!),
      primary: links('.site-header .primary-nav a'), account: links('.site-header .account-nav a'),
      member: links('main .member-menu a'),
      guest: links('main .auth-entry-actions a') }
  })
  await writeFile(join(evidence, filename + '.json'), JSON.stringify(geometry, null, 2))
  return geometry
}

type NavigationGeometry = Awaited<ReturnType<typeof navigationGeometry>>
type Bounds = NavigationGeometry['brand']

function centerY(box: Bounds) { return box.top + box.height / 2 }

function expectOneRow(links: NavigationGeometry['primary'], width: number) {
  expect(links.length).toBeGreaterThan(1)
  for (const [index, link] of links.entries()) {
    expect(Math.abs(centerY(link) - centerY(links[0]))).toBeLessThanOrEqual(1)
    expect(link.left).toBeGreaterThanOrEqual(index ? links[index - 1].right : 0)
    expect(link.right).toBeLessThanOrEqual(width)
    expect(link.textLines).toBe(1)
    expect(link.underline).toBe('none')
    expect(link.border).toBeGreaterThan(0)
    expect(link.borderStyle).toBe('solid')
  }
}

function expectHeaderLayout(layout: NavigationGeometry, viewport: number) {
  expect(layout.width).toBe(viewport)
  expect(layout.clientWidth).toBe(viewport)
  expect(layout.scrollWidth).toBe(layout.clientWidth)
  expectOneRow(layout.primary, layout.clientWidth)
  expectOneRow(layout.account, layout.clientWidth)
  const [firstPrimary] = layout.primary
  const [firstAccount] = layout.account
  if (viewport >= 941) {
    expect(Math.abs(centerY(firstAccount) - centerY(firstPrimary))).toBeLessThanOrEqual(1)
    expect(firstAccount.left).toBeGreaterThanOrEqual(layout.primary.at(-1)!.right)
  } else {
    expect(firstAccount.top).toBeGreaterThanOrEqual(firstPrimary.bottom)
  }
  if (viewport >= 561) {
    expect(Math.abs(centerY(layout.brand) - centerY(firstPrimary))).toBeLessThanOrEqual(1)
    expect(firstPrimary.left).toBeGreaterThanOrEqual(layout.brand.right)
  } else {
    expect(firstPrimary.top).toBeGreaterThanOrEqual(layout.brand.bottom)
  }
}

const devices = {
  desktop: { product: { width: 1274, height: 813 }, account: { width: 1296, height: 869 } },
  mobile: { product: { width: 390, height: 844 }, account: { width: 390, height: 844 } },
}

if (!before) {
  for (const width of [320, 390, 1274]) {
    for (const { path, heading, ready } of headerRoutes) {
      test(`navigation preserves visible link rows on ${path} at ${width}px`, async ({ page }) => {
        const state = await prepare('prepare viewport and isolated storefront responses', async () => {
          await page.setViewportSize({ width, height: 844 })
          return fixture(page)
        })
        const main = page.getByRole('main')
        const header = page.getByRole('banner')
        const name = `navigation-${width}-${path.slice(1).replaceAll('/', '-')}`
        const hasGuestActions = guestRoutes.some(route => route.path === path)
        await operation('observe the page and record its navigation geometry', async () => {
          await page.goto(path)
          await expect(main.getByRole('heading', { name: heading, exact: true })).toBeVisible()
          if (ready) await expect(main.getByText(ready)).toBeVisible()
          if (path === '/products/linen-tote') {
            await expect(main.getByRole('region', { name: '구매 정보' }).getByRole('heading', { level: 1 })).toHaveText(heading)
          }
          if (path === '/login' || path === '/signup') await expect(main.getByLabel('이메일', { exact: true })).toBeEnabled()
          await capture(page, name)
          const layout = await navigationGeometry(page, name)
          await expectLinkDestinations(header.getByRole('navigation', { name: '주요 화면' }), primaryLinks)
          await expectLinkDestinations(header.getByRole('navigation', { name: '회원 서비스' }), accountLinks)
          if (path === '/login' || path === '/signup') {
            await expectLinkDestinations(main.getByRole('navigation', { name: '회원 메뉴' }),
              [...guestActions, ['이메일 확인', '/verify-email']])
            expectOneRow(layout.member, layout.clientWidth)
          }
          expectHeaderLayout(layout, width)
          if (hasGuestActions) {
            expectOneRow(layout.guest, layout.clientWidth)
            await expectLinkDestinations(main.locator('.auth-entry-actions'), guestActions)
          }
        })
        if (hasGuestActions) {
          await operation('activate both guest actions with keyboard and pointer', async () => {
            const actions = main.locator('.auth-entry-actions')
            const login = actions.getByRole('link', { name: '로그인', exact: true })
            const signup = actions.getByRole('link', { name: '회원가입', exact: true })
            await login.focus()
            await page.keyboard.press('Tab')
            await expectKeyboardRing(signup)
            await page.keyboard.press('Shift+Tab')
            await expectKeyboardRing(login)
            await login.click()
            await expect(page).toHaveURL(/\/login$/)
            await expect(main.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
            await page.goBack()
            await login.focus()
            await page.keyboard.press('Tab')
            await expectKeyboardRing(signup)
            await page.keyboard.press('Enter')
            await expect(page).toHaveURL(/\/signup$/)
            await expect(main.getByRole('heading', { name: '회원가입', exact: true })).toBeVisible()
          })
        }
        expect(state.unexpected).toEqual([])
      })
    }
  }

  for (const width of [560, 561, 760, 940, 941]) {
    test(`navigation reflows its groups at ${width}px without wrapping links`, async ({ page }) => {
      const state = await prepare('prepare reflow viewport and isolated catalog responses', async () => {
        await page.setViewportSize({ width, height: 844 })
        return fixture(page)
      })
      await operation('verify header reflow and document width at the layout boundary', async () => {
        await page.goto('/products')
        await expect(page.getByText('아직 등록된 상품이 없습니다')).toBeVisible()
        const name = `navigation-${width}-products`
        await capture(page, name)
        expectHeaderLayout(await navigationGeometry(page, name), width)
      })
      expect(state.unexpected).toEqual([])
    })
  }

  for (const width of [320, 1274]) {
    test(`header links can be reached by keyboard and clicked at ${width}px`, async ({ page }) => {
      const state = await prepare('prepare navigation viewport and isolated storefront responses', async () => {
        await page.setViewportSize({ width, height: 844 })
        return fixture(page)
      })
      const header = page.getByRole('banner')
      const brand = ['모아마켓 홈', '/'] as const
      const links = [brand, ...primaryLinks, ...accountLinks]
      await operation('tab from the skip link through each header link in order', async () => {
        await page.goto('/products')
        await page.keyboard.press('Tab')
        await expectKeyboardRing(page.getByRole('link', { name: '본문으로 건너뛰기', exact: true }))
        const primary = header.getByRole('navigation', { name: '주요 화면' })
        const account = header.getByRole('navigation', { name: '회원 서비스' })
        for (const link of [header.getByRole('link', { name: brand[0], exact: true }),
          ...primaryLinks.map(([name]) => primary.getByRole('link', { name, exact: true })),
          ...accountLinks.map(([name]) => account.getByRole('link', { name, exact: true }))]) {
          await page.keyboard.press('Tab')
          await expectKeyboardRing(link)
        }
        await capture(page, `navigation-${width}-header-focus`)
      })
      for (const [name, path] of links) {
        await operation(`activate the ${name} link and open ${path}`, async () => {
          await page.goto('/products')
          await header.getByRole('link', { name, exact: true }).click()
          expect(new URL(page.url()).pathname).toBe(path)
        })
      }
      expect(state.unexpected).toEqual([])
    })
  }
}

for (const [device, viewport] of Object.entries(devices)) {
  test(`products match visible data and compact results at ${device} size`, async ({ page }) => {
    const state = await prepare('prepare product viewport and isolated responses', async () => {
      await page.setViewportSize(viewport.product)
      return fixture(page)
    })
    const main = page.getByRole('main')
    const priorEmpty = '조건에 맞는 상품이 없습니다.'
    const empty = before ? priorEmpty : '아직 등록된 상품이 없습니다'
    const noMatches = before ? priorEmpty : '검색 결과가 없습니다'
    await operation('observe and capture the default empty catalog', async () => {
      await page.goto('/products')
      await expect(main.getByRole('heading', { name: '상품 둘러보기' })).toBeVisible()
      await expect(main.getByText(empty, { exact: true })).toBeVisible()
      await expect(main.locator('.product-card')).toHaveCount(0)
      if (!before) await expect(main.getByRole('navigation', { name: '상품 페이지' })).toHaveCount(0)
      await capture(page, `products-${device}-empty`)
    })
    await operation('observe and capture the filtered no-match catalog', async () => {
      state.products = true
      await page.goto('/products?q=없는상품&sort=newest&page=0')
      await expect(main.getByText(noMatches, { exact: true })).toBeVisible()
      if (!before) await expect(main.getByText(empty, { exact: true })).toHaveCount(0)
      await capture(page, `products-${device}-no-match`)
    })
    await operation('reset the filter and verify the populated catalog', async () => {
      if (!before) {
        await main.getByRole('link', { name: '조건 초기화', exact: true }).click()
        await expect(page).toHaveURL(/\/products$/)
        await expect(main.getByLabel('상품 검색')).toHaveValue('')
      } else await page.goto('/products')
      await expect(main.locator('.product-card')).toHaveCount(3)
      await expect(main.locator('.product-card').first()).toContainText('린넨 장바구니')
      await capture(page, `products-${device}-list`)
      const filename = `products-${device}-geometry`
      const layout = await measure(page, filename, { header: '.site-header', heading: 'main h1',
        search: '#browse-q', form: 'main form', firstCard: 'main .product-card' })
      if (!before) {
        expect(layout.scrollWidth).toBeLessThanOrEqual(layout.width)
        expect(layout.elements.search!.width).toBeGreaterThan(150)
        const columns = await main.locator('.product-grid').evaluate(grid => getComputedStyle(grid).gridTemplateColumns.split(' ').length)
        expect(columns).toBe(device === 'desktop' ? 4 : 2)
        const card = main.locator('.product-card').first()
        const photo = card.getByRole('link', { name: '린넨 장바구니 사진', exact: true })
        const title = card.getByRole('link', { name: '린넨 장바구니', exact: true })
        await expect(photo).toHaveAttribute('href', (await title.getAttribute('href'))!)
        if (device === 'desktop') expect(layout.elements.firstCard!.bottom).toBeLessThan(layout.height)
        await compareWithBefore(filename, 'y', 'firstCard', layout.elements.firstCard!.y, 20)
      }
      expect(state.unexpected).toEqual([])
    })
    if (before) {
      await operation('capture the original price and stock fields', async () => {
        await main.getByLabel('상품 검색', { exact: true }).fill('생활')
        await main.getByLabel('카테고리', { exact: true }).selectOption(categoryId)
        await main.getByLabel('정렬', { exact: true }).selectOption('price_asc')
        await main.getByLabel('최소 가격').fill('10000')
        await main.getByLabel('최대 가격').fill('25000')
        await capture(page, `products-${device}-filters`)
      })
      return
    }

    const query = main.getByLabel('상품 검색', { exact: true })
    const category = main.getByLabel('카테고리', { exact: true })
    const sort = main.getByLabel('정렬', { exact: true })
    const summary = main.getByText('가격·재고 필터', { exact: true })
    const submit = main.getByRole('button', { name: '검색 적용', exact: true })
    await operation('validate product search action and keyboard focus sequence', async () => {
      await expect(submit).toBeInViewport()
      expect((await submit.boundingBox())!.height).toBeGreaterThanOrEqual(34)
      await query.focus()
      await page.keyboard.press('Tab')
      await expectKeyboardRing(category)
      await page.keyboard.press('Tab')
      await expectKeyboardRing(sort)
    })
    await operation('apply category, sort, price and stock and verify the requested result', async () => {
      await query.fill('생활')
      await category.selectOption(categoryId)
      await sort.selectOption('price_asc')
      await summary.click()
      await main.getByLabel('최소 가격').fill('10000')
      await main.getByLabel('최대 가격').fill('25000')
      const stock = main.getByLabel('재고 있는 상품만', { exact: true })
      await expect(stock).toBeVisible()
      const label = main.getByText('재고 있는 상품만', { exact: true })
      const labelText = await label.evaluate(element => {
        const text = [...element.childNodes].find(node => node.nodeType === Node.TEXT_NODE)!
        const range = document.createRange()
        range.selectNode(text)
        const textBox = range.getBoundingClientRect()
        const labelBox = element.getBoundingClientRect()
        return { lines: range.getClientRects().length, position: {
          x: textBox.left + textBox.width / 2 - labelBox.left,
          y: textBox.top + textBox.height / 2 - labelBox.top,
        } }
      })
      expect(labelText.lines).toBe(1)
      expect((await stock.boundingBox())!.width).toBeLessThan(32)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.product.width)
      await capture(page, `products-${device}-filters`)
      await expect(stock).not.toBeChecked()
      await label.click({ position: labelText.position })
      await expect(stock).toBeChecked()
      await submit.click()
      await expect.poll(() => state.queries.at(-1)).toEqual({ q: '생활', category: categoryId, minPrice: '10000',
        maxPrice: '25000', inStock: 'true', sort: 'price_asc', page: '0' })
      await expect(main.locator('.product-card')).toHaveCount(3)
    })
    await operation('advance to the final result page and verify its links', async () => {
      await main.getByRole('button', { name: '다음 상품', exact: true }).click()
      await expect(page).toHaveURL(/page=1/)
      await expect(main.getByRole('link', { name: '도자기 그릇', exact: true })).toBeVisible()
      await expect(main.getByRole('button', { name: '다음 상품', exact: true })).toBeDisabled()
    })
    await operation('return to the first result page and verify the filters persist', async () => {
      await main.getByRole('button', { name: '이전 상품', exact: true }).click()
      await expect(page).toHaveURL(/page=0/)
      await expect(main.getByLabel('재고 있는 상품만')).toBeChecked()
      await expect(main.locator('.product-card')).toHaveCount(3)
    })
    expect(state.unexpected).toEqual([])
  })

  test(`account keeps its guest and member interactions at ${device} size`, async ({ page }) => {
    const state = await prepare('prepare account viewport and isolated responses', async () => {
      await page.setViewportSize(viewport.account)
      return fixture(page)
    })
    const main = page.getByRole('main')
    const memberNav = main.getByRole('navigation', { name: '회원 메뉴' })
    const login = main.getByRole('link', { name: '로그인', exact: true })
    const signup = main.getByRole('link', { name: '회원가입', exact: true })
    await operation('verify and capture the guest account entry', async () => {
      await page.goto('/account')
      await expect(main.getByRole('heading', { name: '내 계정', exact: true })).toBeVisible()
      await expect(main.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible()
      await capture(page, `account-${device}-guest`)
      const filename = `account-${device}-geometry`
      const layout = await measure(page, filename, { header: '.site-header', account: 'main .auth-card', heading: 'main h1',
        login: 'main .auth-card a[href="/login"]', signup: 'main .auth-card a[href="/signup"]' })
      if (before) return
      await expect(memberNav).toHaveCount(0)
      await expect(login).toHaveAttribute('href', '/login')
      await expect(signup).toHaveAttribute('href', '/signup')
      await expect(login).toBeInViewport()
      await expect(signup).toBeInViewport()
      expect(layout.scrollWidth).toBeLessThanOrEqual(layout.width)
      const card = layout.elements.account!
      await compareWithBefore(filename, 'height', 'account', card.height, 20)
      if (device === 'desktop') await compareWithBefore(filename, 'width', 'account', card.width, 100)
      expect(layout.elements.login!.height).toBeGreaterThanOrEqual(36)
      expect(layout.elements.signup!.height).toBeGreaterThanOrEqual(24)
      expect(await login.evaluate(element => getComputedStyle(element).backgroundColor)).not.toBe(
        await signup.evaluate(element => getComputedStyle(element).backgroundColor),
      )
    })
    if (!before) {
      await operation('keyboard-navigate from login to sign-up', async () => {
        await login.focus()
        await page.keyboard.press('Tab')
        await expectKeyboardRing(signup)
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/\/signup$/)
        await expect(main.getByRole('heading', { name: '회원가입', exact: true })).toBeVisible()
      })
      await operation('return to the guest entry and navigate to login', async () => {
        await page.goBack()
        await expect(login).toBeVisible()
        await login.click()
        await expect(page).toHaveURL(/\/login$/)
        await expect(main.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
      })
    }
    await operation('verify and capture the signed-in account', async () => {
      state.session = 'member'
      await page.goto('/account')
      await expect(main.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
      await expect(main.getByText(memberId, { exact: true })).toBeVisible()
      await expect(main.getByRole('button', { name: '로그아웃' })).toBeVisible()
      await expect(memberNav.getByRole('link', { name: '프로필', exact: true })).toHaveAttribute('href', '/account/profile')
      await capture(page, `account-${device}-member`)
      if (!before) expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.account.width)
    })
    expect(state.unexpected).toEqual([])
  })

  test(`products recover from failed responses at ${device} size`, async ({ page }) => {
    const delay = pendingResponse()
    const state = await prepare('prepare failing catalog responses', async () => {
      await page.setViewportSize(viewport.product)
      const state = await fixture(page)
      state.productGate = delay.promise
      state.categoryError = true
      state.productError = true
      state.products = true
      return state
    })
    const main = page.getByRole('main')
    const retry = main.getByRole('button', { name: '상품 다시 불러오기', exact: true })
    const categoryRetry = main.getByRole('button', { name: '카테고리 재시도', exact: true })
    await operation('observe catalog loading then the failed response', async () => {
      try {
        await page.goto('/products')
        await expect(main.getByRole('status')).toHaveText('상품을 불러오고 있습니다.')
        await expect(main.locator('.product-card')).toHaveCount(0)
        await capture(page, `products-${device}-loading`)
      } finally { delay.release() }
      await expect(categoryRetry).toBeVisible()
      await expect(retry).toBeVisible()
      await expect(main.getByRole('alert').filter({ has: page.getByRole('button', { name: '상품 다시 불러오기' }) })).toContainText('다시 시도해 주세요')
      await capture(page, `products-${device}-error`)
    })
    await operation('retry categories and verify the category option', async () => {
      state.categoryError = false
      await categoryRetry.click()
      await expect(main.getByLabel('카테고리').locator('option', { hasText: '생활용품' })).toHaveCount(1)
      await expect(categoryRetry).toHaveCount(0)
    })
    await operation('retry products and verify recovered content', async () => {
      state.productError = false
      await retry.click()
      await expect(main.getByRole('link', { name: '린넨 장바구니', exact: true })).toBeVisible()
      await expect(retry).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.product.width)
    })
    expect(state.unexpected).toEqual([])
  })

  test(`account keeps loading, session error, and recovery separate at ${device} size`, async ({ page }) => {
    const delay = pendingResponse()
    const state = await prepare('prepare pending account response', async () => {
      await page.setViewportSize(viewport.account)
      const state = await fixture(page)
      state.accountGate = delay.promise
      state.session = 'error'
      return state
    })
    const main = page.getByRole('main')
    const localNav = main.getByRole('navigation', { name: '회원 메뉴' })
    const retry = main.getByRole('button', { name: before ? '상태 다시 확인' : '다시 시도', exact: true })
    await operation('observe the loading and failed session states', async () => {
      try {
        await page.goto('/account')
        await expect(main.getByRole('status')).toHaveText('로그인 상태를 확인하고 있습니다.')
        if (!before) await expect(localNav).toHaveCount(0)
        await capture(page, `account-${device}-loading`)
      } finally { delay.release() }
      await expect(main.getByRole('alert')).toContainText('다시 시도해 주세요')
      await expect(retry).toBeInViewport()
      if (!before) {
        await expect(localNav).toHaveCount(0)
        await expect(main.getByText('로그인이 필요합니다.', { exact: true })).toHaveCount(0)
        await expect(main.getByRole('link', { name: '로그인', exact: true })).toHaveCount(0)
      }
      await capture(page, `account-${device}-error`)
    })
    await operation('retry session and verify member content appears', async () => {
      state.session = 'member'
      await retry.click()
      await expect(main.getByText('로그인되었습니다.', { exact: true })).toBeVisible()
      await expect(localNav).toBeVisible()
      await expect(main.getByRole('alert')).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.account.width)
    })
    expect(state.unexpected).toEqual([])
  })
}
