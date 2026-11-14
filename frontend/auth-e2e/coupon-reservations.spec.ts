import { operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
test('쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다', async ({ page, browser }, info) => {
    const { project } = await operation("coupon-reservations: 로그인 [1]", async () => {
        const project = info.project.name;
        await page.goto('/login');
        await page.getByLabel('이메일', { exact: true }).fill('reservation-member-' + project + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        return { project };
    });
    const { post, input } = await operation("coupon-reservations: /api/account/coupons [2]", async () => {
        await page.goto('/account/coupons');
        await expect(page.getByText('사용 가능', { exact: true })).toBeVisible();
        const coupon = (await (await page.request.get('/api/account/coupons')).json()).items[0], sku = (await (await page.request.get('/api/products/p4b-browse-reservation-browser-' + project + '/skus')).json()).items[0];
        const post = async (data: Record<string, unknown>) => {
            return operation("coupon-reservations: /api/__test/coupon-reservations [3]", async () => {
                const csrf = await (await page.request.get('/api/auth/csrf')).json();
                return page.request.post('/api/__test/coupon-reservations', { headers: { [csrf.headerName]: csrf.token }, data });
            });
        };
        const input = { action: 'reserve', couponId: coupon.id, skuId: sku.id };
        return { post, input };
    });
    await operation("coupon-reservations: /api/__test/coupon-reservations [4]", async () => {
        expect((await page.request.post('/api/__test/coupon-reservations', { data: input })).status()).toBe(403);
    });
    const { reservation } = await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [5]", async () => {
        const created = await post(input);
        expect(created.status()).toBe(200);
        const reservation = await created.json();
        await page.reload();
        await expect(page.getByText('예약 중', { exact: true })).toBeVisible();
        return { reservation };
    });
    const { other } = await prepare("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [6]", async () => {
        const other = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { other };
    });
    try {
        const { token } = await operation("coupon-reservations: /api/auth/login [7]", async () => {
            let token = await (await other.request.get('/api/auth/csrf')).json();
            expect((await other.request.post('/api/auth/login', { headers: { [token.headerName]: token.token }, form: { email: 'reservation-other-' + project + '@example.com', password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200);
            token = await (await other.request.get('/api/auth/csrf')).json();
            return { token };
        });
        await operation("coupon-reservations: /api/__test/coupon-reservations [8]", async () => {
            expect((await other.request.post('/api/__test/coupon-reservations', { headers: { [token.headerName]: token.token }, data: { action: 'commit', reservationId: reservation.id } })).status()).toBe(404);
        });
    }
    finally {
        await cleanup("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [9]", async () => {
            await other.close();
        });
    }
    const { commit, token } = await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [10]", async () => {
        const commit = { action: 'commit', reservationId: reservation.id };
        const token = await (await page.request.get('/api/auth/csrf')).json();
        return { commit, token };
    });
    await prepare("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [11]", async () => {
        await page.route('**/api/__test/coupon-reservations', async (route) => { const r = await route.fetch(); expect(r.status()).toBe(200); await route.abort(); }, { times: 1 });
    });
    await operation("coupon-reservations: /api/__test/coupon-reservations [12]", async () => {
        expect(await page.evaluate(async ({ data, token }) => {
            try {
                await fetch('/api/__test/coupon-reservations', { method: 'POST', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify(data) });
                return true;
            }
            catch {
                return false;
            }
        }, { data: commit, token })).toBe(false);
    });
    await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [13]", async () => {
        const repeated = await post(commit);
        expect(repeated.status()).toBe(200);
        expect((await repeated.json()).state).toBe('COMMITTED');
        await page.reload();
        await expect(page.getByText('사용 완료', { exact: true })).toBeVisible();
    });
    const { full } = await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [14]", async () => {
        const partial = await post({ action: 'partial', reservationId: reservation.id, causeId: crypto.randomUUID() });
        expect(partial.status()).toBe(200);
        expect((await partial.json()).restored).toBe(false);
        await page.reload();
        await expect(page.getByText('사용 완료', { exact: true })).toBeVisible();
        const full = { action: 'full', reservationId: reservation.id, causeId: crypto.randomUUID() };
        return { full };
    });
    const { result } = await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [15]", async () => {
        const restored = await post(full);
        expect(restored.status()).toBe(200);
        const result = await restored.json();
        expect(result.restored).toBe(true);
        return { result };
    });
    await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [16]", async () => {
        expect(await (await post(full)).json()).toEqual(result);
        await page.reload();
        await expect(page.getByText('사용 가능', { exact: true })).toBeVisible();
    });
    const { second } = await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [17]", async () => {
        const next = await post(input);
        expect(next.status()).toBe(200);
        const second = await next.json();
        expect(second.id).not.toBe(reservation.id);
        return { second };
    });
    await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [18]", async () => {
        expect((await post({ action: 'release', reservationId: reservation.id })).status()).toBe(409);
    });
    await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [19]", async () => {
        expect(await (await post(full)).json()).toEqual(result);
        await page.reload();
        await expect(page.getByText('예약 중', { exact: true })).toBeVisible();
    });
    await operation("coupon-reservations: 쿠폰 예약·사용·전체복구를 재조회하고 재시도와 다른 회원 접근을 검증한다 [20]", async () => {
        expect((await post({ action: 'release', reservationId: second.id })).status()).toBe(200);
        await page.reload();
        await expect(page.getByText('사용 가능', { exact: true })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
});
