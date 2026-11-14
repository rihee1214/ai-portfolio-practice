import { operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
test('판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다', async ({ page, browser }, info) => {
    await operation("promotions: 로그인 [1]", async () => {
        await page.goto('/login');
        await page.getByLabel('이메일', { exact: true }).fill('p4b-browse-promotion-browser-' + info.project.name + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
    await operation("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [2]", async () => {
        await page.goto('/seller/promotions');
        await page.getByLabel('행사 이름').fill('상품 할인 ' + info.project.name);
        await page.getByLabel('P05 promotion-browser-' + info.project.name, { exact: true }).check();
        await page.getByLabel('시작 (한국 시간)').fill('2020-01-01T09:00');
        await page.getByLabel('종료 (한국 시간)').fill('2099-01-01T09:00');
    });
    await prepare("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [3]", async () => {
        await page.route('**/api/seller/promotions', async (route) => {
            if (route.request().method() !== 'POST')
                return route.continue();
            const result = await route.fetch();
            expect(result.status()).toBe(201);
            await route.abort();
        }, { times: 1 });
    });
    await operation("promotions: 행사 등록 [4]", async () => {
        await page.getByRole('button', { name: '행사 등록', exact: true }).click();
        await expect(page.getByRole('alert')).toBeVisible();
        await page.getByRole('button', { name: '행사 목록 다시 확인' }).click();
        await expect(page.getByRole('button', { name: '상품 할인 ' + info.project.name + ' · 초안', exact: true })).toHaveCount(1);
    });
    await operation("promotions: 상품 할인  [5]", async () => {
        await page.getByRole('button', { name: '상품 할인 ' + info.project.name + ' · 초안', exact: true }).click();
        await page.getByLabel('행사 상태').selectOption('ACTIVE');
    });
    await prepare("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [6]", async () => {
        await page.route('**/api/seller/promotions/*', async (route) => {
            if (route.request().method() !== 'PUT')
                return route.continue();
            const response = await route.fetch();
            expect(response.status()).toBe(200);
            await route.abort();
        }, { times: 1 });
    });
    const { row } = await operation("promotions: lost promotion publication through server list and saved template", async () => {
        await operation("promotions: 행사 저장 [7]", async () => {
            await page.getByRole('button', { name: '행사 저장', exact: true }).click();
            await expect(page.getByRole('alert')).toBeVisible();
            await page.getByRole('button', { name: '행사 목록 다시 확인' }).click();
        });
        const { row } = await operation("promotions: 상품 할인  [8]", async () => {
            await page.getByRole('button', { name: '상품 할인 ' + info.project.name + ' · 공개', exact: true }).click();
            const row = (await (await page.request.get('/api/seller/promotions')).json()).items[0];
            return { row };
        });
        return { row };
    });
    const { guest } = await prepare("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [9]", async () => {
        const guest = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { guest };
    });
    try {
        const { publicPage } = await prepare("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [10]", async () => {
            const publicPage = await guest.newPage();
            return { publicPage };
        });
        const { csrf, change } = await operation("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [11]", async () => {
            await publicPage.goto('/events/' + row.id);
            await expect(publicPage.getByRole('heading', { name: row.title })).toBeVisible();
            await expect(publicPage.getByText(/2020.*09:00/)).toBeVisible();
            const csrf = await (await page.request.get('/api/auth/csrf')).json();
            const change = { ...row, state: 'STOPPED' };
            return { csrf, change };
        });
        await operation("promotions: /api/seller/promotions/ [12]", async () => {
            expect((await page.request.put('/api/seller/promotions/' + row.id, { headers: { [csrf.headerName]: csrf.token }, data: change })).status()).toBe(200);
        });
        await operation("promotions: 행사 저장 [13]", async () => {
            await page.getByRole('button', { name: '행사 저장', exact: true }).click();
            await expect(page.getByRole('alert')).toBeVisible();
            await page.getByRole('button', { name: '행사 목록 다시 확인' }).click();
        });
        await operation("promotions: 행사 저장 [14]", async () => {
            await page.getByRole('button', { name: row.title + ' · 중지', exact: true }).click();
            await page.getByLabel('행사 상태').selectOption('ARCHIVED');
            await page.getByRole('button', { name: '행사 저장', exact: true }).click();
            await expect(page.getByRole('button', { name: row.title + ' · 보관', exact: true })).toBeVisible();
            await publicPage.reload();
            await expect(publicPage.getByRole('alert')).toBeVisible();
        });
    }
    finally {
        await cleanup("promotions: 판매자가 상품·배송 행사를 공개하고 응답 유실·충돌 뒤 서버 상태를 다시 확인한다 [15]", async () => {
            await guest.close();
        });
    }
    await operation("promotions: 새 행사 [16]", async () => {
        await page.getByRole('button', { name: '새 행사', exact: true }).click();
        await page.getByLabel('행사 이름').fill('배송 할인');
        await page.getByLabel('행사 대상', { exact: true }).selectOption('SHIPPING');
        await page.getByLabel('시작 (한국 시간)').fill('2020-01-01T09:00');
        await page.getByLabel('종료 (한국 시간)').fill('2099-01-01T09:00');
        await page.getByRole('button', { name: '행사 등록', exact: true }).click();
        await expect(page.getByRole('button', { name: '배송 할인 · 초안', exact: true })).toBeVisible();
    });
    await operation('promotions: publish created shipping promotion', async () => {
        await page.getByRole('button', { name: '배송 할인 · 초안', exact: true }).click();
        await page.getByLabel('행사 상태').selectOption('ACTIVE');
        await page.getByRole('button', { name: '행사 저장', exact: true }).click();
        await expect(page.getByRole('button', { name: '배송 할인 · 공개', exact: true })).toBeVisible();
    });
    await operation("promotions: 장바구니 [17]", async () => {
        await page.goto('/cart');
        await expect(page.getByRole('heading', { name: '장바구니', exact: true })).toBeVisible();
    });
});
test('공개 행사 목록은 다음 페이지와 대상 매대 상세를 제공한다', async ({ page }, info) => {
    const { csrf } = await operation("promotions: 로그인 [18]", async () => {
        await page.goto('/login');
        await page.getByLabel('이메일', { exact: true }).fill('p4b-browse-promotion-browser-' + info.project.name + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        const csrf = await (await page.request.get('/api/auth/csrf')).json();
        return { csrf };
    });
    for (let i = 0; i < 21; i++) {
        const { input, row } = await operation("promotions: /api/seller/promotions [19] + final assertions", async () => {
            const { input, create } = await operation("promotions: /api/seller/promotions [19]", async () => {
                const input = { title: '공개 페이지 ' + info.project.name + ' ' + i, scope: 'SHIPPING', productIds: [], startsAt: '2020-01-01T00:00:00Z', endsAt: '2099-01-01T00:00:00Z', kind: 'FREE', value: 0, cap: null, state: 'DRAFT', version: 0 };
                const create = await page.request.post('/api/seller/promotions', { headers: { [csrf.headerName]: csrf.token }, data: input });
                expect(create.status()).toBe(201);
                return { input, create };
            });
            const { row } = await operation("promotions: 공개 행사 목록은 다음 페이지와 대상 매대 상세를 제공한다 [20]", async () => {
                const row = await create.json();
                return { row };
            });
            return { input, row };
        });
        await operation("promotions: /api/seller/promotions/ [21]", async () => {
            expect((await page.request.put('/api/seller/promotions/' + row.id, { headers: { [csrf.headerName]: csrf.token }, data: { ...input, state: 'ACTIVE' } })).status()).toBe(200);
        });
    }
    const { second } = await operation("promotions: /api/events?page=0 [22]", async () => {
        const first = await (await page.request.get('/api/events?page=0')).json(), second = await (await page.request.get('/api/events?page=1')).json();
        expect(first.items).toHaveLength(20);
        expect(first.hasMore).toBe(true);
        expect(second.items.length).toBeGreaterThan(0);
        expect(first.items.some((a: {
            id: string;
        }) => second.items.some((b: {
            id: string;
        }) => a.id === b.id))).toBe(false);
        return { second };
    });
    await operation("promotions: 행사 상세 [23]", async () => {
        await page.goto('/events');
        await expect(page.getByRole('link', { name: '행사 상세', exact: true })).toHaveCount(20);
    });
    await operation("promotions: 다음 행사 [24]", async () => {
        await page.getByRole('button', { name: '다음 행사', exact: true }).click();
        const expectedEvent = page.locator('article').filter({ has: page.locator('a[href="/events/' + second.items[0].id + '"]') });
        await expect(expectedEvent.getByRole('heading', { name: second.items[0].title, exact: true })).toBeVisible();
    });
    await operation("promotions: 행사 상세 [25]", async () => {
        await page.getByRole('link', { name: '행사 상세', exact: true }).first().click();
        await expect(page).toHaveURL(new RegExp('/events/' + second.items[0].id + '$'));
        await expect(page.locator('article')).toHaveCount(1);
        await expect(page.getByRole('heading', { name: second.items[0].title, exact: true })).toBeVisible();
        await expect(page.getByRole('link', { name: second.items[0].storeName, exact: true })).toHaveAttribute('href', '/stores/' + second.items[0].storeSlug);
    });
});
