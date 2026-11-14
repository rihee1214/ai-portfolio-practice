import { bindDeadline, currentDeadline, operation, prepare, cleanup } from './business-deadline';
import { test, expect, type BrowserContext, type Page } from './business-deadline';
import { request as rawRequest } from 'node:http';
import { headers } from './purchase-helpers';
import type { OrderDetail } from '../src/order/OrderQueryStore';
import type { ClaimProgress, ClaimQuote } from '../src/claim/ClaimStore';
type Seed = {
    orderNumber: string;
    email: string;
    phone: string;
    productId: string;
    skuId: string;
    title: string;
    slug: string;
    sellerOrderId: string;
};
type Row = Record<string, string | number | null>;
type Database = {
    order: Row;
    units: Row[];
    claim: Row[];
    claim_unit: Row[];
    payment_refund_attempt: Row[];
    payment_refund_budget: Row[];
    order_unit_lifecycle: Row[];
    provider: Row[];
    restored: Row[];
    inventory: Row[];
    coupons: Row[];
    claim_refund_effect: Row[];
};
const password = ' A1-private-passphrase-가나다 ';
const region = (p: Page) => p.getByRole('region', { name: '발송 전 취소', exact: true });
async function seed(c: BrowserContext, project: string, scenario: string) { return prepare("order-cancellation: seed", async () => { const r = await c.request.get('/api/__test/cancellations/seed/' + project + '/' + scenario); expect(r.status()).toBe(200); return await r.json() as Seed; }); }
async function login(c: BrowserContext, email: string) { return operation("order-cancellation: login", async () => { expect((await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password } })).status()).toBe(200); }); }
async function detail(c: BrowserContext, n: string) { return operation("order-cancellation: detail", async () => { const r = await c.request.get('/api/orders/' + n); expect(r.status()).toBe(200); return await r.json() as OrderDetail; }); }
async function current(c: BrowserContext, n: string) { return operation("order-cancellation: current", async () => { const r = await c.request.get('/api/orders/' + n + '/cancellations'); expect(r.status()).toBe(200); expect(r.headers()['cache-control']).toContain('no-store'); return await r.json() as ClaimProgress[]; }); }
async function database(c: BrowserContext, n: string) { return operation("order-cancellation: database", async () => { const r = await c.request.get('/api/__test/cancellations/' + n + '/database'); expect(r.status(), await r.text()).toBe(200); return await r.json() as Database; }); }
async function work(c: BrowserContext, n: string, attempt: string, action: 'submit' | 'result') { return operation("order-cancellation: work", async () => { const r = await c.request.post('/api/__test/cancellations/' + n + '/worker', { headers: await headers(c), data: { attempt, action } }); expect(r.status(), await r.text()).toBe(200); }); }
async function quote(p: Page, d: OrderDetail, seller: string, ordinal: number) {
    return operation("order-cancellation: quote", async () => {
        const panel = region(p), item = d.stores.find(s => s.sellerOrderId === seller)!.items[0];
        await expect(panel).toBeVisible();
        await expect(panel.getByRole('button', { name: '취소 진행 다시 조회', exact: true })).toBeEnabled();
        if (await panel.getByRole('button', { name: '취소 수량 선택 열기', exact: true }).count())
            await panel.getByRole('button', { name: '취소 수량 선택 열기', exact: true }).click();
        await panel.getByRole('checkbox', { name: '취소할 ' + item.title + ' 개별 ' + (ordinal + 1), exact: true }).check();
        await panel.getByLabel('취소 사유', { exact: true }).fill('원 주문 금액으로 발송 전 취소');
        const pending = p.waitForResponse(r => r.url().endsWith('/cancellations/quote'));
        await panel.getByRole('button', { name: '선택 수량 취소 견적', exact: true }).click();
        const r = await pending;
        expect(r.status()).toBe(200);
        const q = await r.json() as ClaimQuote;
        expect(q.units).toEqual([{ itemId: item.id, ordinal, net: item.units[ordinal].net }]);
        expect(q.itemAmount).toBe(item.units[ordinal].net);
        expect(BigInt(q.conditionalShippingMaximum)).toBe(BigInt(d.stores.find(s => s.sellerOrderId === seller)!.shippingBase) - BigInt(d.stores.find(s => s.sellerOrderId === seller)!.shippingDiscount));
        await panel.getByRole('checkbox', { name: '견적과 선택 수량을 확인하고 취소를 요청합니다', exact: true }).check();
        return q;
    });
}
async function directCancel(c: BrowserContext, n: string, q: ClaimQuote, key: string) { return operation("order-cancellation: directCancel", async () => { const r = await c.request.post('/api/orders/' + n + '/cancellations', { headers: await headers(c), data: { key, units: q.units.map(({ itemId, ordinal }) => ({ itemId, ordinal })), reason: '원 주문 금액으로 발송 전 취소', expectedRevision: q.revision, quoteFingerprint: q.fingerprint } }); expect(r.status()).toBe(202); return await r.json(); }); }
async function guestLogin(c: BrowserContext, s: Seed) {
    return operation("order-cancellation: guestLogin", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('isolated Java port required');
        const call = (path: string, method: string, data?: unknown, cookies: string[] = [], token?: string) => new Promise<{
            status: number;
            data: Record<string, unknown>;
            cookies: string[];
        }>((resolve, reject) => {
            const transportTimeout = currentDeadline().remaining(10000);
            const body = data === undefined ? undefined : JSON.stringify(data), r = rawRequest({ hostname: '127.0.0.1', port, path, method, localAddress: '127.0.0.4', headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }), ...(cookies.length ? { Cookie: cookies.map(v => v.split(';')[0]).join('; ') } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}) } }, response => {
                let text = '';
                response.setEncoding('utf8');
                response.on('data', chunk => { text += chunk; });
                response.on('end', () => {
                    try {
                        resolve({ status: response.statusCode ?? 0, data: JSON.parse(text) as Record<string, unknown>, cookies: response.headers['set-cookie'] ?? [] });
                    }
                    catch (e) {
                        reject(e);
                    }
                });
            });
            const absoluteTimer = setTimeout(() => r.destroy(new Error('Browser operation transport deadline exceeded')), transportTimeout);
            r.once('close', () => clearTimeout(absoluteTimer));
            r.on('error', reject);
            r.setTimeout(10000, () => r.destroy(new Error('isolated guest HTTP timeout')));
            r.end(body);
        });
        const csrf = await call('/api/auth/csrf', 'GET');
        expect(csrf.status).toBe(200);
        expect(csrf.data.headerName).toBe('X-CSRF-TOKEN');
        const r = await call('/api/guest/access/login', 'POST', { orderNumber: s.orderNumber, phone: s.phone, password: '  guest lookup 密碼  ' }, csrf.cookies, String(csrf.data.token));
        expect(r.status).toBe(200);
        expect(r.data.status).toBe('AUTHENTICATED');
        expect(r.cookies.some(v => v.startsWith('GUEST_ACCESS='))).toBe(true);
        await c.addCookies([...csrf.cookies, ...r.cookies].map(raw => { const [pair] = raw.split(';'), [name, ...value] = pair.split('='); return { name, value: value.join('='), domain: '127.0.0.1', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' as const }; }));
    });
}
test('memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'member'), n = s.orderNumber;
    await prepare("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [1]", async () => {
        await login(context, s.email);
    });
    const { original, initial, q, bodies } = await operation("order-cancellation: 취소 진행 다시 조회 [2]", async () => {
        const original = await detail(context, n), initial = await database(context, n);
        await page.goto('/account/orders/' + n);
        await expect(region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true })).toBeEnabled();
        const q = await quote(page, original, s.sellerOrderId, 4), bodies: Record<string, unknown>[] = [];
        return { original, initial, q, bodies };
    });
    await prepare("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [3]", async () => {
        await page.route('**/api/orders/' + n + '/cancellations', async (route) => {
            if (route.request().method() !== 'POST') {
                await route.continue();
                return;
            }
            bodies.push(route.request().postDataJSON());
            const r = await route.fetch();
            expect(r.status()).toBe(202);
            if (bodies.length === 1)
                await route.abort('failed');
            else
                await route.fulfill({ response: r });
        });
    });
    await operation("order-cancellation: submit lost cancellation through one durable refund and restitution", async () => {
        await operation("order-cancellation: 선택 수량 취소 요청 [4]", async () => {
            await region(page).getByRole('button', { name: '선택 수량 취소 요청', exact: true }).dblclick();
            await expect(region(page).getByRole('button', { name: '같은 취소 요청으로 결과 확인', exact: true })).toBeVisible();
            expect(bodies).toHaveLength(1);
        });
        const { attempt, db: initialDb1 } = await operation("order-cancellation: 같은 취소 요청으로 결과 확인 [5] + recovery", async () => {
            await operation("order-cancellation: 같은 취소 요청으로 결과 확인 [5]", async () => {
                await region(page).getByRole('button', { name: '같은 취소 요청으로 결과 확인', exact: true }).click();
                await expect(region(page).getByText(/취소 접수 · 접수 상품 금액/)).toBeVisible();
                expect(bodies).toHaveLength(2);
                expect(bodies[1]).toEqual(bodies[0]);
            });
            await operation("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [6]", async () => {
                await page.unroute('**/api/orders/' + n + '/cancellations');
            });
            const { attempt, db } = await operation("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [7]", async () => {
                const [progress] = await current(context, n), attempt = progress.accepted.refundAttemptId;
                expect(progress.accepted.units).toEqual(q.units);
                const db = await database(context, n);
                expect(db.claim).toHaveLength(1);
                expect(db.claim_unit).toHaveLength(1);
                expect(db.claim_unit[0].net).toBe(Number(q.itemAmount));
                expect(db.order_unit_lifecycle.filter(u => u.state === 'HELD')).toHaveLength(1);
                expect(String(db.payment_refund_budget[0].reserved)).toBe(q.itemAmount);
                expect(db.restored).toHaveLength(0);
                return { attempt, db };
            });
            return { attempt, db };
        });
        let db = initialDb1;
        await operation("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [8]", async () => {
            await work(context, n, attempt, 'submit');
            db = await database(context, n);
            expect(db.payment_refund_attempt[0].state).toBe('SUCCEEDED');
            expect(db.restored).toHaveLength(0);
        });
        await operation("order-cancellation: 취소 진행 다시 조회 [9]", async () => {
            await work(context, n, attempt, 'result');
            db = await database(context, n);
            expect(db.restored.map(u => ({ itemId: u.item_id, ordinal: u.ordinal }))).toEqual(q.units.map(({ itemId, ordinal }) => ({ itemId, ordinal })));
            expect(db.claim_refund_effect).toHaveLength(1);
            expect(db.provider[0]).toMatchObject({ submit_calls: 1, effects: 1 });
            expect(db.coupons).toHaveLength(0);
            expect(db.order.state).toBe('PAID');
            expect(db.units).toEqual(initial.units);
            expect(db.order.quote).toBe(initial.order.quote);
            expect(db.inventory.every(r => r.status === 'COMMITTED')).toBe(true);
            await region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true }).click();
            await expect(region(page).getByText(/환불 처리 완료 · 접수 상품 금액/)).toBeVisible();
            expect((await detail(context, n)).stores).toEqual(original.stores);
        });
    });
    for (const width of [375, 768, 1440]) {
        await operation("order-cancellation: memberOriginalUnitLostResponseReplaysOneHoldRefundAndRestitutionAgainstApiAndDatabase [10]", async () => {
            await page.setViewportSize({ width, height: 900 });
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        });
    }
});
test('freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority', async ({ browser }, info) => {
    const { c, foreign } = await prepare("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [11]", async () => {
        const c = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' }), foreign = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { c, foreign };
    });
    try {
        const s = await seed(c, info.project.name, 'guest'), n = s.orderNumber;
        await operation("order-cancellation: /api/orders/ [12]", async () => {
            expect((await c.request.get('/api/orders/' + n + '/cancellations')).status()).toBe(404);
        });
        await prepare("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [13]", async () => {
            await guestLogin(c, s);
        });
        const { d, p } = await prepare("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [14]", async () => {
            const d = await detail(c, n), p = await c.newPage();
            return { d, p };
        });
        const { q } = await operation("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [15]", async () => {
            await p.goto('/guest/orders/' + n);
            const q = await quote(p, d, s.sellerOrderId, 0);
            return { q };
        });
        await operation("order-cancellation: 선택 수량 취소 요청 [16]", async () => {
            await region(p).getByRole('button', { name: '선택 수량 취소 요청', exact: true }).click();
            await expect(region(p).getByLabel('접수 철회 사유', { exact: true })).toBeVisible();
            const [accepted] = await current(c, n);
            expect(accepted.accepted.itemAmount).toBe(q.itemAmount);
            expect((await foreign.request.get('/api/orders/' + n + '/cancellations')).status()).toBe(404);
        });
        await operation("order-cancellation: /api/payments/ [17]", async () => {
            expect((await c.request.post('/api/payments/' + n + '/approve', { headers: await headers(c), data: {} })).status()).toBe(401);
        });
        await operation("order-cancellation: 접수 철회 [18]", async () => {
            await region(p).getByLabel('접수 철회 사유', { exact: true }).fill('미제출 요청 철회');
            await region(p).getByRole('button', { name: '접수 철회', exact: true }).click();
            await expect(region(p).getByText(/접수 철회 · 접수 상품 금액/)).toBeVisible();
            const db = await database(c, n);
            expect(db.claim[0].state).toBe('WITHDRAWN');
            expect(db.payment_refund_attempt[0].state).toBe('WITHDRAWN');
            expect(db.payment_refund_budget[0].reserved).toBe(0);
            expect(db.order_unit_lifecycle.every(u => u.state === 'OPEN')).toBe(true);
            expect(db.provider).toHaveLength(0);
            expect(db.restored).toHaveLength(0);
        });
        await operation("order-cancellation: /api/guest/access/logout [19]", async () => {
            expect((await c.request.post('/api/guest/access/logout', { headers: await headers(c), data: {} })).status()).toBe(200);
            expect((await c.request.get('/api/orders/' + n + '/cancellations')).status()).toBe(404);
            await p.evaluate(() => window.dispatchEvent(new Event('focus')));
            await expect(p.getByTestId('order-number')).toHaveCount(0);
        });
    }
    finally {
        await cleanup("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [20]", async () => {
            await c.close();
        });
        await cleanup("order-cancellation: freshGuestCurrentGasOwnsQuoteAndReservedWithdrawalWithoutPurchaseOrForeignAuthority [21]", async () => {
            await foreign.close();
        });
    }
});
test('cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase', async ({ page, context, browser }, info) => {
    const { s, n, guard } = await prepare("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [22]", async () => {
        const s = await seed(context, info.project.name, 'failure'), n = s.orderNumber, guard = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { s, n, guard };
    });
    try {
        await prepare("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [23]", async () => {
            await login(context, s.email);
        });
        await prepare("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [24]", async () => {
            await login(guard, s.email);
        });
        const { d, q } = await operation("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [25]", async () => {
            const d = await detail(context, n);
            await page.goto('/account/orders/' + n);
            const q = await quote(page, d, s.sellerOrderId, 0);
            return { d, q };
        });
        await operation("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [26]", async () => {
            await directCancel(guard, n, q, 'other-current-owner');
        });
        let fail = true, posts = 0;
        page.on('request', r => {
            if (r.method() === 'POST' && r.url().endsWith('/cancellations'))
                posts++;
        });
        await prepare("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [27]", async () => {
            await page.route('**/api/orders/' + n + '/cancellations', async (route) => {
                if (route.request().method() === 'GET' && fail) {
                    fail = false;
                    await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' });
                }
                else
                    await route.continue();
            });
        });
        await operation("order-cancellation: 선택 수량 취소 요청 [28]", async () => {
            const rejected = page.waitForResponse(r => r.url().endsWith('/cancellations') && r.request().method() === 'POST');
            await region(page).getByRole('button', { name: '선택 수량 취소 요청', exact: true }).click();
            expect((await rejected).status()).toBe(409);
            await expect(region(page).getByRole('alert')).toContainText('현재');
            await expect(region(page).getByRole('button', { name: '같은 취소 요청으로 결과 확인', exact: true })).toHaveCount(0);
            expect(await current(guard, n)).toHaveLength(1);
            expect(posts).toBe(1);
            await region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true }).click();
            await expect(region(page).getByLabel('접수 철회 사유', { exact: true })).toBeVisible();
        });
        const { before, denied } = await operation("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [29] + recovery", async () => {
            const { before } = await operation("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [29]", async () => {
                await quote(page, d, s.sellerOrderId, 1);
                const before = await database(guard, n);
                return { before };
            });
            await operation("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [30]", async () => {
                await page.unroute('**/api/orders/' + n + '/cancellations');
            });
            const { denied } = await operation("order-cancellation: /api/auth/logout [31]", async () => {
                const denied = page.waitForResponse(r => r.url().endsWith('/cancellations') && r.request().method() === 'POST');
                return { denied };
            });
            return { before, denied };
        });
        await operation("order-cancellation: 선택 수량 취소 요청 [32]", async () => {
            await page.route('**/api/orders/' + n + '/cancellations', bindDeadline(async (route) => {
                if (route.request().method() === 'POST') {
                    expect((await context.request.post('/api/auth/logout', { headers: await headers(context) })).status()).toBe(200);
                }
                await route.continue();
            }), { times: 1 });
            await region(page).getByRole('button', { name: '선택 수량 취소 요청', exact: true }).click();
            expect([401, 403, 404]).toContain((await denied).status());
            await expect(page.getByTestId('order-number')).toHaveCount(0);
            await expect(page.getByRole('button', { name: '선택 수량 취소 요청', exact: true })).toHaveCount(0);
            expect(await database(guard, n)).toEqual(before);
            expect(posts).toBe(2);
            await page.reload();
            expect(posts).toBe(2);
        });
    }
    finally {
        await cleanup("order-cancellation: cancelConflictFailedRefreshAndActualLogoutNeverRetainPrivateCommandsOrChangeDatabase [33]", async () => {
            await guard.close();
        });
    }
});
test('lateCancellationReadAfterLogoutDiscardsPrivateProgressAndCommands', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'late'), n = s.orderNumber;
    await prepare("order-cancellation: lateCancellationReadAfterLogoutDiscardsPrivateProgressAndCommands [34]", async () => {
        await login(context, s.email);
    });
    const { originalProgress } = await operation("order-cancellation: lateCancellationReadAfterLogoutDiscardsPrivateProgressAndCommands [35]", async () => {
        const originalProgress = await current(context, n);
        expect(originalProgress).toHaveLength(1);
        return { originalProgress };
    });
    await operation("order-cancellation: 취소 진행 다시 조회 [36]", async () => {
        await page.goto('/account/orders/' + n);
        await expect(region(page).getByText(/취소 접수 · 접수 상품 금액/)).toBeVisible();
        await expect(region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true })).toBeEnabled();
    });
    let release!: () => void, captured!: () => void;
    const held = new Promise<void>(r => release = r), ready = new Promise<void>(r => captured = r);
    await prepare("order-cancellation: lateCancellationReadAfterLogoutDiscardsPrivateProgressAndCommands [37]", async () => {
        await page.route('**/api/orders/' + n + '/cancellations', async (route) => {
            const response = await route.fetch();
            expect(await response.json()).toEqual(originalProgress);
            captured();
            await held;
            try {
                await route.fulfill({ response });
            }
            catch { /* current authority generation can abort the held request */ }
        });
    });
    await operation("order-cancellation: 취소 진행 다시 조회 [38]", async () => {
        await region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true }).click();
        await ready;
        expect((await context.request.post('/api/auth/logout', { headers: await headers(context) })).status()).toBe(200);
        await page.evaluate(() => window.dispatchEvent(new Event('focus')));
        release();
        await expect(page.getByTestId('order-number')).toHaveCount(0);
        await expect(page.getByText(/접수 상품 금액/)).toHaveCount(0);
        await expect(page.getByLabel('접수 철회 사유', { exact: true })).toHaveCount(0);
        await expect(page.getByRole('button', { name: '취소 수량 선택 열기', exact: true })).toHaveCount(0);
        expect((await context.request.get('/api/orders/' + n + '/cancellations')).status()).toBe(404);
    });
});
test('sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'policy'), n = s.orderNumber;
    await prepare("order-cancellation: sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot [39]", async () => {
        await login(context, s.email);
    });
    const { original } = await operation("order-cancellation: sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot [40]", async () => {
        const original = await detail(context, n);
        await page.goto('/seller/products');
        await page.getByRole('button', { name: s.title + ' · 공개 등록', exact: true }).click();
        await expect(page.getByLabel('변심 반품 회수비', { exact: true })).toHaveValue('3000');
        return { original };
    });
    await operation("order-cancellation: 상품 저장 [41]", async () => {
        await page.getByLabel('변심 반품 회수비', { exact: true }).fill('0');
        const saved = page.waitForResponse(r => r.request().method() === 'PUT' && r.url().endsWith('/api/seller/products/' + s.productId));
        await page.getByRole('button', { name: '상품 저장', exact: true }).click();
        expect((await saved).status()).toBe(200);
        await expect(page.getByLabel('변심 반품 회수비', { exact: true })).toHaveValue('0');
        const zero = (await (await context.request.get('/api/browse/products/' + s.slug)).json()).product;
        expect(zero.returnPolicy.amount).toBe('0');
    });
    await operation("order-cancellation: sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot [42]", async () => {
        await page.goto('/products/' + s.slug);
        await expect(page.getByTestId('product-return-policy')).toContainText('회수비 0원');
    });
    await operation("order-cancellation: 장바구니 담기 [43]", async () => {
        await page.getByRole('button', { name: '장바구니 담기', exact: true }).click();
        await expect(page.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible();
    });
    const { total } = await operation("order-cancellation: sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot [44]", async () => {
        await page.goto('/checkout');
        await expect(page.getByTestId('quote-return-policy')).toBeVisible();
        await expect(page.getByTestId('quote-return-policy')).toContainText('회수비 0원');
        const total = await page.getByTestId('quote-total').textContent();
        return { total };
    });
    await operation("order-cancellation: 현재 상품·수량·견적과 배송 정보를 확인했습니다 [45]", async () => {
        for (const [label, value] of Object.entries({ '주문 구매자 이름': 'C 구매자', '주문 구매자 전화번호': '01012345678', '수령인 이름': 'C 수령인', '수령인 전화번호': '01087654321', '우편번호': '01234', '기본 주소': '서울시 C 구매 주소', '상세 주소': '101호' })) {
            await operation("order-cancellation: sellerFeeZeroAndFiveThousandAreVisibleBeforePurchaseStaleQuoteAndImmutableOrderSnapshot [46]", async () => {
                await page.getByLabel(label, { exact: true }).fill(value);
            });
        }
        await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다', exact: true }).check();
    });
    let changed = false;
    const { stale } = await operation("order-cancellation: /api/seller/products [47]", async () => {
        const stale = page.waitForResponse(r => r.url().endsWith('/api/orders') && r.request().method() === 'POST');
        return { stale };
    });
    await operation("order-cancellation: 주문 생성 [48]", async () => {
        await page.route('**/api/orders', bindDeadline(async (route) => {
            if (route.request().method() === 'POST' && !changed) {
                changed = true;
                const p = (await (await context.request.get('/api/seller/products')).json()).items.find((p: {
                    id: string;
                }) => p.id === s.productId);
                expect((await context.request.put('/api/seller/products/' + p.id, { headers: await headers(context), data: { categoryId: p.categoryId, slug: p.slug, title: p.title, description: p.description, state: p.state, version: p.version, returnFee: '5000' } })).status()).toBe(200);
            }
            await route.continue();
        }), { times: 1 });
        await page.getByRole('button', { name: '주문 생성', exact: true }).click();
        expect((await stale).status()).toBe(409);
        await expect(page.getByTestId('created-order-number')).toHaveCount(0);
    });
    await operation("order-cancellation: 새 견적을 확인하고 다시 주문 준비 [49]", async () => {
        await page.getByRole('button', { name: '새 견적을 확인하고 다시 주문 준비', exact: true }).click();
        await expect(page.getByTestId('quote-return-policy')).toContainText('회수비 5,000원');
        expect(await page.getByTestId('quote-total').textContent()).toBe(total);
    });
    await operation("order-cancellation: 현재 상품·수량·견적과 배송 정보를 확인했습니다 [50]", async () => {
        await page.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다', exact: true }).check();
        await page.getByRole('button', { name: '주문 생성', exact: true }).click();
        const created = await page.getByTestId('created-order-number').textContent();
        expect(created).toMatch(/^O[0-9a-f]{32}$/);
        const fresh = await detail(context, created!);
        expect(fresh.stores[0].items[0].returnPolicy?.amount).toBe('5000');
        expect((await detail(context, n)).stores).toEqual(original.stores);
        const db = await database(context, created!);
        expect(JSON.parse(String(db.order.quote)).stores[0].lines[0].returnPolicy.amount).toBe('5000');
    });
});
test('latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'latest'), n = s.orderNumber;
    await prepare("order-cancellation: latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected [51]", async () => {
        await login(context, s.email);
    });
    const { before } = await operation("order-cancellation: latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected [52]", async () => {
        const [p] = await current(context, n);
        const refunds = [...p.refunds].sort((a, b) => a.purpose.localeCompare(b.purpose) || a.sequence - b.sequence);
        expect(refunds.map(r => ({ purpose: r.purpose, sequence: r.sequence, state: r.state }))).toEqual([{ purpose: 'ITEM', sequence: 1, state: 'FAILED' }, { purpose: 'ITEM', sequence: 2, state: 'UNKNOWN' }]);
        const before = await database(context, n);
        expect(before.restored).toHaveLength(0);
        expect(BigInt(String(before.payment_refund_budget[0].reserved))).toBeGreaterThan(0n);
        return { before };
    });
    let posts = 0;
    await operation("order-cancellation: latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected [53]", async () => {
        page.on('request', r => {
            if (r.method() === 'POST' && r.url().includes('/cancellations'))
                posts++;
        });
        await page.goto('/account/orders/' + n);
        await expect(region(page).getByText('최신 환불 결과를 확인하는 동안에는 접수 철회를 할 수 없습니다.', { exact: true })).toBeVisible();
        await expect(region(page).getByLabel('접수 철회 사유', { exact: true })).toHaveCount(0);
    });
    await prepare("order-cancellation: latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected [54]", async () => {
        await page.route('**/api/orders/' + n + '/cancellations', async (route) => { const r = await route.fetch(), body = await r.json() as ClaimProgress[]; await route.fulfill({ response: r, json: body.map(p => ({ ...p, state: 'COMPLETED' })) }); });
    });
    await operation("order-cancellation: 취소 진행 다시 조회 [55]", async () => {
        await region(page).getByRole('button', { name: '취소 진행 다시 조회', exact: true }).click();
        await expect(region(page).getByRole('alert')).toBeVisible();
        await expect(region(page).getByText(/환불 처리 완료 · 접수 상품 금액/)).toHaveCount(0);
        expect(posts).toBe(0);
        expect(await database(context, n)).toEqual(before);
        expect((await current(context, n))[0].state).toBe('REVIEW_REQUIRED');
    });
});
