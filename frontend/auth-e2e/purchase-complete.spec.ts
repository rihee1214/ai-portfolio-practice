import { operation, prepare, cleanup } from './business-deadline';
import { test, expect, type BrowserContext } from './business-deadline';
import { create, approve, headers, login, worker, verifyDatabase, verifyUI, sellerPage } from './purchase-helpers';
import { armIsolatedSource, isolateLoginSource, isolatePhoneSource } from './guest-login-source';
type Detail = {
    total: string;
    stores: {
        sellerOrderId: string;
        storeId: string;
        subtotal: string;
        productDiscount: string;
        couponDiscount: string;
        shippingBase: string;
        shippingDiscount: string;
        total: string;
        items: {
            id: string;
            title: string;
            quantity: number;
            gross: string;
            promotionDiscount: string;
            couponDiscount: string;
            net: string;
            units: {
                gross: string;
                promotionDiscount: string;
                couponDiscount: string;
                net: string;
            }[];
        }[];
    }[];
};
async function detail(c: BrowserContext, n: string) { return operation("purchase-complete: detail", async () => { const r = await c.request.get('/api/orders/' + n); expect(r.status()).toBe(200); expect(r.headers()['cache-control']).toContain('no-store'); return await r.json() as Detail; }); }
async function database(c: BrowserContext, n: string) { return operation("purchase-complete: database", async () => { const r = await c.request.get('/api/__test/purchase/' + n + '/database'); expect(r.status(), await r.text()).toBe(200); return await r.json(); }); }
function totals(d: Detail, want: number) {
    let parent = 0;
    expect(d.stores).toHaveLength(2);
    for (const s of d.stores) {
        let gross = 0, promotion = 0, coupon = 0, net = 0;
        for (const i of s.items) {
            const sums = { gross: 0, promotionDiscount: 0, couponDiscount: 0, net: 0 };
            expect(i.units).toHaveLength(i.quantity);
            for (const u of i.units) {
                expect(Number(u.net)).toBe(Number(u.gross) - Number(u.promotionDiscount) - Number(u.couponDiscount));
                for (const k of ['gross', 'promotionDiscount', 'couponDiscount', 'net'] as const)
                    sums[k] += Number(u[k]);
            }
            for (const k of ['gross', 'promotionDiscount', 'couponDiscount', 'net'] as const)
                expect(sums[k]).toBe(Number(i[k]));
            gross += sums.gross;
            promotion += sums.promotionDiscount;
            coupon += sums.couponDiscount;
            net += sums.net;
        }
        expect(gross).toBe(Number(s.subtotal));
        expect(promotion).toBe(Number(s.productDiscount));
        expect(coupon).toBe(Number(s.couponDiscount));
        expect(net + Number(s.shippingBase) - Number(s.shippingDiscount)).toBe(Number(s.total));
        parent += Number(s.total);
    }
    expect(parent).toBe(want);
    expect(Number(d.total)).toBe(want);
}
async function reserved(c: BrowserContext, n: string, state: string, coupons: number) {
    return operation("purchase-complete: reserved", async () => {
        const db = await database(c, n);
        expect(db.inventory).toHaveLength(2);
        expect(db.inventory.every((r: {
            status: string;
            quantity: number;
        }) => r.status === state && r.quantity === 1)).toBe(true);
        expect(db.coupons).toHaveLength(coupons);
        expect(db.coupons.every((r: {
            state: string;
        }) => r.state === state)).toBe(true);
        return db;
    });
}
test('memberMultiStorePurchase', async ({ page, context, browser }, info) => {
    const n = await create(page, info.project.name, 'gmember', false, '', { coupons: true, loseOrderResponse: true,
        onCreated: async n => {
            await operation('purchase-complete: created order retains original money and two reservations', async () => {
                totals(await detail(context, n), 35800);
                await reserved(context, n, 'RESERVED', 2);
            });
        },
    });
    await operation("purchase-complete: approval replay through committed reservations and one financial effect", async () => {
        await operation("purchase-complete: 결제로 이동 [1]", async () => {
            await page.getByRole('link', { name: '결제로 이동' }).click();
            await page.getByRole('button', { name: '모의 결제 승인', exact: true }).click();
            await expect(page.getByRole('heading', { name: '결제가 완료되었습니다' })).toBeVisible();
        });
        await operation("purchase-complete: /api/account/orders [2]", async () => {
            await approve(context, n);
            const db = await reserved(context, n, 'COMMITTED', 2);
            expect(db.order.state).toBe('PAID');
            expect(db.attempts).toEqual([expect.objectContaining({ approval_calls: 1, approval_effects: 1, success_events: 1 })]);
            expect((await (await context.request.get('/api/account/orders')).json()).orders).toHaveLength(1);
        });
    });
    await operation("purchase-complete: memberMultiStorePurchase [3]", async () => {
        await worker(context, n, { action: 'prepare' });
        const rows = await verifyDatabase(context, n);
        expect(rows).toHaveLength(2);
    });
    const { seller } = await prepare("purchase-complete: memberMultiStorePurchase [4]", async () => {
        const seller = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { seller };
    });
    try {
        const { p } = await prepare("purchase-complete: memberMultiStorePurchase [5]", async () => {
            const p = await seller.newPage();
            return { p };
        });
        await sellerPage(p, info.project.name, 'gmember', 'a', n);
        await operation("purchase-complete: 원 항목과 수량을 확인했습니다 [6]", async () => {
            await p.getByRole('checkbox', { name: '원 항목과 수량을 확인했습니다' }).check();
            await p.getByRole('button', { name: '발송 처리', exact: true }).click();
            await expect(p.getByText('발송 완료', { exact: true }).first()).toBeVisible();
            const current = await verifyDatabase(context, n);
            expect(current.map(s => s.state).sort()).toEqual(['PREPARING', 'SHIPPED']);
            await page.goto('/account/orders/' + n);
            await verifyUI(page, current);
            totals(await detail(context, n), 35800);
        });
    }
    finally {
        await cleanup("purchase-complete: memberMultiStorePurchase [7]", async () => {
            await seller.close();
        });
    }
});
test('guestPurchaseAndNewBrowserLookup', async ({ page, context, browser }, info) => {
    const phone = info.project.name === 'desktop' ? '+821072000001' : '+821072000002', prefix = 'p4b-browse-order-gguest-' + info.project.name;
    const lines: {
        skuId: string;
        quantity: number;
    }[] = [];
    for (const side of ['a', 'b']) {
        await operation("purchase-complete: /api/browse/products/ [10]", async () => {
            const p = await (await context.request.get('/api/browse/products/' + prefix + '-' + side)).json();
            lines.push({ skuId: p.offers[0].id, quantity: 1 });
        });
    }
    const { q } = await operation("purchase-complete: /api/quotes [11] + final assertions", async () => {
        const { quote } = await operation("purchase-complete: /api/quotes [11]", async () => {
            const quote = await context.request.post('/api/quotes', { headers: await headers(context), data: { lines, coupons: [] } });
            expect(quote.status()).toBe(200);
            return { quote };
        });
        const { q } = await operation("purchase-complete: guestPurchaseAndNewBrowserLookup [12]", async () => {
            const q = await quote.json();
            expect(q.total).toBe('34000');
            expect(q.stores.every((s: {
                productDiscount: string;
            }) => s.productDiscount === '1000')).toBe(true);
            return { q };
        });
        return { q };
    });
    await operation("purchase-complete: /api/quotes [13]", async () => {
        expect((await context.request.post('/api/quotes', { headers: await headers(context), data: { lines, coupons: [{ storeId: q.stores[0].storeId, couponId: crypto.randomUUID() }] } })).status()).toBe(401);
    });
    await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [14]", async () => {
        await isolatePhoneSource(page);
    });
    const n = await create(page, info.project.name, 'gguest', true, phone);
    await operation("purchase-complete: guestPurchaseAndNewBrowserLookup [15]", async () => {
        await approve(context, n);
    });
    const { fresh, member } = await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [16]", async () => {
        const fresh = await browser.newContext({ baseURL: new URL(page.url()).origin }), member = await browser.newContext({ baseURL: new URL(page.url()).origin });
        return { fresh, member };
    });
    try {
        const { p } = await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [17]", async () => {
            const p = await fresh.newPage();
            return { p };
        });
        await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [18]", async () => {
            await isolateLoginSource(p, '127.0.0.4');
        });
        await operation("purchase-complete: /api/orders/ [19]", async () => {
            expect((await fresh.request.get('/api/orders/' + n)).status()).toBe(404);
        });
        await operation("purchase-complete: 주문 조회 인증 [20]", async () => {
            await armIsolatedSource(p, '/api/guest/access/login');
            await p.goto('/guest/orders/login');
            for (const [label, value] of [['주문번호', n], ['등록 전화번호', phone], ['조회 비밀번호', 'f2 actual guest password']]) {
                await operation("purchase-complete: guestPurchaseAndNewBrowserLookup [21]", async () => {
                    await p.getByLabel(label, { exact: true }).fill(value);
                });
            }
            await p.getByLabel('조회 비밀번호', { exact: true }).fill('wrong G lookup password');
            const denied = p.waitForResponse('**/api/guest/access/login');
            await p.getByRole('button', { name: '주문 조회 인증', exact: true }).click();
            expect((await denied).status()).toBe(401);
            expect((await fresh.request.get('/api/orders/' + n)).status()).toBe(404);
        });
        await operation("purchase-complete: 주문 조회 인증 [22]", async () => {
            await armIsolatedSource(p, '/api/guest/access/login');
            await p.getByLabel('조회 비밀번호', { exact: true }).fill('f2 actual guest password');
            const response = p.waitForResponse('**/api/guest/access/login');
            await p.getByRole('button', { name: '주문 조회 인증', exact: true }).click();
            expect((await response).status()).toBe(200);
        });
        await operation("purchase-complete: 실제 주문 이력 [23]", async () => {
            await p.getByRole('region', { name: '실제 주문 이력', exact: true }).getByRole('link', { name: n, exact: true }).click();
            await expect(p.getByTestId('order-number')).toHaveText(n);
            totals(await detail(fresh, n), 34000);
            await reserved(fresh, n, 'COMMITTED', 0);
        });
        await operation("purchase-complete: guestPurchaseAndNewBrowserLookup [24]", async () => {
            await worker(fresh, n, { action: 'prepare' });
            await p.reload();
            await verifyUI(p, await verifyDatabase(fresh, n));
        });
        await operation("purchase-complete: /api/payments/ [25]", async () => {
            expect((await fresh.request.post('/api/payments/' + n + '/approve', { headers: await headers(fresh), data: {} })).status()).toBe(401);
        });
        const { mp } = await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [26]", async () => {
            const mp = await member.newPage();
            return { mp };
        });
        await prepare("purchase-complete: guestPurchaseAndNewBrowserLookup [27]", async () => {
            await login(mp, 'order-gmember-' + info.project.name + '@example.com');
        });
        await operation("purchase-complete: /api/quotes [28]", async () => {
            const mq = await (await member.request.post('/api/quotes', { headers: await headers(member), data: { lines, coupons: [] } })).json();
            expect(mq.total).toBe(q.total);
            expect(mq.stores.map((s: {
                productDiscount: string;
            }) => s.productDiscount)).toEqual(q.stores.map((s: {
                productDiscount: string;
            }) => s.productDiscount));
            expect((await member.request.get('/api/orders/' + n)).status()).toBe(404);
            const foreign = (await (await member.request.get('/api/account/orders')).json()).orders[0].orderNumber;
            const hidden = await fresh.request.get('/api/orders/' + foreign);
            expect(hidden.status()).toBe(404);
            expect(hidden.headers()['cache-control']).toContain('no-store');
            expect(await hidden.text()).not.toContain('F2 수령인');
        });
    }
    finally {
        await cleanup("purchase-complete: guestPurchaseAndNewBrowserLookup [29]", async () => {
            await fresh.close();
        });
        await cleanup("purchase-complete: guestPurchaseAndNewBrowserLookup [30]", async () => {
            await member.close();
        });
    }
});
test('uncertainFailureAndZeroPayment', async ({ page, context }, info) => {
    for (const mode of ['unknown', 'failed', 'lost', 'zero']) {
        await prepare("purchase-complete: uncertainFailureAndZeroPayment [31]", async () => {
            await login(page, 'p4b-browse-reserve-order-d-g-browser-' + info.project.name + '-' + mode + '@example.com');
        });
        await operation("purchase-complete: 모의 결제 승인 [32]", async () => {
            const n = (await (await context.request.get('/api/account/orders')).json()).orders[0].orderNumber;
            await page.goto('/payments/' + n);
            await reserved(context, n, 'RESERVED', 2);
            await page.getByRole('button', { name: '모의 결제 승인', exact: true }).click();
            if (mode === 'unknown' || mode === 'lost') {
                await operation("purchase-complete: uncertainFailureAndZeroPayment [33]", async () => {
                    await expect(page.getByText('결제 확인 중입니다. 예약을 유지하고 서버 결과를 확인합니다.', { exact: true })).toBeVisible();
                    await reserved(context, n, 'RESERVED', 2);
                });
                let { db } = await operation("purchase-complete: uncertainFailureAndZeroPayment [34]", async () => {
                    await approve(context, n);
                    const db = await database(context, n);
                    expect(db.attempts[0].approval_calls).toBe(1);
                    expect(db.order.state).toBe('PENDING_PAYMENT');
                    return { db };
                });
                await operation("purchase-complete: 결제가 완료되었습니다 [35]", async () => {
                    expect((await context.request.post('/api/__test/payment/query', { headers: await headers(context), data: { number: n } })).status()).toBe(200);
                    if (mode === 'unknown') {
                        await operation("purchase-complete: uncertainFailureAndZeroPayment [36]", async () => {
                            await reserved(context, n, 'RESERVED', 2);
                            expect((await database(context, n)).order.state).toBe('PENDING_PAYMENT');
                        });
                    }
                    else {
                        await operation("purchase-complete: 결제가 완료되었습니다 [37]", async () => {
                            await expect(page.getByRole('heading', { name: '결제가 완료되었습니다' })).toBeVisible();
                            db = await reserved(context, n, 'COMMITTED', 2);
                            expect(db.attempts[0]).toMatchObject({ approval_calls: 1, approval_effects: 1, success_events: 1 });
                        });
                    }
                });
            }
            else if (mode === 'failed') {
                await operation("purchase-complete: uncertainFailureAndZeroPayment [38]", async () => {
                    await expect(page.getByText('결제가 완료되지 않아 주문이 종료되었습니다.', { exact: true })).toBeVisible();
                    const db = await reserved(context, n, 'RELEASED', 2);
                    expect(db.order.state).toBe('CANCELED');
                    expect(db.attempts[0]).toMatchObject({ approval_calls: 1, approval_effects: 0, success_events: 0 });
                });
            }
            else {
                await operation("purchase-complete: 결제가 완료되었습니다 [39]", async () => {
                    await expect(page.getByRole('heading', { name: '결제가 완료되었습니다' })).toBeVisible();
                });
                await operation("purchase-complete: uncertainFailureAndZeroPayment [40]", async () => {
                    await approve(context, n);
                    const db = await reserved(context, n, 'COMMITTED', 2);
                    expect(db.order.amount).toBe(0);
                    expect(db.attempts[0]).toMatchObject({ approval_calls: 0, approval_effects: 0, success_events: 1 });
                });
            }
        });
    }
});
test('historyAndSellerIsolation', async ({ page, context, browser }, info) => {
    const n = await create(page, info.project.name, 'ghistory', false, '', { coupons: true });
    await operation("purchase-complete: historyAndSellerIsolation [41]", async () => {
        await approve(context, n);
    });
    const { before, sellerContexts } = await operation("purchase-complete: historyAndSellerIsolation [42]", async () => {
        await worker(context, n, { action: 'prepare' });
        const before = await detail(context, n), sellerContexts: BrowserContext[] = [];
        return { before, sellerContexts };
    });
    try {
        for (const side of ['a', 'b']) {
            const { seller } = await prepare("purchase-complete: historyAndSellerIsolation [43]", async () => {
                const seller = await browser.newContext({ baseURL: new URL(page.url()).origin });
                return { seller };
            });
            sellerContexts.push(seller);
            const { p } = await prepare("purchase-complete: historyAndSellerIsolation [44]", async () => {
                const p = await seller.newPage();
                return { p };
            });
            await sellerPage(p, info.project.name, 'ghistory', side, n);
            const { id } = await operation("purchase-complete: /api/seller/orders/ [45]", async () => {
                const id = p.url().split('/').at(-1)!, other = before.stores.find(s => s.sellerOrderId !== id)!;
                expect((await seller.request.get('/api/seller/orders/' + other.sellerOrderId)).status()).toBe(404);
                const projection = await (await seller.request.get('/api/seller/orders/' + id)).json();
                expect(Object.keys(projection).sort()).toEqual(['items', 'orderNumber', 'orderState', 'recipient', 'sellerOrderId', 'total']);
                expect(projection.items.map((i: {
                    id: string;
                }) => i.id)).toEqual(before.stores.find(s => s.sellerOrderId === id)!.items.map(i => i.id));
                return { id };
            });
            const { row } = await operation("purchase-complete: 원 항목과 수량을 확인했습니다 [46]", async () => {
                await p.getByRole('checkbox', { name: '원 항목과 수량을 확인했습니다' }).check();
                await p.getByRole('button', { name: '발송 처리', exact: true }).click();
                await expect(p.getByText('발송 완료', { exact: true }).first()).toBeVisible();
                const row = (await verifyDatabase(context, n)).find(s => s.sellerOrderId === id)!;
                return { row };
            });
            await operation("purchase-complete: historyAndSellerIsolation [47]", async () => {
                await worker(context, n, { action: 'progress', shipment: row.id, target: 'IN_TRANSIT' });
            });
            const { product } = await operation("purchase-complete: /api/seller/products [48]", async () => {
                if (side === 'a')
                    await worker(context, n, { action: 'progress', shipment: row.id, target: 'DELIVERED' });
                const product = (await (await seller.request.get('/api/seller/products')).json()).items[0];
                return { product };
            });
            const { sku } = await operation("purchase-complete: /api/seller/products/ [49]", async () => {
                expect((await seller.request.put('/api/seller/products/' + product.id, { headers: await headers(seller), data: { categoryId: product.categoryId, slug: product.slug, title: 'G 변경 후 상품', description: product.description, state: product.state, version: product.version } })).status()).toBe(200);
                const sku = (await (await seller.request.get('/api/seller/products/' + product.id + '/skus')).json()).items[0];
                return { sku };
            });
            await operation("purchase-complete: /api/seller/products/ [50]", async () => {
                expect((await seller.request.put('/api/seller/products/' + product.id + '/skus/' + sku.id, { headers: await headers(seller), data: { ...sku, price: 7 } })).status()).toBe(200);
            });
            const { coupon } = await operation("purchase-complete: /api/seller/promotions [51]", async () => {
                for (const promo of (await (await seller.request.get('/api/seller/promotions')).json()).items) {
                    await operation("purchase-complete: /api/seller/promotions/ [52]", async () => {
                        expect((await seller.request.put('/api/seller/promotions/' + promo.id, { headers: await headers(seller), data: { ...promo, title: 'G 변경 후 행사', state: 'STOPPED' } })).status()).toBe(200);
                    });
                }
                const coupon = (await (await seller.request.get('/api/seller/coupons')).json()).items[0];
                return { coupon };
            });
            await operation("purchase-complete: /api/seller/coupons/ [53]", async () => {
                expect((await seller.request.put('/api/seller/coupons/' + coupon.id, { headers: await headers(seller), data: { ...coupon, ...coupon.terms, title: 'G 변경 후 쿠폰', state: 'STOPPED' } })).status()).toBe(200);
            });
        }
        const { rows } = await operation("purchase-complete: historyAndSellerIsolation [54]", async () => {
            expect(await detail(context, n)).toEqual(before);
            const rows = await verifyDatabase(context, n);
            expect(rows.map(s => s.state).sort()).toEqual(['DELIVERED', 'IN_TRANSIT']);
            return { rows };
        });
        await operation("purchase-complete: historyAndSellerIsolation [55]", async () => {
            await page.goto('/account/orders/' + n);
            await verifyUI(page, rows);
        });
        for (const s of before.stores) {
            await operation("purchase-complete: historyAndSellerIsolation [56]", async () => {
                for (const i of s.items) {
                    await operation("purchase-complete: historyAndSellerIsolation [57]", async () => {
                        await expect(page.getByTestId('seller-order-' + s.sellerOrderId).getByRole('heading', { name: i.title, exact: true })).toBeVisible();
                    });
                }
            });
        }
        await operation("purchase-complete: historyAndSellerIsolation [58]", async () => {
            await page.reload();
            await verifyUI(page, rows);
        });
    }
    finally {
        for (const c of sellerContexts) {
            await operation("purchase-complete: historyAndSellerIsolation [59]", async () => {
                await c.close();
            });
        }
    }
});
