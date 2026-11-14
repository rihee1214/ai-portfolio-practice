import { operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
test('서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다', async ({ page, browser }, info) => {
    const prefix = 'p4b-browse-quote-browser-' + info.project.name;
    for (const side of ['a', 'b']) {
        await operation("quotes: 장바구니 담기 [1]", async () => {
            await page.goto('/products/' + prefix + '-' + side);
            await expect(page.getByRole('button', { name: '장바구니 담기', exact: true })).toBeEnabled();
        });
        await operation("quotes: 장바구니 담기 [2]", async () => {
            await page.getByRole('button', { name: '장바구니 담기', exact: true }).click();
            await expect(page.getByText('방문자 장바구니를 변경했습니다.')).toBeVisible();
        });
    }
    await operation("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [3]", async () => {
        await page.goto('/checkout');
        await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 76,000원');
        await expect(page.getByLabel('적용 쿠폰', { exact: false })).toHaveCount(0);
    });
    await operation("quotes: 로그인 [4]", async () => {
        await page.goto('/login');
        await page.getByLabel('이메일', { exact: true }).fill('quote-member-' + info.project.name + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
    const { select, quoted } = await operation("quotes: /api/quotes [5]", async () => {
        await page.goto('/checkout');
        await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 76,000원');
        const select = page.getByLabel('적용 쿠폰 — ' + prefix + '-a', { exact: true });
        await expect(select).toBeEnabled();
        await expect(select.locator('option')).toHaveCount(2);
        const quoted = page.waitForResponse(r => r.url().endsWith('/api/quotes') && r.request().method() === 'POST');
        return { select, quoted };
    });
    const { old } = await operation("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [6]", async () => {
        await select.selectOption({ label: '견적 3000원' });
        const old = await (await quoted).json();
        expect(old.total).toBe('73000');
        await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 73,000원');
        return { old };
    });
    const { seller } = await prepare("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [7]", async () => {
        const seller = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { seller };
    });
    try {
        const { csrf, product, sku } = await operation("quotes: /api/auth/login [8]", async () => {
            let csrf = await (await seller.request.get('/api/auth/csrf')).json();
            expect((await seller.request.post('/api/auth/login', { headers: { [csrf.headerName]: csrf.token }, form: { email: 'p4b-browse-quote-browser-' + info.project.name + '-a@example.com', password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200);
            csrf = await (await seller.request.get('/api/auth/csrf')).json();
            const product = (await (await seller.request.get('/api/seller/products')).json()).items[0];
            const sku = (await (await seller.request.get('/api/seller/products/' + product.id + '/skus')).json()).items[0];
            return { csrf, product, sku };
        });
        await operation("quotes: /api/seller/products/ [9]", async () => {
            expect((await seller.request.put('/api/seller/products/' + product.id + '/skus/' + sku.id, { headers: { [csrf.headerName]: csrf.token }, data: { ...sku, price: 50000 } })).status()).toBe(200);
        });
        await operation("quotes: 견적 다시 계산 [10]", async () => {
            await page.getByRole('button', { name: '견적 다시 계산', exact: true }).click();
            await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 90,000원');
            expect(old.total).toBe('73000');
            expect(old.stores.find((s: {
                slug: string;
            }) => s.slug === prefix + '-a').lines[0].unitPrice).toBe('30000');
            await page.reload();
            await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 93,000원');
            await expect(select).toHaveValue('');
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        });
        await operation("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [11]", async () => {
            await select.selectOption({ label: '견적 3000원' });
            await expect(page.getByTestId('quote-total')).toHaveText('현재 견적 합계: 90,000원');
            await expect(select).not.toHaveValue('');
        });
        await operation("quotes: 로그아웃 [12]", async () => {
            await page.goto('/account');
            await page.getByRole('button', { name: '로그아웃', exact: true }).click();
            await expect(page.getByText('로그아웃했습니다.', { exact: true })).toBeVisible();
        });
        await operation("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [13]", async () => {
            await page.goto('/checkout');
            await expect(page.getByTestId('quote-total')).toHaveCount(0);
        });
    }
    finally {
        await cleanup("quotes: 서버 견적은 매대별 배송·쿠폰과 가격 변경을 반영하고 이전 응답은 유지한다 [14]", async () => {
            await seller.close();
        });
    }
});
