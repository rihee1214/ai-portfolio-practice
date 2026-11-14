import { boundedFetch, operation, prepare, cleanup } from './business-deadline';
import { expect, test } from './business-deadline';
test('모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다', async ({ page, context, browser }, info) => {
    const { phone, initialCsrf } = await operation("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [1]", async () => {
        const phone = info.project.name === 'desktop' ? '+821061000001' : '+821061000002';
        await page.goto('/checkout/guest');
        await expect(page.getByText('로컬 모의 인증입니다.', { exact: false })).toBeVisible();
        const initialCsrf = await (await context.request.get('/api/auth/csrf')).json();
        return { phone, initialCsrf };
    });
    await operation("guest: /api/guest/checkout [2]", async () => {
        const starts = await Promise.all([1, 2].map(() => context.request.post('/api/guest/checkout', { headers: { [initialCsrf.headerName]: initialCsrf.token }, data: {} })));
        expect(starts.map(response => response.status())).toEqual([200, 200]);
        expect((await context.request.get('/api/guest/checkout')).status()).toBe(200);
    });
    const { code, exchangeUrl, boundCsrf } = await operation("guest: 모의 인증번호 요청 [3]", async () => {
        await page.getByLabel('구매자 이름').fill('가상 구매자');
        await page.getByLabel('전화번호').fill(phone);
        await page.getByRole('button', { name: '모의 인증번호 요청' }).click();
        await expect(page.getByLabel('인증번호')).toBeVisible();
        const beforeRefresh = await (await context.request.get('/api/guest/checkout')).json();
        await page.reload();
        await expect(page.getByLabel('인증번호')).toBeVisible();
        const afterRefresh = await (await context.request.get('/api/guest/checkout')).json();
        expect(afterRefresh.attemptId).toBe(beforeRefresh.attemptId);
        const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT;
        if (!host || !project?.startsWith('demo-'))
            throw new Error('Isolated emulator required');
        const codes = await (await boundedFetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as {
            verificationCodes: {
                phoneNumber: string;
                code: string;
            }[];
        };
        const code = codes.verificationCodes.find(entry => entry.phoneNumber === phone)?.code;
        expect(code).toMatch(/^\d{6}$/);
        const exchangeUrl = '/api/guest/verifications/' + afterRefresh.attemptId + '/exchange';
        const boundCsrf = await (await context.request.get('/api/auth/csrf')).json();
        return { code, exchangeUrl, boundCsrf };
    });
    for (const wrong of [{ phone: '+821099999999', purpose: 'PURCHASE' }, { phone, purpose: 'RECOVERY' }]) {
        await operation("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [4]", async () => {
            expect((await context.request.post(exchangeUrl, { headers: { [boundCsrf.headerName]: boundCsrf.token }, data: { ...wrong, code } })).status()).toBe(409);
        });
    }
    const { other } = await prepare("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [5]", async () => {
        const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { other };
    });
    try {
        await operation("guest: /api/guest/checkout [6]", async () => {
            const otherCsrf = await (await other.request.get('/api/auth/csrf')).json();
            await other.request.post('/api/guest/checkout', { headers: { [otherCsrf.headerName]: otherCsrf.token }, data: {} });
            expect((await other.request.post(exchangeUrl, { headers: { [otherCsrf.headerName]: otherCsrf.token }, data: { phone, purpose: 'PURCHASE', code } })).status()).toBe(409);
        });
    }
    finally {
        await cleanup("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [7]", async () => {
            await other.close();
        });
    }
    const { confirmed, csrf } = await operation("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [8]", async () => {
        await page.getByLabel('인증번호').fill(code!);
        const verifyResponse = page.waitForResponse(response => /\/api\/guest\/verifications\/[^/]+\/verify$/.test(response.url()));
        await page.getByLabel('인증번호').press('Enter');
        const confirmed = await verifyResponse;
        expect(confirmed.status()).toBe(200);
        await expect(page.getByText('구매 인증을 확인했습니다.', { exact: false })).toBeVisible();
        const csrf = await (await context.request.get('/api/auth/csrf')).json();
        return { confirmed, csrf };
    });
    await operation("guest: /api/account/profile [9]", async () => {
        const replay = await context.request.post(confirmed.url(), { headers: { [csrf.headerName]: csrf.token }, data: confirmed.request().postDataJSON() });
        expect(replay.status()).toBe(409);
        expect((await context.request.get('/api/account/profile')).status()).toBe(401);
        await page.reload();
        await expect(page.getByText('구매 인증을 확인했습니다.', { exact: false })).toBeVisible();
        const cookies = await context.cookies();
        expect(cookies.find(cookie => cookie.name === 'SESSION')).toMatchObject({ httpOnly: true, sameSite: 'Lax' });
        const storage = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
        expect(storage).not.toContain(code!);
        expect(storage).not.toContain(phone);
        expect(storage).not.toContain('Token');
        expect(page.url()).not.toContain(code!);
        expect(page.url()).not.toContain(phone);
    });
    await operation("guest: 정보 변경·새 인증 시작 [10]", async () => {
        await page.getByRole('button', { name: '정보 변경·새 인증 시작' }).click();
        await expect(page.getByLabel('구매자 이름')).toBeVisible();
    });
    await operation("guest: 모의 구매 인증은 현재 세션에만 결합하고 재사용을 차단한다 [11]", async () => {
        expect((await context.request.post(confirmed.url(), { headers: { [csrf.headerName]: csrf.token }, data: confirmed.request().postDataJSON() })).status()).toBe(409);
    });
    await operation("guest: 모의 인증번호 요청 [12]", async () => {
        await page.getByLabel('구매자 이름').fill('새 가상 구매자');
        await page.getByLabel('전화번호').fill(phone);
        await page.getByRole('button', { name: '모의 인증번호 요청' }).click();
        await expect(page.getByRole('alert')).toContainText('요청이 많습니다');
    });
});
test('확인 응답 유실 뒤 새로고침은 서버 결과를 복구하고 재확인을 거부한다', async ({ page, context }, info) => {
    const { code } = await operation("guest: 모의 인증번호 요청 [13]", async () => {
        const phone = info.project.name === 'desktop' ? '+821062000001' : '+821062000002';
        await page.goto('/checkout/guest');
        await page.getByLabel('구매자 이름').fill('가상 응답유실');
        await page.getByLabel('전화번호').fill(phone);
        await page.getByRole('button', { name: '모의 인증번호 요청' }).click();
        await expect(page.getByLabel('인증번호')).toBeVisible();
        const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT;
        if (!host || !project?.startsWith('demo-'))
            throw new Error('Isolated emulator required');
        const codes = await (await boundedFetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as {
            verificationCodes: {
                phoneNumber: string;
                code: string;
            }[];
        };
        const code = codes.verificationCodes.find(entry => entry.phoneNumber === phone)?.code;
        expect(code).toMatch(/^\d{6}$/);
        return { code };
    });
    let lostUrl = '', lostBody: unknown;
    await prepare("guest: 확인 응답 유실 뒤 새로고침은 서버 결과를 복구하고 재확인을 거부한다 [14]", async () => {
        await page.route('**/api/guest/verifications/*/verify', async (route) => {
            const response = await route.fetch();
            expect(response.status()).toBe(200);
            lostUrl = route.request().url();
            lostBody = route.request().postDataJSON();
            await route.abort('failed');
        });
    });
    const { csrf } = await operation("guest: 인증 확인 [15] + recovery", async () => {
        await operation("guest: 인증 확인 [15]", async () => {
            await page.getByLabel('인증번호').fill(code!);
            await page.getByRole('button', { name: '인증 확인' }).click();
            await expect(page.getByRole('alert')).toContainText('인증을 완료하지 못했습니다');
            await expect(page.getByText('구매 인증을 확인했습니다.', { exact: false })).toHaveCount(0);
        });
        await operation("guest: 확인 응답 유실 뒤 새로고침은 서버 결과를 복구하고 재확인을 거부한다 [16]", async () => {
            await page.unroute('**/api/guest/verifications/*/verify');
        });
        const { csrf } = await operation("guest: 확인 응답 유실 뒤 새로고침은 서버 결과를 복구하고 재확인을 거부한다 [17]", async () => {
            await page.reload();
            await expect(page.getByText('구매 인증을 확인했습니다.', { exact: false })).toBeVisible();
            const csrf = await (await context.request.get('/api/auth/csrf')).json();
            return { csrf };
        });
        return { csrf };
    });
    await operation("guest: 확인 응답 유실 뒤 새로고침은 서버 결과를 복구하고 재확인을 거부한다 [18]", async () => {
        expect((await context.request.post(lostUrl, { headers: { [csrf.headerName]: csrf.token }, data: lostBody })).status()).toBe(409);
    });
});
