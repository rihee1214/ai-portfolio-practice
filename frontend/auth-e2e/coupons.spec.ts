import { operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
test.use({ trace: 'retain-on-failure' });
test('회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다', async ({ page, browser }, info) => {
    const login = async (p: typeof page, email: string) => {
        await operation("coupons: 로그인 [1]", async () => {
            await p.goto('/login');
            await p.getByLabel('이메일', { exact: true }).fill(email);
            await p.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
            await p.getByRole('button', { name: '로그인', exact: true }).click();
            await expect(p.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        });
    };
    await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [2]", async () => {
        await login(page, 'p4b-browse-coupon-browser-' + info.project.name + '@example.com');
    });
    const { title, template: initialTemplate1 } = await operation("coupons: 쿠폰 등록 [3]", async () => {
        await page.goto('/seller/coupons');
        const title = '쿠폰 ' + info.project.name;
        await page.getByLabel('쿠폰 이름', { exact: true }).fill(title);
        await page.getByLabel('쿠폰 할인 값', { exact: true }).fill('1000');
        for (const label of ['발급 시작', '사용 시작']) {
            await operation("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [4]", async () => {
                await page.getByLabel(label + ' (한국 시간)', { exact: true }).fill('2020-01-01T09:00');
            });
        }
        for (const label of ['발급 종료', '사용 종료']) {
            await operation("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [5]", async () => {
                await page.getByLabel(label + ' (한국 시간)', { exact: true }).fill('2099-01-01T09:00');
            });
        }
        await page.getByRole('button', { name: '쿠폰 등록', exact: true }).click();
        await page.getByRole('button', { name: title + ' · 초안', exact: true }).click();
        const csrf = await (await page.request.get('/api/auth/csrf')).json();
        const template = (await (await page.request.get('/api/seller/coupons')).json()).items.find((t: {
            title: string;
        }) => t.title === title);
        const input = { title, kind: template.terms.kind, value: 1000, cap: null, minimum: 0, issueStart: template.issueStart, issueEnd: template.issueEnd, startsAt: template.terms.startsAt, endsAt: template.terms.endsAt, totalLimit: 100, state: 'DRAFT', version: template.version };
        expect((await page.request.put('/api/seller/coupons/' + template.id, { headers: { [csrf.headerName]: csrf.token }, data: input })).status()).toBe(200);
        return { title, template };
    });
    let template = initialTemplate1;
    await operation("coupons: 쿠폰 저장 [6]", async () => {
        await page.getByLabel('쿠폰 상태', { exact: true }).selectOption('ACTIVE');
        await page.getByRole('button', { name: '쿠폰 저장', exact: true }).click();
        await expect(page.getByRole('alert')).toBeVisible();
        await page.getByRole('button', { name: '쿠폰 템플릿 다시 확인' }).click();
    });
    await operation("coupons: 쿠폰 저장 [7]", async () => {
        await page.getByRole('button', { name: title + ' · 초안', exact: true }).click();
        await page.getByLabel('쿠폰 상태', { exact: true }).selectOption('ACTIVE');
        await page.getByRole('button', { name: '쿠폰 저장', exact: true }).click();
        await expect(page.getByRole('button', { name: title + ' · 발급 중', exact: true })).toBeVisible();
    });
    const { member } = await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [8]", async () => {
        const member = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { member };
    });
    const { guest } = await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [9]", async () => {
        const guest = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { guest };
    });
    let failed = false;
    let primary: unknown = null;
    try {
        await operation("coupons: /api/account/coupons [10]", async () => {
            console.log('coupon stage: anonymous boundary');
            expect((await guest.request.get('/api/account/coupons')).status()).toBe(401);
        });
        const { buyer } = await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [11]", async () => {
            const buyer = await member.newPage();
            return { buyer };
        });
        await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [12]", async () => {
            await login(buyer, 'coupon-member-' + info.project.name + '@example.com');
        });
        await operation("coupons: 같은 이름  [13]", async () => {
            await buyer.goto('/account/coupons');
            console.log('coupon stage: owned loaded');
            await expect(buyer.getByText('만료', { exact: true })).toBeVisible();
            const twins = buyer.getByRole('article').filter({ has: buyer.getByRole('heading', { name: '같은 이름 ' + info.project.name, exact: true }) });
            await expect(twins).toHaveCount(2);
            const links = await twins.getByRole('link').evaluateAll(nodes => nodes.map(n => n.getAttribute('href')));
            expect(new Set(links).size).toBe(2);
            console.log('coupon stage: twin reload');
            await buyer.reload();
            await expect(twins).toHaveCount(2);
            console.log('coupon stage: csrf boundary');
        });
        const { claimButton } = await operation("coupons: 쿠폰 목록 다시 확인 [14]", async () => {
            expect((await buyer.request.post('/api/account/coupons/claim', { data: { templateId: template.id } })).status()).toBe(403);
            await expect(buyer.getByRole('button', { name: '쿠폰 목록 다시 확인' })).toBeEnabled();
            const claimButton = buyer.getByRole('button', { name: title + ' 받기', exact: true });
            return { claimButton };
        });
        for (let pageIndex = 0; await operation('coupons: inspect claim availability', async () => claimButton.count()) === 0; pageIndex++) {
            const { next } = await operation("coupons: 다음 발급 쿠폰 [15]", async () => {
                expect(pageIndex).toBeLessThan(100);
                const next = buyer.getByRole('button', { name: '다음 발급 쿠폰', exact: true });
                await expect(next).toBeEnabled();
                return { next };
            });
            await operation("coupons: 발급 쿠폰 페이지 [16]", async () => {
                await next.click();
                await expect(buyer.getByRole('navigation', { name: '발급 쿠폰 페이지' }).getByText((pageIndex + 2) + ' 페이지', { exact: true })).toBeVisible();
                await expect(buyer.getByRole('button', { name: '쿠폰 목록 다시 확인' })).toBeEnabled();
            });
        }
        await operation("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [17]", async () => {
            await expect(claimButton).toBeVisible();
            console.log('coupon stage: claim intercept');
        });
        let claims = 0;
        await prepare("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [18]", async () => {
            await buyer.route('**/api/account/coupons/claim', async (route) => { claims++; const r = await route.fetch(); expect(r.status()).toBe(200); await route.abort(); }, { times: 1 });
        });
        const { owned, token } = await operation("coupons: 쿠폰 목록 다시 확인 [19]", async () => {
            await buyer.getByRole('button', { name: title + ' 받기', exact: true }).click();
            await expect(buyer.getByRole('alert')).toBeVisible();
            expect(claims).toBe(1);
            console.log('coupon stage: claim recovery');
            await buyer.getByRole('button', { name: '쿠폰 목록 다시 확인' }).click();
            await expect(buyer.getByRole('heading', { name: title, exact: true })).toHaveCount(1);
            const owned = (await (await buyer.request.get('/api/account/coupons')).json()).items.find((c: {
                title: string;
            }) => c.title === title);
            expect(owned.terms.value).toBe(1000);
            const token = await (await buyer.request.get('/api/auth/csrf')).json();
            console.log('coupon stage: claim idempotent');
            return { owned, token };
        });
        await operation("coupons: /api/account/coupons/claim [20]", async () => {
            const again = await buyer.request.post('/api/account/coupons/claim', { headers: { [token.headerName]: token.token }, data: { templateId: template.id } });
            expect(again.status()).toBe(200);
            expect((await again.json()).id).toBe(owned.id);
            console.log('coupon stage: stop issuance');
        });
        await operation("coupons: 쿠폰 저장 [21]", async () => {
            await page.getByRole('button', { name: title + ' · 발급 중', exact: true }).click();
            await page.getByLabel('쿠폰 할인 값', { exact: true }).fill('500');
            await page.getByLabel('쿠폰 상태', { exact: true }).selectOption('STOPPED');
            await page.getByRole('button', { name: '쿠폰 저장', exact: true }).click();
            await expect(page.getByRole('button', { name: title + ' · 발급 중지', exact: true })).toBeVisible();
            console.log('coupon stage: snapshot reload');
            await buyer.reload();
            const card = buyer.getByRole('article').filter({ has: buyer.getByRole('heading', { name: title, exact: true }) });
            await expect(card.getByText('1,000원 할인', { exact: true })).toBeVisible();
            await expect(card.getByText('사용 가능', { exact: true })).toBeVisible();
            template = await (await page.request.get('/api/seller/coupons/' + template.id)).json();
            expect(template.issuedCount).toBe(1);
            console.log('coupon stage: logout');
        });
        await operation("coupons: 로그아웃 [22]", async () => {
            await buyer.goto('/account');
            await buyer.getByRole('button', { name: '로그아웃', exact: true }).click();
            await expect(buyer.getByText('로그아웃했습니다.', { exact: true })).toBeVisible();
        });
        await operation("coupons: 로그인하고 쿠폰 확인 [23]", async () => {
            await buyer.goto('/account/coupons');
            await expect(buyer.getByRole('link', { name: '로그인하고 쿠폰 확인' })).toBeVisible();
            await expect(buyer.getByRole('heading', { name: title, exact: true })).toHaveCount(0);
        });
    }
    catch (error) {
        failed = true;
        primary = error;
        console.error('coupon primary failure', error);
    }
    finally {
        const { closed } = await cleanup("coupons: 회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다 [24]", async () => {
            const closed = await Promise.allSettled([member.close(), guest.close()]);
            return { closed };
        });
        if (!failed) {
            const failure = closed.find(r => r.status === 'rejected');
            if (failure?.status === 'rejected')
                primary = failure.reason;
        }
    }
    if (primary)
        throw primary;
});
