import { currentDeadline, operation, prepare, cleanup } from './business-deadline';
import { test, expect, type BrowserContext, type Page } from './business-deadline';
import { request as rawRequest } from 'node:http';
import { headers } from './purchase-helpers';
import type { Current, Page as ExchangePage, Receipt, Progress } from '../src/exchanges/ExchangeTypes';
type Seed = {
    orderNumber: string;
    sellerOrderId: string;
    targetSkuId: string;
    guest: boolean;
    phone?: string;
    buyerEmail: string;
    sellerEmail: string;
    supportEmail: string;
    exchangeId?: string;
};
type Row = Record<string, unknown>;
type Database = {
    order: Row;
    purchaseReservations: Row[];
    inventory_exchange_reservation: Row[];
    inventory: Row[];
    order_physical_source: Row[];
    order_delivery_bucket: Row[];
    claim_exchange_request: Row[];
    claim_exchange_refund_consent: Row[];
    claim_exchange_refund_completion: Row[];
    payment_refund_attempt: Row[];
    provider: Row[];
    restitutions: Row[];
    claim_return_request: Row[];
};
const password = ' A1-private-passphrase-가나다 ';
const panel = (p: Page) => p.getByRole('region', { name: '동일 가격 교환', exact: true });
const base = (s: Seed) => '/api/orders/' + s.orderNumber + '/exchanges';
async function seed(c: BrowserContext, project: string, scenario: string) { return prepare("order-exchanges: seed", async () => { const r = await c.request.get('/api/__test/exchanges/seed/' + project + '/' + scenario); expect(r.status(), await r.text()).toBe(200); return await r.json() as Seed; }); }
async function login(c: BrowserContext, email: string) { return operation("order-exchanges: login", async () => { expect((await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password } })).status()).toBe(200); }); }
async function guestLogin(c: BrowserContext, s: Seed) {
    return operation("order-exchanges: guestLogin", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('isolated Java endpoint required');
        const call = (path: string, method: string, data?: unknown, cookies: string[] = [], token?: string) => new Promise<{
            status: number;
            data: Record<string, unknown>;
            cookies: string[];
        }>((resolve, reject) => {
            const transportTimeout = currentDeadline().remaining(10000);
            const body = data === undefined ? undefined : JSON.stringify(data), r = rawRequest({ hostname: '127.0.0.1', port, path, method, localAddress: '127.0.0.8', headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }), ...(cookies.length ? { Cookie: cookies.map(v => v.split(';')[0]).join('; ') } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}) } }, response => {
                let text = '';
                response.setEncoding('utf8');
                response.on('data', chunk => { text += chunk; });
                response.on('end', () => {
                    try {
                        resolve({ status: response.statusCode ?? 0, data: JSON.parse(text) as Record<string, unknown>, cookies: response.headers['set-cookie'] ?? [] });
                    }
                    catch (error) {
                        reject(error);
                    }
                });
            });
            const absoluteTimer = setTimeout(() => r.destroy(new Error('Browser operation transport deadline exceeded')), transportTimeout);
            r.once('close', () => clearTimeout(absoluteTimer));
            r.on('error', reject);
            r.setTimeout(10000, () => r.destroy(new Error('isolated E guest login timeout')));
            r.end(body);
        });
        const csrf = await call('/api/auth/csrf', 'GET');
        expect(csrf.status).toBe(200);
        expect(csrf.data.headerName).toBe('X-CSRF-TOKEN');
        const result = await call('/api/guest/access/login', 'POST', { orderNumber: s.orderNumber, phone: s.phone, password: '  guest lookup 密碼  ' }, csrf.cookies, String(csrf.data.token));
        expect(result.status).toBe(200);
        expect(result.data.status).toBe('AUTHENTICATED');
        expect(result.cookies.some(v => v.startsWith('GUEST_ACCESS='))).toBe(true);
        await c.addCookies([...csrf.cookies, ...result.cookies].map(raw => { const [pair] = raw.split(';'), [name, ...value] = pair.split('='); return { name, value: value.join('='), domain: '127.0.0.1', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' as const }; }));
    });
}
async function owner(c: BrowserContext, s: Seed) {
    return operation("order-exchanges: owner", async () => {
        if (s.guest)
            await guestLogin(c, s);
        else
            await login(c, s.buyerEmail);
    });
}
async function database(c: BrowserContext, s: Seed) { return operation("order-exchanges: database", async () => { const r = await c.request.get('/api/__test/exchanges/' + s.orderNumber + '/database'); expect(r.status(), await r.text()).toBe(200); return await r.json() as Database; }); }
async function current(c: BrowserContext, s: Seed, id: string) { return operation("order-exchanges: current", async () => { const r = await c.request.get(base(s) + '/' + id + '/view'); expect(r.status(), await r.text()).toBe(200); expect(r.headers()['cache-control']).toContain('no-store'); return await r.json() as Current; }); }
async function work(c: BrowserContext, s: Seed, id: string, action: 'DISPATCH' | 'DELIVER' | 'ITEM' | 'SHIPPING') { return operation("order-exchanges: work", async () => { const r = await c.request.post('/api/__test/exchanges/' + s.orderNumber + '/work', { headers: await headers(c), data: { exchangeId: id, action } }); expect(r.status(), await r.text()).toBe(200); expect((await r.json()).actualJobs).toBe(true); }); }
async function mock(c: BrowserContext, s: Seed, id: string, purpose: 'ITEM' | 'SHIPPING', behavior: string) { return operation("order-exchanges: mock", async () => { const r = await c.request.post('/api/__test/exchanges/' + s.orderNumber + '/mock', { headers: await headers(c), data: { exchangeId: id, purpose, behavior } }); expect(r.status(), await r.text()).toBe(200); }); }
async function open(p: Page, s: Seed, actor: 'buyer' | 'seller' | 'support' = 'buyer') {
    return operation("order-exchanges: open", async () => {
        await p.goto(actor === 'seller' ? '/seller/orders/' + s.sellerOrderId : actor === 'support' ? '/admin/orders' : (s.guest ? '/guest/orders/' : '/account/orders/') + s.orderNumber);
        if (actor === 'support') {
            await p.getByLabel('지원할 주문번호', { exact: true }).fill(s.orderNumber);
            await p.getByRole('button', { name: '이 주문 반품 조회', exact: true }).click();
        }
        await expect(panel(p).getByRole('button', { name: '교환 정보 다시 조회', exact: true })).toBeEnabled();
    });
}
async function refresh(p: Page) { return operation("order-exchanges: refresh", async () => { await panel(p).getByRole('button', { name: '교환 정보 다시 조회', exact: true }).click(); await expect(panel(p).getByRole('button', { name: '교환 정보 다시 조회', exact: true })).toBeEnabled(); }); }
async function request(p: Page, c: BrowserContext, s: Seed, responsibility: 'BUYER' | 'SELLER') {
    return operation("order-exchanges: request", async () => {
        const r = await c.request.get(base(s));
        expect(r.status()).toBe(200);
        const page = await r.json() as ExchangePage;
        expect(page.choices.length).toBeGreaterThan(0);
        for (const choice of page.choices) {
            const label = choice.title + ' 개별 ' + (choice.ordinal + 1);
            await panel(p).getByRole('combobox', { name: label + ' 교환 대상', exact: true }).selectOption(s.targetSkuId);
            await panel(p).getByRole('combobox', { name: label + ' 신청 책임', exact: true }).selectOption(responsibility);
        }
        await panel(p).getByLabel('교환 신청 사유', { exact: true }).fill('현재 실제 원단위 동일가격 교환');
        const wait = p.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === base(s));
        await panel(p).getByRole('button', { name: '교환 신청', exact: true }).click();
        const response = await wait;
        expect(response.status()).toBe(202);
        const accepted = await response.json() as Receipt;
        await expect(panel(p).getByRole('button', { name: '교환 정보 다시 조회', exact: true })).toBeEnabled();
        return accepted;
    });
}
async function seller(p: Page, id: string, action: 'approve' | 'receive' | 'inspect' | 'extend', condition: 'SALEABLE' | 'DEFECTIVE' = 'SALEABLE', responsibility: 'BUYER' | 'SELLER' = 'BUYER') {
    return operation("order-exchanges: seller", async () => {
        await panel(p).getByLabel('교환 처리 사유', { exact: true }).fill('실제 판매자 ' + action);
        if (action === 'receive')
            for (const select of await panel(p).getByRole('combobox', { name: /회수 결과$/ }).all())
                await select.selectOption('RECEIVED');
        if (action === 'inspect') {
            for (const select of await panel(p).getByRole('combobox', { name: /검수 상태$/ }).all())
                await select.selectOption(condition);
            for (const select of await panel(p).getByRole('combobox', { name: /확인 책임$/ }).all())
                await select.selectOption(responsibility);
        }
        const labels = { approve: '교환 승인·재고 예약', receive: '교환 실제 회수 결과 기록', inspect: '교환 실제 검수 결과 기록', extend: '사유를 기록하고 예약 7일 1회 연장' }, wait = p.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/seller/exchanges/' + id + '/' + action);
        await panel(p).getByRole('button', { name: labels[action], exact: true }).click();
        const response = await wait;
        expect(response.status(), await response.text()).toBe(200);
        const progress = await response.json() as Progress;
        await expect(panel(p).getByRole('button', { name: '교환 정보 다시 조회', exact: true })).toBeEnabled();
        return progress;
    });
}
test('exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow', async ({ page, browser }, info) => {
    const s = await seed(page.context(), info.project.name, 'member');
    await prepare("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [1]", async () => {
        await owner(page.context(), s);
    });
    const { before } = await operation("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [2]", async () => {
        const before = await database(page.context(), s);
        await open(page, s);
        return { before };
    });
    const { accepted, sellerContext, sellerPage } = await prepare("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [3]", async () => {
        const accepted = await request(page, page.context(), s, 'BUYER'), sellerContext = await browser.newContext(), sellerPage = await sellerContext.newPage();
        return { accepted, sellerContext, sellerPage };
    });
    try {
        await prepare("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [4]", async () => {
            await login(sellerContext, s.sellerEmail);
        });
        await open(sellerPage, s, 'seller');
        const approved = await seller(sellerPage, accepted.exchangeId, 'approve');
        await operation('order-exchanges: extend approved replacement reservation by one week', async () => {
            const extended = await seller(sellerPage, accepted.exchangeId, 'extend');
            expect(Date.parse(extended.reservations[0].expiresAt) - Date.parse(approved.reservations[0].expiresAt)).toBe(604800000);
        });
        await seller(sellerPage, accepted.exchangeId, 'receive');
        await seller(sellerPage, accepted.exchangeId, 'inspect');
        const bodies: string[] = [];
        let lost = true;
        const path = '/api/seller/exchanges/' + accepted.exchangeId + '/reship';
        await prepare("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [6]", async () => {
            await sellerPage.route('**' + path, async (route) => {
                bodies.push(route.request().postData()!);
                if (lost) {
                    lost = false;
                    expect((await route.fetch()).status()).toBe(202);
                    await route.abort('failed');
                }
                else
                    await route.continue();
            });
        });
        await operation("order-exchanges: reship lost response through actual dispatch effect", async () => {
            await operation("order-exchanges: 검수한 대체품 발송 요청 [7]", async () => {
                await panel(sellerPage).getByLabel('교환 처리 사유', { exact: true }).fill('검수 후 실제 대체품 출고');
                await panel(sellerPage).getByRole('button', { name: '검수한 대체품 발송 요청', exact: true }).click();
                await panel(sellerPage).getByRole('button', { name: '같은 요청으로 교환 결과 확인', exact: true }).click();
                await expect(panel(sellerPage).getByText('대체품 발송 대기', { exact: true })).toBeVisible();
                expect(bodies).toHaveLength(2);
                expect(bodies[0]).toBe(bodies[1]);
                expect(Object.keys(JSON.parse(bodies[0])).sort()).toEqual(['expectedRevision', 'key', 'reason']);
            });
            await operation("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [8]", async () => {
                await work(page.context(), s, accepted.exchangeId, 'DISPATCH');
                await refresh(sellerPage);
                await expect(panel(sellerPage).getByText('대체품 발송 완료·배송 중', { exact: true })).toBeVisible();
                await expect(panel(sellerPage).getByText('대체품 배송 완료', { exact: true })).not.toBeVisible();
            });
        });
        await operation("order-exchanges: /api/orders/ [9]", async () => {
            await work(page.context(), s, accepted.exchangeId, 'DELIVER');
            await refresh(page);
            await expect(panel(page).getByText('대체품 배송 완료', { exact: true })).toBeVisible();
            const view = await current(page.context(), s, accepted.exchangeId), after = await database(page.context(), s);
            expect(view.replacementDelivered).toBe(true);
            expect(after.purchaseReservations).toEqual(before.purchaseReservations);
            expect(after.inventory_exchange_reservation).toHaveLength(1);
            expect(after.inventory_exchange_reservation[0].status).toBe('COMMITTED');
            expect(after.order_physical_source.some(r => r.round === 1 && r.predecessor_id)).toBe(true);
            expect(after.order_delivery_bucket.filter(r => r.kind === 'EXCHANGE')).toHaveLength(1);
            const response = await page.context().request.get('/api/orders/' + s.orderNumber + '/confirmations'), confirmation = await response.json() as {
                units: {
                    eligible: boolean;
                    deliveredAt: string;
                    dueAt: string;
                }[];
            };
            expect(response.status()).toBe(200);
            expect(confirmation.units.every(u => u.eligible && Date.parse(u.dueAt) - Date.parse(u.deliveredAt) === 604800000)).toBe(true);
        });
    }
    finally {
        await cleanup("order-exchanges: exchangeMemberActualReshipResponseLossKeepsOneReservationAndNewPhysicalDeliveryWindow [10]", async () => {
            await sellerContext.close();
        });
    }
});
test('exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery', async ({ page, browser }, info) => {
    const s = await seed(page.context(), info.project.name, 'guest');
    await prepare("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [11]", async () => {
        await owner(page.context(), s);
    });
    await operation("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [12]", async () => {
        await open(page, s);
    });
    const { accepted, sellerContext, sellerPage } = await prepare("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [13]", async () => {
        const accepted = await request(page, page.context(), s, 'SELLER'), sellerContext = await browser.newContext(), sellerPage = await sellerContext.newPage();
        return { accepted, sellerContext, sellerPage };
    });
    try {
        await prepare("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [14]", async () => {
            await login(sellerContext, s.sellerEmail);
        });
        await operation("order-exchanges: 교환 승인·재고 예약 [15]", async () => {
            await open(sellerPage, s, 'seller');
            await expect(panel(sellerPage).getByRole('button', { name: '교환 승인·재고 예약', exact: true })).toHaveCount(0);
            expect((await database(page.context(), s)).inventory_exchange_reservation).toHaveLength(0);
        });
        await seller(sellerPage, accepted.exchangeId, 'receive');
        await seller(sellerPage, accepted.exchangeId, 'inspect', 'DEFECTIVE', 'SELLER');
        await seller(sellerPage, accepted.exchangeId, 'approve');
        await operation("order-exchanges: guest replacement command through actual dispatch", async () => {
            await operation("order-exchanges: 검수한 대체품 발송 요청 [16]", async () => {
                await panel(sellerPage).getByLabel('교환 처리 사유', { exact: true }).fill('기존 거래 SELLER 실검수 대체 발송');
                const wait = sellerPage.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/' + accepted.exchangeId + '/reship'));
                await panel(sellerPage).getByRole('button', { name: '검수한 대체품 발송 요청', exact: true }).click();
                expect((await wait).status()).toBe(202);
            });
            await operation("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [17]", async () => {
                await work(page.context(), s, accepted.exchangeId, 'DISPATCH');
            });
        });
        await operation("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [18]", async () => {
            await work(page.context(), s, accepted.exchangeId, 'DELIVER');
            await refresh(page);
            await expect(panel(page).getByText('대체품 배송 완료', { exact: true })).toBeVisible();
            const after = await database(page.context(), s);
            expect(after.inventory_exchange_reservation).toHaveLength(accepted.units.length);
            expect(after.inventory_exchange_reservation.every(r => r.status === 'COMMITTED')).toBe(true);
            expect(after.restitutions).toHaveLength(0);
            expect((await current(page.context(), s, accepted.exchangeId)).eligibility.storeState).toBe('CLOSED');
        });
    }
    finally {
        await cleanup("order-exchanges: exchangeGasClosedStoreRequiresActualSellerInspectionBeforeReservationAndReplacementDelivery [19]", async () => {
            await sellerContext.close();
        });
    }
});
test('exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice', async ({ page, browser }, info) => {
    const s = await seed(page.context(), info.project.name, 'refund');
    await prepare("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [20]", async () => {
        await owner(page.context(), s);
    });
    await operation("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [21]", async () => {
        await open(page, s);
    });
    const { accepted, sellerContext, sellerPage, supportContext, supportPage } = await prepare("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [22]", async () => {
        const accepted = await request(page, page.context(), s, 'BUYER'), sellerContext = await browser.newContext(), sellerPage = await sellerContext.newPage(), supportContext = await browser.newContext(), supportPage = await supportContext.newPage();
        return { accepted, sellerContext, sellerPage, supportContext, supportPage };
    });
    try {
        await prepare("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [23]", async () => {
            await login(sellerContext, s.sellerEmail);
        });
        await open(sellerPage, s, 'seller');
        await seller(sellerPage, accepted.exchangeId, 'approve');
        await seller(sellerPage, accepted.exchangeId, 'receive');
        await seller(sellerPage, accepted.exchangeId, 'inspect');
        await operation("order-exchanges: 교환 대신 환불할 최종 견적 확인 [24]", async () => {
            await refresh(page);
            await panel(page).getByRole('button', { name: '교환 대신 환불할 최종 견적 확인', exact: true }).click();
            await expect(panel(page).getByRole('region', { name: '교환 환불 최종 견적', exact: true })).toContainText('5,000원');
        });
        await operation("order-exchanges: 명시 동의한 환불 전환 요청 [25]", async () => {
            await panel(page).getByLabel('교환 처리 사유', { exact: true }).fill('원 주문 정책을 확인한 명시 환불');
            await expect(panel(page).getByRole('button', { name: '명시 동의한 환불 전환 요청', exact: true })).toBeDisabled();
        });
        await prepare("order-exchanges: prepare support observer before buyer consent", async () => {
            await login(supportContext, s.supportEmail);
        });
        const { before } = await operation("order-exchanges: original-policy refund consent through UNKNOWN and retained money", async () => {
            const { before } = await operation("order-exchanges: 이 견적의 환불·회수비·미수납을 확인하고 교환 대신 환불에 동의합니다 [26]", async () => {
                await panel(page).getByRole('checkbox', { name: '이 견적의 환불·회수비·미수납을 확인하고 교환 대신 환불에 동의합니다', exact: true }).check();
                const wait = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/refund-consent'));
                await panel(page).getByRole('button', { name: '명시 동의한 환불 전환 요청', exact: true }).click();
                expect((await wait).status()).toBe(202);
                const before = await database(page.context(), s);
                await mock(page.context(), s, accepted.exchangeId, 'ITEM', 'LOST_RESPONSE');
                return { before };
            });
            await operation("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [27]", async () => {
                await work(page.context(), s, accepted.exchangeId, 'ITEM');
            });
            await operation("order-exchanges: 교환 상품 환불 [29]", async () => {
                await open(supportPage, s, 'support');
                await expect(panel(supportPage).getByRole('region', { name: '교환 상품 환불', exact: true })).toContainText('UNKNOWN');
                await expect(panel(supportPage).getByRole('button', { name: '상품 환불 확정 실패 새 시도 요청', exact: true })).toHaveCount(0);
            });
            return { before };
        });
        await operation("order-exchanges: same-attempt query through worker completion and CLOSED database", async () => {
            await operation("order-exchanges: 상품 환불 같은 시도 조회 [30]", async () => {
                await panel(supportPage).getByLabel('교환 처리 사유', { exact: true }).fill('실제 동일 시도 결과 조회');
                const query = supportPage.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/refunds/ITEM/query'));
                await panel(supportPage).getByRole('button', { name: '상품 환불 같은 시도 조회', exact: true }).click();
                expect((await query).status()).toBe(202);
            });
            await operation("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [31]", async () => {
                await work(page.context(), s, accepted.exchangeId, 'ITEM');
                await refresh(page);
                await expect(panel(page).getByText('상품 환불 완료 근거 확인', { exact: true })).toBeVisible();
                const after = await database(page.context(), s);
                expect(after.order.state).toBe('CLOSED');
                expect(after.purchaseReservations).toEqual(before.purchaseReservations);
                expect(after.inventory).toEqual(before.inventory);
                expect(after.restitutions).toEqual(before.restitutions);
                expect(after.payment_refund_attempt).toHaveLength(1);
                expect(after.provider).toHaveLength(1);
                expect(after.provider[0].effects).toBe(1);
                expect(after.provider[0].submit_calls).toBe(1);
                expect(after.claim_return_request).toHaveLength(0);
                expect((await current(page.context(), s, accepted.exchangeId)).shipping.decision).toBe('RETAINED');
            });
        });
    }
    finally {
        await cleanup("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [32]", async () => {
            await sellerContext.close();
        });
        await cleanup("order-exchanges: exchangeBuyerExplicitOriginalPolicyRefundUnknownUsesSameAttemptAndNeverRestocksOrSubmitsTwice [33]", async () => {
            await supportContext.close();
        });
    }
});
test('exchangeOriginalShippingSupportUsesIndependentRevisionAndLeavesOriginalBuyerConsentAndItemHistoryUntouched', async ({ page, browser }, info) => {
    const s = await seed(page.context(), info.project.name, 'shipping');
    expect(s.exchangeId).toMatch(/^[0-9a-f-]{36}$/);
    const id = s.exchangeId!;
    await prepare("order-exchanges: exchangeOriginalShippingSupportUsesIndependentRevisionAndLeavesOriginalBuyerConsentAndItemHistoryUntouched [34]", async () => {
        await owner(page.context(), s);
    });
    const { before, first, supportContext, supportPage } = await prepare("order-exchanges: exchangeOriginalShippingSupportUsesIndependentRevisionAndLeavesOriginalBuyerConsentAndItemHistoryUntouched [35]", async () => {
        const before = await database(page.context(), s), first = await current(page.context(), s, id), supportContext = await browser.newContext(), supportPage = await supportContext.newPage();
        return { before, first, supportContext, supportPage };
    });
    try {
        await prepare("order-exchanges: exchangeOriginalShippingSupportUsesIndependentRevisionAndLeavesOriginalBuyerConsentAndItemHistoryUntouched [36]", async () => {
            await login(supportContext, s.supportEmail);
        });
        const { wait } = await operation("order-exchanges: 교환 원배송비 [37]", async () => {
            await open(supportPage, s, 'support');
            expect(first.shipping.operationRevision).toBe(1);
            await expect(panel(supportPage).getByRole('region', { name: '교환 원배송비', exact: true })).toContainText('FAILED');
            await expect(panel(supportPage).getByRole('button', { name: '상품 환불 확정 실패 새 시도 요청', exact: true })).toHaveCount(0);
            await panel(supportPage).getByLabel('교환 처리 사유', { exact: true }).fill('원배송비 확정 실패만 명시 재시도');
            const wait = supportPage.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/refunds/SHIPPING/retry'));
            return { wait };
        });
        await operation("order-exchanges: 원배송비 확정 실패 새 시도 요청 [38]", async () => {
            await panel(supportPage).getByRole('button', { name: '원배송비 확정 실패 새 시도 요청', exact: true }).click();
            const response = await wait;
            expect(response.status()).toBe(202);
            expect(response.request().postDataJSON().expectedRevision).toBe(1);
            await expect(panel(supportPage).getByRole('region', { name: '교환 원배송비', exact: true })).toContainText('독립 진행 2');
            expect((await current(page.context(), s, id)).progress.version).toBe(first.progress.version);
            await mock(page.context(), s, id, 'SHIPPING', 'SUCCESS');
        });
        await operation("order-exchanges: 교환 원배송비 [39]", async () => {
            await work(page.context(), s, id, 'SHIPPING');
            await refresh(supportPage);
            await expect(panel(supportPage).getByRole('region', { name: '교환 원배송비', exact: true })).toContainText('독립 진행 3');
            await expect(panel(supportPage).getByText('상품 환불 완료 근거 확인', { exact: true })).toBeVisible();
            const after = await database(page.context(), s);
            expect(after.order.state).toBe('CLOSED');
            expect(after.claim_exchange_refund_consent).toEqual(before.claim_exchange_refund_consent);
            expect(after.inventory_exchange_reservation).toEqual(before.inventory_exchange_reservation);
            expect(after.inventory).toEqual(before.inventory);
            expect(after.restitutions).toEqual(before.restitutions);
            expect(after.payment_refund_attempt.filter(r => r.purpose === 'ITEM')).toEqual(before.payment_refund_attempt.filter(r => r.purpose === 'ITEM'));
            expect(after.claim_exchange_refund_completion).toHaveLength(1);
        });
    }
    finally {
        await cleanup("order-exchanges: exchangeOriginalShippingSupportUsesIndependentRevisionAndLeavesOriginalBuyerConsentAndItemHistoryUntouched [40]", async () => {
            await supportContext.close();
        });
    }
});
