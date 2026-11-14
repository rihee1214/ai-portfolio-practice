import { boundedFetch, operation, prepare } from './business-deadline';
import { expect, type Page, type BrowserContext } from './business-deadline';
import { armIsolatedSource } from './guest-login-source';
export type Shipment = {
    id: string;
    sellerOrderId: string;
    state: string;
    version: number;
    items: {
        orderItemId: string;
        quantity: number;
    }[];
    events: {
        id: string;
        source: string;
        actor: string;
        occurredAt: string;
        processedAt: string;
        previousState: string;
        nextState: string;
        version: number;
        applied: boolean;
    }[];
};
const password = ' A1-private-passphrase-가나다 ';
export async function headers(c: BrowserContext) { return operation("purchase-helpers: headers", async () => { const d = await (await c.request.get('/api/auth/csrf')).json(); return { [d.headerName]: d.token }; }); }
export async function login(p: Page, email: string) { return operation("purchase-helpers: login", async () => { await armIsolatedSource(p, '/api/auth/login'); await p.goto('/login'); await p.getByLabel('이메일', { exact: true }).fill(email); await p.getByLabel('비밀번호', { exact: true }).fill(password); await p.getByRole('button', { name: '로그인', exact: true }).click(); await expect(p.getByText('로그인되었습니다.', { exact: true })).toBeVisible(); }); }
export async function preparedShipmentOrder(p: Page, project: string, scenario: 'shipmentsplit' | 'shipmenteditfailure' | 'shipmentkeyboard') {
    await prepare("purchase-helpers: preparedShipmentOrder [1]", async () => {
        await login(p, 'order-' + scenario + '-' + project + '@example.com');
    });
    return prepare("purchase-helpers: /api/__test/shipments/prepared/ [2]", async () => {
        const c = p.context(), seed = await c.request.get('/api/__test/shipments/prepared/' + project + '/' + scenario);
        expect(seed.status()).toBe(200);
        const { orderNumber: n } = await seed.json() as {
            orderNumber: string;
        };
        expect(n).toMatch(/^O[0-9a-f]{32}$/);
        const response = await c.request.get('/api/orders/' + n);
        expect(response.status()).toBe(200);
        const detail = await response.json();
        expect(detail.orderNumber).toBe(n);
        expect(detail.state).toBe('PENDING_PAYMENT');
        expect(detail.stores).toHaveLength(2);
        expect(new Set(detail.stores.map((s: {
            sellerOrderId: string;
        }) => s.sellerOrderId)).size).toBe(2);
        expect(detail.stores.map((s: {
            name: string;
        }) => s.name)).toEqual(['P04B', 'P04B']);
        expect(detail.stores.flatMap((s: {
            items: {
                title: string;
            }[];
        }) => s.items.map(i => i.title)).sort()).toEqual(['a', 'b'].map(side => 'P05 order-' + scenario + '-' + project + '-' + side));
        expect(detail.stores.flatMap((s: {
            items: {
                quantity: number;
            }[];
        }) => s.items.map(i => i.quantity)).sort()).toEqual(scenario === 'shipmentkeyboard' ? [1, 1] : [1, 5]);
        expect(detail.contact).toMatchObject({ recipientName: 'F2 수령인', recipientPhone: '+821087654321', postalCode: '01234', addressLine1: '서울시 배송 검증 주소 ' + '긴주소'.repeat(18), addressLine2: '101호' });
        // This fixture-only reader stays within preparation; shared business reads retain their operation deadline.
        const read = async (c: BrowserContext, n: string) => {
            const r = await c.request.get('/api/orders/' + n + '/shipments');
            expect(r.status()).toBe(200);
            expect(r.headers()['cache-control']).toContain('no-store');
            return await r.json() as Shipment[];
        };
        expect(await read(c, n)).toEqual([]);
        return n;
    });
}
export async function create(p: Page, project: string, scenario: string, guest = false, phone = '', options: {
    coupons?: boolean;
    loseOrderResponse?: boolean;
    quantity?: number;
    onCreated?: (orderNumber: string) => Promise<void>;
} = {}) {
    const prefix = 'p4b-browse-order-' + scenario + '-' + project;
    if (!guest)
        await prepare('purchase-helpers: member purchase session', async () => login(p, 'order-' + scenario + '-' + project + '@example.com'));
    for (const side of ['a', 'b']) {
        await prepare('purchase-helpers: cart item input', async () => {
            await p.goto('/products/' + prefix + '-' + side);
            if (side === 'a' && options.quantity)
                await p.getByLabel('담을 수량', { exact: true }).fill(String(options.quantity));
        });
        await operation('purchase-helpers: add one store item', async () => { await p.getByRole('button', { name: '장바구니 담기', exact: true }).click(); await expect(p.getByText(/장바구니.*변경했습니다|장바구니에 담았습니다/)).toBeVisible(); });
    }
    if (guest) {
        await prepare('purchase-helpers: guest phone input', async () => { await p.goto('/checkout/guest'); await p.getByLabel('구매자 이름', { exact: true }).fill('F2 비회원'); await p.getByLabel('전화번호', { exact: true }).fill(phone); });
        const code = await operation('purchase-helpers: request current phone challenge', async () => {
            await armIsolatedSource(p, '/api/guest/verifications');
            await p.getByRole('button', { name: '모의 인증번호 요청' }).click();
            await expect(p.getByLabel('인증번호', { exact: true })).toBeVisible();
            const host = process.env.FIREBASE_AUTH_EMULATOR_HOST, projectId = process.env.GCLOUD_PROJECT;
            if (!host || !projectId?.startsWith('demo-'))
                throw new Error('isolated emulator required');
            const codes = await (await boundedFetch(`http://${host}/emulator/v1/projects/${projectId}/verificationCodes`)).json() as {
                verificationCodes: {
                    phoneNumber: string;
                    code: string;
                }[];
            };
            const code = codes.verificationCodes.find(c => c.phoneNumber === phone)?.code;
            expect(code).toMatch(/^\d{6}$/);
            return code;
        });
        await prepare('purchase-helpers: challenge input', async () => { await p.getByLabel('인증번호', { exact: true }).fill(code!); });
        await operation('purchase-helpers: verify current phone challenge', async () => { await p.getByRole('button', { name: '인증 확인', exact: true }).click(); await expect(p.getByRole('heading', { name: '조회 비밀번호 설정' })).toBeVisible(); });
        await prepare('purchase-helpers: guest secret input', async () => {
            for (const name of ['조회 비밀번호', '조회 비밀번호 확인'])
                await p.getByLabel(name, { exact: true }).fill('f2 actual guest password');
        });
        await operation('purchase-helpers: prepare guest order credential', async () => { await p.getByRole('button', { name: '조회 비밀번호 준비', exact: true }).click(); await expect(p.getByText(/조회 비밀번호 준비 완료/)).toBeVisible(); });
        await operation('purchase-helpers: return to guest checkout quote', async () => { await p.getByRole('link', { name: '주문서 준비로 돌아가기' }).click(); await expect(p.getByTestId('quote-total')).toBeVisible(); });
    }
    else {
        await operation('purchase-helpers: open member checkout quote', async () => { await p.goto('/checkout'); await expect(p.getByTestId('quote-total')).toBeVisible(); });
    }
    if (options.coupons)
        for (const side of ['a', 'b'])
            await operation('purchase-helpers: select one store coupon', async () => { await p.getByLabel('적용 쿠폰 — ' + prefix + '-' + side, { exact: true }).selectOption({ label: '주문 할인 ' + side }); await expect(p.getByRole('button', { name: '견적 다시 계산', exact: true })).toBeEnabled(); });
    await prepare('purchase-helpers: order contact input', async () => {
        if (!guest) {
            await p.getByLabel('주문 구매자 이름', { exact: true }).fill('F2 구매자');
            await p.getByLabel('주문 구매자 전화번호', { exact: true }).fill('01012345678');
        }
        for (const [label, value] of [['수령인 이름', 'F2 수령인'], ['수령인 전화번호', '01087654321'], ['우편번호', '01234'], ['기본 주소', '서울시 배송 검증 주소 ' + '긴주소'.repeat(18)], ['상세 주소', '101호']])
            await p.getByLabel(label, { exact: true }).fill(value);
        await p.getByRole('checkbox', { name: '현재 상품·수량·견적과 배송 정보를 확인했습니다' }).check();
    });
    if (options.loseOrderResponse) {
        const bodies: unknown[] = [];
        let number = '';
        await prepare('purchase-helpers: lost-order transport', async () => {
            await p.route('**/api/orders', async (route) => {
                bodies.push(route.request().postDataJSON());
                const response = await route.fetch();
                expect(response.status()).toBe(200);
                const saved = await response.json();
                if (bodies.length === 1) {
                    number = saved.orderNumber;
                    await route.abort('failed');
                }
                else {
                    expect(saved.orderNumber).toBe(number);
                    await route.fulfill({ response });
                }
            });
        });
        return operation('purchase-helpers: create order and recover same request', async () => {
            await p.getByRole('button', { name: '주문 생성', exact: true }).dblclick();
            await expect(p.getByRole('button', { name: '같은 요청으로 주문 결과 확인' })).toBeVisible();
            expect(bodies).toHaveLength(1);
            await p.getByRole('button', { name: '같은 요청으로 주문 결과 확인' }).click();
            await expect(p.getByTestId('created-order-number')).toHaveText(number);
            expect(bodies).toHaveLength(2);
            expect(bodies[1]).toEqual(bodies[0]);
            await p.unroute('**/api/orders');
            await options.onCreated?.(number);
            return number;
        });
    }
    return operation('purchase-helpers: create order and receive durable number', async () => {
        const pending = p.waitForResponse(r => r.url().endsWith('/api/orders'));
        await p.getByRole('button', { name: '주문 생성', exact: true }).click();
        const response = await pending;
        expect(response.status()).toBe(200);
        const order = await response.json();
        expect(order.stores).toBe(2);
        await options.onCreated?.(order.orderNumber as string);
        return order.orderNumber as string;
    });
}
export async function approve(c: BrowserContext, n: string) { return operation("purchase-helpers: approve", async () => { expect((await c.request.post('/api/payments/' + n + '/approve', { headers: await headers(c), data: {} })).status()).toBe(200); }); }
export async function worker(c: BrowserContext, n: string, data: Record<string, string>) { return operation("purchase-helpers: worker", async () => { const r = await c.request.post('/api/__test/shipments/' + n + '/worker', { headers: await headers(c), data }); expect(r.status(), await r.text()).toBe(200); }); }
export async function read(c: BrowserContext, n: string) { return operation("purchase-helpers: read", async () => { const r = await c.request.get('/api/orders/' + n + '/shipments'); expect(r.status()).toBe(200); expect(r.headers()['cache-control']).toContain('no-store'); return await r.json() as Shipment[]; }); }
export async function verifyDatabase(c: BrowserContext, n: string) {
    return operation("purchase-helpers: verifyDatabase", async () => {
        const [api, r] = await Promise.all([read(c, n), c.request.get('/api/__test/shipments/' + n + '/database')]);
        expect(r.status()).toBe(200);
        const db = await r.json();
        expect(db.shipments).toHaveLength(api.length);
        for (const s of api) {
            expect(db.shipments.find((d: {
                id: string;
            }) => d.id === s.id)).toEqual({ id: s.id, seller_order_id: s.sellerOrderId, state: s.state, version: s.version });
            expect(db.items.filter((d: {
                shipment_id: string;
            }) => d.shipment_id === s.id).map((d: {
                order_item_id: string;
                quantity: number;
            }) => ({ orderItemId: d.order_item_id, quantity: d.quantity })).sort((a: {
                orderItemId: string;
            }, b: {
                orderItemId: string;
            }) => a.orderItemId.localeCompare(b.orderItemId))).toEqual([...s.items].sort((a, b) => a.orderItemId.localeCompare(b.orderItemId)));
            const events = db.events.filter((e: {
                shipment_id: string;
            }) => e.shipment_id === s.id);
            expect(events).toHaveLength(s.events.length);
            for (let i = 0; i < events.length; i++) {
                const e = events[i], a = s.events[i];
                expect({ id: e.id, source: e.source, actor: e.actor, previousState: e.previous_state, nextState: e.next_state, version: e.version, applied: e.applied }).toEqual({ id: a.id, source: a.source, actor: a.actor, previousState: a.previousState, nextState: a.nextState, version: a.version, applied: a.applied });
                expect(new Date(e.occurred_at).toISOString()).toBe(new Date(a.occurredAt).toISOString());
                expect(new Date(e.processed_at).toISOString()).toBe(new Date(a.processedAt).toISOString());
            }
        }
        return api;
    });
}
const labels: Record<string, string> = { PREPARING: '배송 준비', SHIPPED: '발송 완료', IN_TRANSIT: '배송 중', DELIVERED: '배송 완료' };
export async function verifyUI(p: Page, rows: Shipment[]) {
    return operation("purchase-helpers: verifyUI", async () => {
        for (const s of rows) {
            const panel = p.getByTestId('shipment-' + s.sellerOrderId);
            await expect(panel).toBeVisible();
            await expect(p.getByTestId('shipment-state-' + s.id)).toHaveText(labels[s.state]);
            for (const i of s.items)
                await expect(panel.getByTestId('shipment-item-' + i.orderItemId)).toContainText('수량 ' + i.quantity);
            for (const e of s.events) {
                const event = panel.getByTestId('shipment-event-' + e.id);
                await expect(event).toBeVisible();
                if (e.source === 'MOCK_PROVIDER')
                    await expect(event).toContainText('모의 배송 제공자');
                expect(await event.locator('time').evaluateAll(nodes => nodes.map(n => n.getAttribute('datetime')))).toEqual([e.occurredAt, e.processedAt]);
            }
        }
        expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await expect(p.getByRole('button', { name: /배송 완료|시간 진행|모의 이벤트/ })).toHaveCount(0);
    });
}
export async function sellerPage(p: Page, project: string, scenario: string, side: string, n: string) {
    await prepare("purchase-helpers: sellerPage [20]", async () => {
        await login(p, 'p4b-browse-order-' + scenario + '-' + project + '-' + side + '@example.com');
    });
    await operation("purchase-helpers: 판매자 메뉴 [21]", async () => {
        await p.goto('/seller');
        const nav = p.getByRole('navigation', { name: '판매자 메뉴' }).getByRole('link', { name: '판매 주문', exact: true });
        await nav.focus();
        await p.keyboard.press('Enter');
        await expect(p.getByRole('heading', { name: '판매 주문·모의 배송', exact: true })).toBeVisible();
        const link = p.getByRole('link', { name: n, exact: true });
        await link.focus();
    });
    await operation("purchase-helpers: sellerPage [22]", async () => {
        await p.keyboard.press('Enter');
        await expect(p.getByTestId('seller-order-number')).toHaveText(n);
    });
}
