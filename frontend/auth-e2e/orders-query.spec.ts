import { boundedFetch, operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Page, type BrowserContext } from './business-deadline';
async function headers(context: BrowserContext) { return operation("orders-query: headers", async () => { const csrf = await (await context.request.get('/api/auth/csrf')).json(); return { [csrf.headerName]: csrf.token }; }); }
async function login(page: Page, email: string) { return operation("orders-query: login", async () => { await page.goto('/login'); await page.getByLabel('이메일', { exact: true }).fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 '); await page.getByRole('button', { name: '로그인', exact: true }).click(); await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible(); }); }
const email = (project: string, mode: string) => 'p4b-browse-reserve-order-d-f-browser-' + project + '-' + mode + '@example.com';
test('실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다', async ({ page, context }, info) => {
    for (const [mode, state, label] of [['success', 'SUCCEEDED', '결제 승인 성공'], ['failed', 'FAILED', '결제 승인 실패'], ['unknown', 'UNKNOWN', '결제 확인 중']]) {
        await prepare("orders-query: 실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다 [1]", async () => {
            await login(page, email(info.project.name, mode));
        });
        const { number, detail, payment } = await operation("orders-query: /api/account/orders [2]", async () => {
            const listing = await context.request.get('/api/account/orders');
            expect(listing.headers()['cache-control']).toContain('no-store');
            const rows = (await listing.json()).orders;
            expect(rows).toHaveLength(1);
            const number = rows[0].orderNumber;
            const detail = await (await context.request.get('/api/orders/' + number)).json(), payment = await (await context.request.get('/api/payments/' + number + '/history')).json();
            expect(detail.stores).toHaveLength(2);
            expect(payment.paymentState).toBe(state);
            expect(payment.events.length).toBeGreaterThan(0);
            return { number, detail, payment };
        });
        await operation("orders-query: 실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다 [3]", async () => {
            await page.goto('/account/orders');
            await page.getByRole('link', { name: number, exact: true }).click();
            await expect(page.getByTestId('order-number')).toHaveText(number);
            await expect(page.getByTestId('payment-history-state')).toHaveText(label);
            await expect(page.getByTestId('order-total')).toContainText(Number(detail.total).toLocaleString('ko-KR'));
        });
        for (const store of detail.stores) {
            const { panel } = await operation("orders-query: 실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다 [4]", async () => {
                const panel = page.getByTestId('seller-order-' + store.sellerOrderId);
                await expect(panel).toBeVisible();
                return { panel };
            });
            for (const item of store.items) {
                await operation("orders-query: 실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다 [5]", async () => {
                    await expect(panel.getByRole('heading', { name: item.title, exact: true })).toBeVisible();
                });
                await operation("orders-query: 실제 두 매대 주문의 성공 실패 확인중 이력은 서버 스냅샷과 일치한다 [6]", async () => {
                    await panel.getByText('주문 당시 개별 수량 금액', { exact: true }).click();
                    await expect(panel.getByText(new RegExp('개별 1: 원금액'))).toBeVisible();
                });
            }
        }
        await operation("orders-query: 결제 이력 [7]", async () => {
            await expect(page.getByRole('region', { name: '결제 이력', exact: true }).locator('li')).toHaveCount(payment.events.length);
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        });
    }
});
test('새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다', async ({ page, context, browser }, info) => {
    const prefix = 'p4b-browse-order-historyguest-' + info.project.name, phone = info.project.name === 'desktop' ? '+821069000001' : '+821069000002', password = 'f actual guest lookup password';
    for (const side of ['a', 'b']) {
        await operation("orders-query: 장바구니 담기 [8]", async () => {
            await page.goto('/products/' + prefix + '-' + side);
            await page.getByRole('button', { name: '장바구니 담기', exact: true }).click();
            await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible();
        });
    }
    const { code } = await operation("orders-query: 모의 인증번호 요청 [9]", async () => {
        await page.goto('/checkout/guest');
        await page.getByLabel('구매자 이름', { exact: true }).fill('F 실제 비회원');
        await page.getByLabel('전화번호', { exact: true }).fill(phone);
        await page.getByRole('button', { name: '모의 인증번호 요청' }).click();
        await expect(page.getByLabel('인증번호', { exact: true })).toBeVisible();
        const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, project = process.env.GCLOUD_PROJECT;
        if (!host || !project?.startsWith('demo-'))
            throw new Error('isolated emulator required');
        const codes = await (await boundedFetch(`http://${host}/emulator/v1/projects/${project}/verificationCodes`)).json() as {
            verificationCodes: {
                phoneNumber: string;
                code: string;
            }[];
        };
        const code = codes.verificationCodes.find(c => c.phoneNumber === phone)?.code;
        expect(code).toMatch(/^\d{6}$/);
        return { code };
    });
    await operation("orders-query: 인증 확인 [10]", async () => {
        await page.getByLabel('인증번호', { exact: true }).fill(code!);
        await page.getByRole('button', { name: '인증 확인', exact: true }).click();
        await expect(page.getByRole('heading', { name: '조회 비밀번호 설정' })).toBeVisible();
    });
    await operation("orders-query: 조회 비밀번호 준비 [11]", async () => {
        await page.getByLabel('조회 비밀번호', { exact: true }).fill(password);
        await page.getByLabel('조회 비밀번호 확인', { exact: true }).fill(password);
        await page.getByRole('button', { name: '조회 비밀번호 준비', exact: true }).click();
        await expect(page.getByText(/조회 비밀번호 준비 완료/)).toBeVisible();
    });
    await operation("orders-query: 주문서 준비로 돌아가기 [12]", async () => {
        await page.getByRole('link', { name: '주문서 준비로 돌아가기' }).click();
        await expect(page.getByTestId('quote-total')).toBeVisible();
    });
    const { order } = await operation("orders-query: 현재 상품·수량·견적과 배송 정보를 확인했습니다 [13]", async () => {
        for (const [label, value] of [['수령인 이름', 'F 수령인'], ['수령인 전화번호', '01012345678'], ['우편번호', '01234'], ['기본 주소', 'F 주문 주소'], ['상세 주소', '101호']]) {
            await operation("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [14]", async () => {
                await page.getByLabel(label, { exact: true }).fill(value);
            });
        }
        await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check();
        const submitted = page.waitForResponse(r => r.url().endsWith('/api/orders'));
        await page.getByRole('button', { name: '주문 생성', exact: true }).click();
        const created = await submitted;
        expect(created.status()).toBe(200);
        const order = await created.json();
        expect(order.stores).toBe(2);
        return { order };
    });
    await operation("orders-query: /api/payments/ [15]", async () => {
        expect((await context.request.post('/api/payments/' + order.orderNumber + '/approve', { headers: await headers(context), data: {} })).status()).toBe(200);
    });
    const { fresh } = await prepare("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [16]", async () => {
        const fresh = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { fresh };
    });
    try {
        await operation("orders-query: /api/orders/ [17]", async () => {
            expect((await fresh.request.get('/api/orders/' + order.orderNumber)).status()).toBe(404);
        });
        const { lookup } = await prepare("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [18]", async () => {
            const lookup = await fresh.newPage();
            return { lookup };
        });
        await operation("orders-query: 주문 조회 인증 [19]", async () => {
            await lookup.goto('/guest/orders/login');
            await lookup.getByLabel('주문번호', { exact: true }).fill(order.orderNumber);
            await lookup.getByLabel('등록 전화번호', { exact: true }).fill(phone);
            await lookup.getByLabel('조회 비밀번호', { exact: true }).fill(password);
            await lookup.getByRole('button', { name: '주문 조회 인증', exact: true }).click();
            await lookup.getByRole('region', { name: '실제 주문 이력', exact: true }).getByRole('link', { name: order.orderNumber, exact: true }).click();
            await expect(lookup.getByTestId('payment-history-state')).toHaveText('결제 승인 성공');
            expect((await fresh.request.get('/api/payments/' + order.orderNumber + '/history')).status()).toBe(200);
            expect((await fresh.request.get('/api/payments/' + order.orderNumber)).status()).toBe(401);
        });
        await operation("orders-query: /api/payments/ [20]", async () => {
            expect((await fresh.request.post('/api/payments/' + order.orderNumber + '/approve', { headers: await headers(fresh), data: {} })).status()).toBe(401);
        });
        await prepare("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [21]", async () => {
            await lookup.route('**/api/payments/*/history', route => route.abort('failed'));
        });
        await operation("orders-query: 주문 상세 다시 조회 [22] + recovery", async () => {
            await operation("orders-query: 주문 상세 다시 조회 [22]", async () => {
                await lookup.getByRole('button', { name: '주문 상세 다시 조회', exact: true }).click();
                await expect(lookup.getByRole('alert')).toBeVisible();
                await expect(lookup.getByTestId('order-number')).toHaveCount(0);
            });
            await operation("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [23]", async () => {
                await lookup.unroute('**/api/payments/*/history');
            });
            await operation("orders-query: 주문 상세 다시 조회 [24]", async () => {
                await lookup.getByRole('button', { name: '주문 상세 다시 조회', exact: true }).click();
                await expect(lookup.getByTestId('order-number')).toHaveText(order.orderNumber);
            });
        });
        await operation("orders-query: 연결된 주문 [25]", async () => {
            await lookup.goto('/guest/orders');
            await expect(lookup.getByRole('heading', { name: '연결된 주문', exact: true })).toBeVisible();
        });
        await prepare("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [26]", async () => {
            await lookup.route('**/api/guest/access/logout', async (route) => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed'); });
        });
        await operation("orders-query: 비회원 조회 로그아웃 [27]", async () => {
            await lookup.getByRole('button', { name: '비회원 조회 로그아웃', exact: true }).click();
            await expect(lookup.getByRole('alert')).toBeVisible();
            await expect(lookup.getByText(order.orderNumber, { exact: true })).toHaveCount(0);
            expect((await fresh.request.get('/api/payments/' + order.orderNumber + '/history')).status()).toBe(404);
            expect(await lookup.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))).not.toContain(password);
        });
    }
    finally {
        await cleanup("orders-query: 새 브라우저의 실제 비회원 주문 조회는 구매 승인 권한과 분리되고 응답 유실 로그아웃에 숨는다 [28]", async () => {
            await fresh.close();
        });
    }
});
test('계정 전환 뒤 늦은 이전 주문 응답은 표시하지 않는다', async ({ page, context }, info) => {
    await prepare("orders-query: 계정 전환 뒤 늦은 이전 주문 응답은 표시하지 않는다 [29]", async () => {
        await login(page, email(info.project.name, 'success'));
    });
    const { number } = await operation("orders-query: /api/account/orders [30]", async () => {
        const number = (await (await context.request.get('/api/account/orders')).json()).orders[0].orderNumber;
        await page.goto('/account/orders/' + number);
        await expect(page.getByTestId('order-number')).toHaveText(number);
        return { number };
    });
    let release!: () => void, captured!: () => void;
    const held = new Promise<void>(r => { release = r; }), ready = new Promise<void>(r => { captured = r; });
    await prepare("orders-query: 계정 전환 뒤 늦은 이전 주문 응답은 표시하지 않는다 [31]", async () => {
        await page.route('**/api/orders/' + number, async (route) => {
            const response = await route.fetch();
            captured();
            await held;
            try {
                await route.fulfill({ response });
            }
            catch { /* request may have been aborted on account change */ }
        });
    });
    await operation("orders-query: 주문 상세 다시 조회 [32]", async () => {
        await page.getByRole('button', { name: '주문 상세 다시 조회', exact: true }).click();
        await ready;
        await expect(page.getByTestId('order-number')).toHaveCount(0);
    });
    await operation("orders-query: /api/auth/login [33] + recovery", async () => {
        await operation("orders-query: /api/auth/login [33]", async () => {
            expect((await context.request.post('/api/auth/login', { headers: await headers(context), form: { email: email(info.project.name, 'failed'), password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200);
            await page.evaluate(() => window.dispatchEvent(new Event('focus')));
            release();
        });
        await operation("orders-query: 계정 전환 뒤 늦은 이전 주문 응답은 표시하지 않는다 [34]", async () => {
            await page.unroute('**/api/orders/' + number);
        });
        await operation("orders-query: /api/orders/ [35]", async () => {
            await expect(page.getByRole('alert')).toBeVisible();
            await expect(page.getByTestId('order-number')).toHaveCount(0);
            expect((await context.request.get('/api/orders/' + number)).status()).toBe(404);
        });
    });
});
