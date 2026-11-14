import { bindDeadline, currentDeadline, operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Browser, type BrowserContext, type Page } from './business-deadline';
import { headers, worker, read, type Shipment } from './purchase-helpers';
import { request as rawRequest } from 'node:http';
type Unit = {
    itemId: string;
    ordinal: number;
};
type View = {
    revision: number;
    orderState: string;
    units: {
        unit: Unit;
        lifecycle: string;
        eligible: boolean;
        deliveredAt: string | null;
        dueAt: string | null;
    }[];
};
async function isolatedGuestLogin(context: BrowserContext, body: {
    orderNumber: string;
    phone: string;
    password: string;
}) {
    return operation("order-confirmation: isolatedGuestLogin", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('isolated API port required');
        const call = (path: string, method: string, data?: unknown, cookies: string[] = [], token?: string) => new Promise<{
            status: number;
            data: Record<string, unknown>;
            cookies: string[];
        }>((resolve, reject) => {
            const transportTimeout = currentDeadline().remaining(10000);
            const text = data === undefined ? undefined : JSON.stringify(data), r = rawRequest({ hostname: '127.0.0.1', port, path, method, localAddress: '127.0.0.3', headers: { Accept: 'application/json', ...(text === undefined ? {} : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) }), ...(cookies.length ? { Cookie: cookies.map(c => c.split(';')[0]).join('; ') } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}) } }, response => {
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
            r.setTimeout(10000, () => r.destroy(new Error('isolated guest HTTP timeout')));
            r.end(text);
        });
        const csrf = await call('/api/auth/csrf', 'GET');
        expect(csrf.status).toBe(200);
        expect(csrf.data.headerName).toBe('X-CSRF-TOKEN');
        expect(typeof csrf.data.token).toBe('string');
        const login = await call('/api/guest/access/login', 'POST', body, csrf.cookies, String(csrf.data.token));
        expect(login.status).toBe(200);
        expect(login.data.status).toBe('AUTHENTICATED');
        expect(login.cookies.some(c => c.startsWith('GUEST_ACCESS='))).toBe(true);
        await context.addCookies([...csrf.cookies, ...login.cookies].map(raw => { const [pair] = raw.split(';'), [name, ...value] = pair.split('='); return { name, value: value.join('='), domain: '127.0.0.1', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' as const }; }));
    });
}
async function current(c: BrowserContext, n: string) { return operation("order-confirmation: current", async () => { const r = await c.request.get('/api/orders/' + n + '/confirmations'); expect(r.status()).toBe(200); expect(r.headers()['cache-control']).toContain('no-store'); return await r.json() as View; }); }
async function database(c: BrowserContext, n: string) { return operation("order-confirmation: database", async () => { const r = await c.request.get('/api/__test/confirmations/' + n + '/database'); expect(r.status()).toBe(200); return await r.json(); }); }
async function dispatch(c: BrowserContext, seller: BrowserContext, n: string, id: string) { return operation("order-confirmation: dispatch", async () => { const row = (await read(c, n)).find(s => s.id === id)!; expect((await seller.request.post('/api/seller/shipments/' + id + '/ship', { headers: await headers(seller), data: { key: 'confirm-ship-' + id, version: row.version, items: row.items } })).status()).toBe(200); await worker(c, n, { action: 'progress', shipment: id, target: 'IN_TRANSIT' }); await worker(c, n, { action: 'progress', shipment: id, target: 'DELIVERED' }); }); }
async function quickLogin(c: BrowserContext, email: string) { return operation("order-confirmation: quickLogin", async () => { expect((await c.request.post('/api/auth/login', { headers: await headers(c), form: { email, password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200); }); }
async function seeded(c: BrowserContext, project: string, scenario: string) {
    await prepare("order-confirmation: seeded [1]", async () => {
        await quickLogin(c, 'order-' + scenario + '-' + project + '@example.com');
    });
    return prepare("order-confirmation: /api/account/orders [2]", async () => {
        const rows = (await (await c.request.get('/api/account/orders')).json()).orders;
        expect(rows).toHaveLength(1);
        return rows[0].orderNumber as string;
    });
}
async function splitAndDeliverTwo(c: BrowserContext, browser: Browser, project: string, scenario: string, n: string) {
    const { seller } = await prepare("order-confirmation: splitAndDeliverTwo [3]", async () => {
        const seller = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { seller };
    });
    await prepare("order-confirmation: splitAndDeliverTwo [4]", async () => {
        await quickLogin(seller, 'p4b-browse-order-' + scenario + '-' + project + '-a@example.com');
    });
    return prepare("order-confirmation: /api/seller/orders [5]", async () => {
        // Fixture readiness uses this local reader; shared command/result reads keep their operation deadline.
        const read = async (c: BrowserContext, n: string) => {
            const r = await c.request.get('/api/orders/' + n + '/shipments');
            expect(r.status()).toBe(200);
            expect(r.headers()['cache-control']).toContain('no-store');
            return await r.json() as Shipment[];
        };
        const rows = (await (await seller.request.get('/api/seller/orders')).json()).orders, sellerId = rows.find((o: {
            orderNumber: string;
        }) => o.orderNumber === n).sellerOrderId as string, shipments = await read(c, n), source = shipments.find(s => s.sellerOrderId === sellerId && s.state === 'PREPARING')!, target = shipments.find(s => s.sellerOrderId === sellerId && s.state === 'DELIVERED')!;
        expect(source.items[0].quantity).toBe(3);
        expect(target.items[0].quantity).toBe(2);
        return { seller, sellerId, source, target: target.id };
    });
}
async function choose(page: Page) {
    return operation("order-confirmation: choose", async () => {
        const region = page.getByRole('region', { name: '구매 확정', exact: true });
        for (const box of await region.locator('li input[type=checkbox]').all())
            await box.check();
        await region.getByRole('checkbox', { name: '선택한 수량을 구매 확정합니다' }).check();
        return region;
    });
}
async function finish(c: BrowserContext, browser: Browser, page: Page, project: string, scenario: string, n: string, a: {
    seller: BrowserContext;
    sellerId: string;
}, final?: () => Promise<void>) {
    await operation("order-confirmation: finish [6]", async () => {
        for (const row of (await read(c, n)).filter(s => s.sellerOrderId === a.sellerId && s.state === 'PREPARING')) {
            await operation("order-confirmation: finish [7]", async () => {
                await dispatch(c, a.seller, n, row.id);
            });
        }
    });
    const { b } = await prepare("order-confirmation: finish [8]", async () => {
        const b = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { b };
    });
    try {
        await prepare("order-confirmation: finish [9]", async () => {
            await quickLogin(b, 'p4b-browse-order-' + scenario + '-' + project + '-b@example.com');
        });
        await operation("order-confirmation: /api/seller/orders [10]", async () => {
            const sellerId = (await (await b.request.get('/api/seller/orders')).json()).orders.find((o: {
                orderNumber: string;
            }) => o.orderNumber === n).sellerOrderId as string;
            for (const row of (await read(c, n)).filter(s => s.sellerOrderId === sellerId && s.state === 'PREPARING')) {
                await operation("order-confirmation: finish [11]", async () => {
                    await dispatch(c, b, n, row.id);
                });
            }
        });
    }
    finally {
        await cleanup("order-confirmation: finish [12]", async () => {
            await b.close();
        });
    }
    const { region } = await operation("order-confirmation: 구매 확정 다시 조회 [13]", async () => {
        await page.getByRole('button', { name: '구매 확정 다시 조회', exact: true }).click();
        await expect(page.getByRole('region', { name: '구매 확정', exact: true }).locator('li input[type=checkbox]')).toHaveCount(4);
        const region = await choose(page);
        return { region };
    });
    await operation("order-confirmation: 선택 수량 구매 확정 [14]", async () => {
        await region.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).click();
        await expect(page.getByRole('heading', { name: '주문 종료', exact: true })).toBeVisible();
        const db = await database(c, n);
        expect(db.order.state).toBe('CLOSED');
        expect(db.closedEvents).toHaveLength(1);
        expect(db.order_unit_lifecycle).toHaveLength(6);
        expect(db.order_unit_lifecycle.every((u: {
            state: string;
        }) => u.state === 'CONFIRMED')).toBe(true);
        expect(db.order_confirmation).toHaveLength(2);
        await final?.();
    });
}
test('memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase', async ({ page, context, browser }, info) => {
    const scenario = 'confirmationmember', n = await seeded(context, info.project.name, scenario);
    const a = await splitAndDeliverTwo(context, browser, info.project.name, scenario, n);
    try {
        const { original, first, bodies } = await operation("order-confirmation: 구매 확정 [16]", async () => {
            const original = await (await context.request.get('/api/orders/' + n)).json();
            await page.goto('/account/orders/' + n);
            await expect(page.getByRole('region', { name: '구매 확정', exact: true }).locator('li input[type=checkbox]')).toHaveCount(2);
            const first = await current(context, n);
            expect(first.units.filter(e => e.eligible)).toHaveLength(2);
            expect(first.units.filter(e => e.deliveredAt === null)).toHaveLength(4);
            const bodies: unknown[] = [];
            return { original, first, bodies };
        });
        await prepare("order-confirmation: memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase [17]", async () => {
            await page.route('**/api/orders/' + n + '/confirm', async (route) => {
                bodies.push(route.request().postDataJSON());
                const r = await route.fetch();
                expect(r.status()).toBe(200);
                if (bodies.length === 1)
                    await route.abort('failed');
                else
                    await route.fulfill({ response: r });
            });
        });
        await operation("order-confirmation: 선택 수량 구매 확정 [18] + recovery", async () => {
            await operation("order-confirmation: 선택 수량 구매 확정 [18]", async () => {
                const region = await choose(page);
                await region.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).dblclick();
                await expect(page.getByRole('button', { name: '같은 요청으로 구매 확정 결과 확인', exact: true })).toBeVisible();
                expect(bodies).toHaveLength(1);
                const replay = page.waitForResponse(r => r.url().endsWith('/confirm'));
                await page.getByRole('button', { name: '같은 요청으로 구매 확정 결과 확인', exact: true }).click();
                expect((await replay).status()).toBe(200);
                expect(bodies).toHaveLength(2);
                expect(bodies[1]).toEqual(bodies[0]);
                await expect(region.locator('li input[type=checkbox]')).toHaveCount(0);
            });
            await operation("order-confirmation: memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase [19]", async () => {
                await page.unroute('**/api/orders/' + n + '/confirm');
            });
            await operation("order-confirmation: memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase [20]", async () => {
                const db = await database(context, n);
                expect(db.order.state).toBe('PAID');
                expect(db.order_unit_lifecycle.filter((u: {
                    state: string;
                }) => u.state === 'CONFIRMED')).toHaveLength(2);
                expect(db.order_unit_lifecycle.filter((u: {
                    state: string;
                }) => u.state === 'OPEN')).toHaveLength(4);
                const expected = first.units.filter(e => e.eligible).map(e => e.unit.itemId + ':' + e.unit.ordinal).sort();
                expect(db.order_unit_lifecycle.filter((u: {
                    state: string;
                }) => u.state === 'CONFIRMED').map((u: {
                    item_id: string;
                    ordinal: number;
                }) => u.item_id + ':' + u.ordinal).sort()).toEqual(expected);
            });
        });
        await finish(context, browser, page, info.project.name, scenario, n, a, async () => {
            await operation('order-confirmation: final confirmed order keeps original store money and policy', async () => {
                const saved = await (await context.request.get('/api/orders/' + n)).json();
                expect(saved.stores).toEqual(original.stores);
                expect(saved.total).toBe(original.total);
                expect(saved.policyVersion).toBe(original.policyVersion);
            });
        });
        for (const width of [375, 768, 1440]) {
            await operation("order-confirmation: memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase [22]", async () => {
                await page.setViewportSize({ width, height: 900 });
                expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
            });
        }
    }
    finally {
        await cleanup("order-confirmation: memberSelectedTwoUnitsReplayAndCompleteAllSellersMatchApiAndDatabase [23]", async () => {
            await a.seller.close();
        });
    }
});
test('newGuestLookupConfirmsOnlyDeliveredTwoThenClosesWithPurchaseAuthoritySeparated', async ({ browser }, info) => {
    const { scenario, fresh } = await prepare("order-confirmation: newGuestLookupConfirmsOnlyDeliveredTwoThenClosesWithPurchaseAuthoritySeparated [24]", async () => {
        const scenario = 'confirmationguest', fresh = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { scenario, fresh };
    });
    try {
        const seed = await prepare('order-confirmation: preexisting guest confirmation identity', async () => {
            const response = await fresh.request.get('/api/__test/confirmations/guest-seed/' + info.project.name);
            expect(response.status()).toBe(200);
            return await response.json() as { orderNumber: string; phone: string };
        });
        const { n } = await operation("order-confirmation: /api/__test/confirmations/guest-seed/ [25]", async () => {
            const n = seed.orderNumber as string;
            expect((await fresh.request.get('/api/orders/' + n + '/confirmations')).status()).toBe(404);
            await isolatedGuestLogin(fresh, { orderNumber: n, phone: seed.phone, password: 'f2 actual guest password' });
            return { n };
        });
        const { p, a } = await prepare("order-confirmation: newGuestLookupConfirmsOnlyDeliveredTwoThenClosesWithPurchaseAuthoritySeparated [26]", async () => {
            const p = await fresh.newPage(), a = await splitAndDeliverTwo(fresh, browser, info.project.name, scenario, n);
            return { p, a };
        });
        try {
            const { region } = await operation("order-confirmation: 구매 확정 [27]", async () => {
                await p.goto('/guest/orders/' + n);
                await expect(p.getByRole('region', { name: '구매 확정', exact: true }).locator('li input[type=checkbox]')).toHaveCount(2);
                const region = await choose(p);
                return { region };
            });
            await operation("order-confirmation: 선택 수량 구매 확정 [28]", async () => {
                await region.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).click();
                await expect(region.locator('li input[type=checkbox]')).toHaveCount(0);
                const db = await database(fresh, n);
                expect(db.order.state).toBe('PAID');
                expect(db.order_unit_lifecycle.filter((u: {
                    state: string;
                }) => u.state === 'CONFIRMED')).toHaveLength(2);
                expect(db.order_unit_lifecycle.filter((u: {
                    state: string;
                }) => u.state === 'OPEN')).toHaveLength(4);
            });
            await operation("order-confirmation: /api/payments/ [29]", async () => {
                expect((await fresh.request.post('/api/payments/' + n + '/approve', { headers: await headers(fresh), data: {} })).status()).toBe(401);
            });
            await finish(fresh, browser, p, info.project.name, scenario, n, a, async () => {
                await operation('order-confirmation: final guest confirmation survives order reload', async () => {
                    expect((await fresh.request.get('/api/payments/' + n + '/history')).status()).toBe(200);
                    await p.reload();
                    await expect(p.getByRole('heading', { name: '주문 종료', exact: true })).toBeVisible();
                });
            });
        }
        finally {
            await cleanup("order-confirmation: newGuestLookupConfirmsOnlyDeliveredTwoThenClosesWithPurchaseAuthoritySeparated [31]", async () => {
                await a.seller.close();
            });
        }
    }
    finally {
        await cleanup("order-confirmation: newGuestLookupConfirmsOnlyDeliveredTwoThenClosesWithPurchaseAuthoritySeparated [32]", async () => {
            await fresh.close();
        });
    }
});
test('confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery', async ({ page, context, browser }, info) => {
    const scenario = 'confirmationfailure', n = await seeded(context, info.project.name, scenario);
    const { a, guard } = await prepare("order-confirmation: confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery [34]", async () => {
        const a = await splitAndDeliverTwo(context, browser, info.project.name, scenario, n), guard = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { a, guard };
    });
    try {
        await prepare("order-confirmation: confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery [35]", async () => {
            await quickLogin(guard, 'order-' + scenario + '-' + info.project.name + '@example.com');
        });
        const { view } = await operation("order-confirmation: 구매 확정 [36]", async () => {
            await page.goto('/account/orders/' + n);
            await expect(page.getByRole('region', { name: '구매 확정', exact: true }).locator('li input[type=checkbox]')).toHaveCount(2);
            const view = await current(context, n);
            return { view };
        });
        await operation("order-confirmation: /api/orders/ [37]", async () => {
            expect((await context.request.post('/api/orders/' + n + '/confirm', { headers: await headers(context), data: { key: 'concurrent', expectedRevision: view.revision, units: [view.units.find(e => e.eligible)!.unit] } })).status()).toBe(200);
        });
        let fail = true, posts = 0;
        page.on('request', r => {
            if (r.method() === 'POST' && r.url().endsWith('/confirm'))
                posts++;
        });
        await prepare("order-confirmation: confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery [38]", async () => {
            await page.route('**/api/orders/' + n + '/confirmations', async (route) => {
                if (fail) {
                    fail = false;
                    await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' });
                }
                else
                    await route.continue();
            });
        });
        const { region, before } = await operation("order-confirmation: 선택 수량 구매 확정 [39]", async () => {
            const region = await choose(page);
            const rejected = page.waitForResponse(r => r.url().endsWith('/confirm'));
            await region.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).click();
            expect((await rejected).status()).toBe(409);
            await expect(region.getByRole('alert')).toContainText('현재 정보를 확인하지 못했습니다');
            await expect(region.locator('li')).toHaveCount(0);
            await expect(region.getByRole('button', { name: '같은 요청으로 구매 확정 결과 확인' })).toHaveCount(0);
            expect(posts).toBe(1);
            await region.getByRole('button', { name: '구매 확정 다시 조회', exact: true }).click();
            await expect(region.locator('li input[type=checkbox]')).toHaveCount(1);
            expect(posts).toBe(1);
            const before = await database(guard, n);
            return { region, before };
        });
        const { denied } = await operation("order-confirmation: /api/auth/logout [40]", async () => {
            await choose(page);
            const denied = page.waitForResponse(r => r.url().endsWith('/confirm'));
            return { denied };
        });
        await operation("order-confirmation: 선택 수량 구매 확정 [41]", async () => {
            await page.route('**/api/orders/' + n + '/confirm', bindDeadline(async (route) => { expect((await context.request.post('/api/auth/logout', { headers: await headers(context) })).status()).toBe(200); await route.continue(); }), { times: 1 });
            await region.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).click();
            expect([401, 403]).toContain((await denied).status());
            await expect(page.getByTestId('order-number')).toHaveCount(0);
            await expect(page.getByText('F2 수령인', { exact: false })).toHaveCount(0);
            expect(await database(guard, n)).toEqual(before);
            expect(posts).toBe(2);
            await page.reload();
            expect(posts).toBe(2);
        });
    }
    finally {
        await cleanup("order-confirmation: confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery [42]", async () => {
            await a.seller.close();
        });
        await cleanup("order-confirmation: confirmationConflictGetFailureAndLostAuthorityRequireExplicitRecovery [43]", async () => {
            await guard.close();
        });
    }
});
test('lateConfirmationReadAfterLogoutNeverPublishesPrivateOrderOrCommands', async ({ page, context, browser }, info) => {
    const scenario = 'confirmationlate', n = await seeded(context, info.project.name, scenario);
    const a = await splitAndDeliverTwo(context, browser, info.project.name, scenario, n);
    try {
        await operation("order-confirmation: 구매 확정 [45]", async () => {
            await page.goto('/account/orders/' + n);
            await expect(page.getByRole('region', { name: '구매 확정', exact: true }).locator('li input[type=checkbox]')).toHaveCount(2);
        });
        let release!: () => void, captured!: () => void;
        const held = new Promise<void>(r => release = r), ready = new Promise<void>(r => captured = r);
        await prepare("order-confirmation: lateConfirmationReadAfterLogoutNeverPublishesPrivateOrderOrCommands [46]", async () => {
            await page.route('**/api/orders/' + n + '/confirmations', async (route) => {
                const response = await route.fetch();
                captured();
                await held;
                try {
                    await route.fulfill({ response });
                }
                catch { /* auth generation may abort the read */ }
            });
        });
        await operation("order-confirmation: 구매 확정 다시 조회 [47] + recovery", async () => {
            await operation("order-confirmation: 구매 확정 다시 조회 [47]", async () => {
                await page.getByRole('button', { name: '구매 확정 다시 조회', exact: true }).click();
                await ready;
                expect((await context.request.post('/api/auth/logout', { headers: await headers(context) })).status()).toBe(200);
                await page.evaluate(() => window.dispatchEvent(new Event('focus')));
                release();
            });
            await operation("order-confirmation: lateConfirmationReadAfterLogoutNeverPublishesPrivateOrderOrCommands [48]", async () => {
                await page.unroute('**/api/orders/' + n + '/confirmations');
            });
            await operation("order-confirmation: 선택 수량 구매 확정 [49]", async () => {
                await expect(page.getByTestId('order-number')).toHaveCount(0);
                await expect(page.getByRole('button', { name: '선택 수량 구매 확정', exact: true })).toHaveCount(0);
                expect((await context.request.get('/api/orders/' + n + '/confirmations')).status()).toBe(404);
            });
        });
    }
    finally {
        await cleanup("order-confirmation: lateConfirmationReadAfterLogoutNeverPublishesPrivateOrderOrCommands [50]", async () => {
            await a.seller.close();
        });
    }
});
