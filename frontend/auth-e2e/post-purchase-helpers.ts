import { currentDeadline, operation, prepare } from './business-deadline';
import { expect, type BrowserContext, type Page, type TestInfo } from './business-deadline';
import { request as rawRequest } from 'node:http';
import { headers } from './purchase-helpers';
import { armIsolatedSource, registerIsolatedSource } from './guest-login-source';
import type { OrderDetail } from '../src/order/OrderQueryStore';
const password = ' A1-private-passphrase-가나다 ';
export async function isolateGSource(page: Page, source: string) {
    return prepare("post-purchase-helpers: isolateGSource", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('isolated API port required');
        for (const path of ['/api/auth/login', '/api/guest/access/login', '/api/guest/verifications'])
            await registerIsolatedSource(page, path, async (route) => {
                const request = route.request();
                if (request.method() !== 'POST') {
                    await route.continue();
                    return;
                }
                expect(new URL(request.url()).hostname).toBe('127.0.0.1');
                const original = await request.allHeaders();
                expect(original['x-csrf-token']).toBeTruthy();
                expect(original.cookie).toBeTruthy();
                const result = await new Promise<{
                    status: number;
                    headers: Record<string, string>;
                    body: Buffer;
                }>((resolve, reject) => {
                    const transportTimeout = currentDeadline().remaining(10000);
                    const r = rawRequest({ hostname: '127.0.0.1', port, path, method: 'POST', localAddress: source, headers: { ...original, host: '127.0.0.1:' + port } }, response => {
                        const chunks: Buffer[] = [];
                        response.on('data', c => chunks.push(Buffer.from(c)));
                        response.on('error', reject);
                        response.on('end', () => {
                            const h: Record<string, string> = {};
                            for (const [k, v] of Object.entries(response.headers))
                                if (v !== undefined && k !== 'transfer-encoding')
                                    h[k] = Array.isArray(v) ? v.join('\n') : v;
                            resolve({ status: response.statusCode ?? 500, headers: h, body: Buffer.concat(chunks) });
                        });
                    });
                    const absoluteTimer = setTimeout(() => r.destroy(new Error('Browser operation transport deadline exceeded')), transportTimeout);
                    r.once('close', () => clearTimeout(absoluteTimer));
                    r.on('error', reject);
                    r.setTimeout(10000, () => r.destroy(new Error('G real authentication transport timeout')));
                    r.end(request.postData() ?? '');
                });
                await route.fulfill(result);
            });
    });
}
export async function actor(context: BrowserContext, email: string, source: string) {
    const { page } = await prepare("post-purchase-helpers: actor [1]", async () => {
        const page = await context.newPage();
        return { page };
    });
    await prepare("post-purchase-helpers: actor [2]", async () => {
        await isolateGSource(page, source);
    });
    return operation("post-purchase-helpers: 로그인 [3]", async () => {
        await armIsolatedSource(page, '/api/auth/login');
        await page.goto('/login');
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(password);
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        return page;
    });
}
export async function get<T>(context: BrowserContext, path: string) { return operation("post-purchase-helpers: get", async () => { const r = await context.request.get(path); expect(r.status(), await r.text()).toBe(200); return await r.json() as T; }); }
export async function post<T>(context: BrowserContext, path: string, data: Record<string, unknown>) { return operation("post-purchase-helpers: post", async () => { const r = await context.request.post(path, { headers: await headers(context), data }); expect(r.status(), await r.text()).toBe(200); return await r.json() as T; }); }
export const order = (c: BrowserContext, n: string) => get<OrderDetail>(c, '/api/orders/' + n);
export async function geometry(page: Page, info: TestInfo, label: string) {
    return operation("post-purchase-helpers: geometry", async () => {
        for (const width of [375, 768, 1440]) {
            await page.setViewportSize({ width, height: 900 });
            const m = await page.evaluate(() => ({ inner: innerWidth, client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, overflow: [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + .5 || e.scrollWidth > e.clientWidth + 1).map(e => ({ tag: e.tagName, classes: e.className, text: e.textContent?.slice(0, 160), client: e.clientWidth, scroll: e.scrollWidth, bbox: e.getBoundingClientRect().toJSON() })) }));
            await info.attach(label + '-' + width, { body: JSON.stringify({ requested: width, ...m }), contentType: 'application/json' });
            expect(m.inner).toBe(width);
            expect(m.client).toBe(width);
            expect(m.scroll, JSON.stringify(m.overflow)).toBeLessThanOrEqual(m.client);
        }
    });
}
export function snapshotAmounts(d: OrderDetail) {
    let total = 0n;
    for (const s of d.stores) {
        let net = 0n;
        for (const i of s.items) {
            let sum = 0n;
            for (const u of i.units) {
                expect(BigInt(u.net)).toBe(BigInt(u.gross) - BigInt(u.promotionDiscount) - BigInt(u.couponDiscount));
                sum += BigInt(u.net);
            }
            expect(sum).toBe(BigInt(i.net));
            net += sum;
        }
        expect(net + BigInt(s.shippingBase) - BigInt(s.shippingDiscount)).toBe(BigInt(s.total));
        total += BigInt(s.total);
    }
    expect(total).toBe(BigInt(d.total));
}
