import { boundedFetch, operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
test('직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다', async ({ page, context, browser }, info) => {
    const { phone, password, code } = await operation("guest-lookup: 모의 인증번호 요청 [1]", async () => {
        const phone = info.project.name === 'desktop' ? '+821063000001' : '+821063000002';
        const password = '  guest lookup password  ';
        await page.goto('/checkout/guest');
        await page.getByLabel('구매자 이름').fill('가상 조회 구매자');
        await page.getByLabel('전화번호', { exact: true }).fill(phone);
        await page.getByRole('button', { name: '모의 인증번호 요청' }).click();
        await expect(page.getByLabel('인증번호', { exact: true })).toBeVisible();
        const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT;
        if (!host || !project?.startsWith('demo-'))
            throw new Error('Fresh emulator required');
        const codes = await (await boundedFetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as {
            verificationCodes: {
                phoneNumber: string;
                code: string;
            }[];
        };
        const code = codes.verificationCodes.find(row => row.phoneNumber === phone)?.code;
        expect(code).toMatch(/^\d{6}$/);
        return { phone, password, code };
    });
    await operation("guest-lookup: 인증 확인 [2]", async () => {
        await page.getByLabel('인증번호', { exact: true }).fill(code!);
        await page.getByRole('button', { name: '인증 확인', exact: true }).click();
        await expect(page.getByRole('heading', { name: '조회 비밀번호 설정' })).toBeVisible();
    });
    await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [3]", async () => {
        await page.getByLabel('조회 비밀번호', { exact: true }).fill(password);
        await page.getByLabel('조회 비밀번호 확인', { exact: true }).fill(password);
    });
    await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [4]", async () => {
        await page.route('**/api/guest/checkout/password', async (route) => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed'); });
    });
    const { checkout, csrf, fixtureUrl } = await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [5] + recovery", async () => {
        await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [5]", async () => {
            await page.getByLabel('조회 비밀번호 확인', { exact: true }).press('Enter');
            await expect(page.getByRole('alert')).toBeVisible();
            await expect(page.getByLabel('조회 비밀번호', { exact: true })).toHaveValue('');
        });
        await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [6]", async () => {
            await page.unroute('**/api/guest/checkout/password');
        });
        const { checkout, csrf, fixtureUrl } = await operation("guest-lookup: /api/guest/checkout [7]", async () => {
            await page.reload();
            await expect(page.getByText('조회 비밀번호 준비 완료.', { exact: false })).toBeVisible();
            const checkout = await (await context.request.get('/api/guest/checkout')).json(), csrf = await (await context.request.get('/api/auth/csrf')).json();
            const fixtureUrl = `http://127.0.0.1:${process.env.A3_API_PORT}/__test/guest-order`;
            return { checkout, csrf, fixtureUrl };
        });
        return { checkout, csrf, fixtureUrl };
    });
    await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [8]", async () => {
        expect((await context.request.post(fixtureUrl, { data: { attemptId: checkout.attemptId } })).status()).toBe(403);
    });
    const { orderNumber, otherOrderNumber } = await operation("guest-lookup: /api/guest/access/references [9]", async () => {
        const created = await context.request.post(fixtureUrl, { data: { attemptId: checkout.attemptId }, headers: { [csrf.headerName]: csrf.token } });
        expect(created.status()).toBe(200);
        const { orderNumber, otherOrderNumber } = await created.json();
        expect((await context.request.get('/api/guest/access/references')).status()).toBe(401);
        return { orderNumber, otherOrderNumber };
    });
    const { other } = await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [10]", async () => {
        const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { other };
    });
    other.setDefaultTimeout(10000);
    other.setDefaultNavigationTimeout(10000);
    console.log('C lookup: prepared and fixture created');
    try {
        const { lookup } = await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [11]", async () => {
            const lookup = await other.newPage();
            return { lookup };
        });
        await operation("guest-lookup: 주문 조회 인증 [12]", async () => {
            await lookup.goto('/guest/orders/login');
            console.log('C lookup: independent page ready');
            await lookup.getByLabel('주문번호', { exact: true }).fill(orderNumber);
            await lookup.getByLabel('등록 전화번호').fill(phone);
            await lookup.getByLabel('조회 비밀번호', { exact: true }).fill(password.trim());
            await lookup.getByRole('button', { name: '주문 조회 인증' }).click();
            await expect(lookup.getByRole('alert')).toBeVisible();
        });
        await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [13]", async () => {
            await lookup.route('**/api/guest/access/login', async (route) => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed'); });
        });
        const { cookie } = await operation("guest-lookup: 주문 조회 인증 [14] + recovery", async () => {
            await operation("guest-lookup: 주문 조회 인증 [14]", async () => {
                await lookup.getByLabel('조회 비밀번호', { exact: true }).fill(password);
                await lookup.getByRole('button', { name: '주문 조회 인증' }).click();
                await expect(lookup.getByRole('alert')).toBeVisible();
                await expect(lookup.getByRole('heading', { name: '연결된 주문' })).toHaveCount(0);
            });
            await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [15]", async () => {
                await lookup.unroute('**/api/guest/access/login');
            });
            const { cookie } = await operation("guest-lookup: 연결된 주문 [16]", async () => {
                await lookup.getByLabel('조회 비밀번호', { exact: true }).fill(password);
                await lookup.getByLabel('조회 비밀번호', { exact: true }).press('Enter');
                await expect(lookup.getByRole('heading', { name: '연결된 주문' })).toBeVisible();
                console.log('C lookup: authenticated after lost response');
                await expect(lookup.getByText(orderNumber, { exact: true })).toBeVisible();
                await expect(lookup.getByText(otherOrderNumber, { exact: true })).toHaveCount(0);
                expect((await other.request.get('/api/guest/access/references/' + otherOrderNumber)).status()).toBe(404);
                expect((await other.request.get('/api/account/profile')).status()).toBe(401);
                await lookup.reload();
                await expect(lookup.getByText(orderNumber, { exact: true })).toBeVisible();
                const cookie = (await other.cookies()).find(row => row.name === 'GUEST_ACCESS')!;
                expect(cookie).toMatchObject({ httpOnly: true, sameSite: 'Lax', secure: false });
                const stored = await lookup.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
                expect(stored).not.toContain(password);
                expect(stored).not.toContain(cookie.value);
                expect(lookup.url()).not.toContain(password);
                return { cookie };
            });
            return { cookie };
        });
        let failedLogoutRequests = 0;
        await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [17]", async () => {
            await lookup.route('**/api/guest/access/logout', async (route) => {
                await route.abort('failed');
                failedLogoutRequests++;
            });
        });
        await operation("guest-lookup: 비회원 조회 로그아웃 [18] + recovery", async () => {
            await operation("guest-lookup: 비회원 조회 로그아웃 [18]", async () => {
                await lookup.getByRole('button', { name: '비회원 조회 로그아웃', exact: true }).click();
                await expect(lookup.getByRole('button', { name: '비회원 조회 로그아웃 다시 시도' })).toBeVisible();
                await expect.poll(() => failedLogoutRequests).toBe(1);
                await expect(lookup.getByRole('button', { name: '비회원 조회 로그아웃 다시 시도' })).toBeEnabled();
                await expect(lookup.getByText(orderNumber, { exact: true })).toHaveCount(0);
                expect((await other.request.get('/api/guest/access/references')).status()).toBe(200);
            });
            await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [19]", async () => {
                await lookup.unroute('**/api/guest/access/logout');
            });
        });
        await prepare("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [20]", async () => {
            await lookup.route('**/api/guest/access/logout', async (route) => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed'); });
        });
        await operation("guest-lookup: 비회원 조회 로그아웃 다시 시도 [21] + recovery", async () => {
            await operation("guest-lookup: 비회원 조회 로그아웃 다시 시도 [21]", async () => {
                await lookup.getByRole('button', { name: '비회원 조회 로그아웃 다시 시도' }).click();
                await expect(lookup.getByRole('alert')).toBeVisible();
                await expect(lookup.getByRole('button', { name: '비회원 조회 로그아웃 다시 시도' })).toBeEnabled();
                expect((await other.request.get('/api/guest/access/references', { headers: { Cookie: 'GUEST_ACCESS=' + cookie.value } })).status()).toBe(401);
            });
            await operation("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [22]", async () => {
                await lookup.unroute('**/api/guest/access/logout');
            });
            await operation("guest-lookup: 비회원 조회 로그아웃 다시 시도 [23]", async () => {
                await lookup.getByRole('button', { name: '비회원 조회 로그아웃 다시 시도' }).click();
                await expect(lookup.getByLabel('주문번호', { exact: true })).toBeVisible();
                expect((await other.request.get('/api/guest/access/references', { headers: { Cookie: 'GUEST_ACCESS=' + cookie.value } })).status()).toBe(401);
                await lookup.reload();
                await expect(lookup.getByRole('heading', { name: '연결된 주문' })).toHaveCount(0);
                console.log('C lookup: logout verified');
            });
        });
    }
    finally {
        await cleanup("guest-lookup: 직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다 [24]", async () => {
            await other.close().catch(error => {
                if (info.status !== 'timedOut')
                    throw error;
            });
        });
    }
});
