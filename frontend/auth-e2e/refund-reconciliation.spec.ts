import { currentDeadline, operation, prepare } from './business-deadline';
import { test, expect, type BrowserContext, type Page } from './business-deadline';
import { request as rawRequest } from 'node:http';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { headers } from './purchase-helpers';
type Seed = {
    orderNumber: string;
    attemptId: string;
    buyerEmail: string;
    phone: string;
    guest: boolean;
    amount: string;
};
type Database = {
    attempts: {
        id: string;
        purpose: string;
        state: string;
        amount: string;
    }[];
    budget: {
        paid: string;
        reserved: string;
        succeeded: string;
    }[];
    provider: {
        submit_calls: number;
        query_calls: number;
        effects: number;
    }[];
    consumptions: {
        slot: number;
        decision: string;
    }[];
    holds: {
        state: string;
    }[];
    payment: {
        state: string;
        compensation: string;
    }[];
};
const panel = (page: Page) => page.getByRole('region', { name: '제공자 환불 상태', exact: true });
async function seed(context: BrowserContext, project: string, scenario: string) { return prepare("refund-reconciliation: seed", async () => { const r = await context.request.get('/api/__test/refunds/seed/' + project + '/' + scenario); expect(r.status(), await r.text()).toBe(200); return await r.json() as Seed; }); }
async function login(context: BrowserContext, s: Seed) {
    return operation("refund-reconciliation: login", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('real isolated API port required');
        const jar = new Map<string, string>();
        const call = (path: string, method: string, data?: Record<string, string>, token?: string, form = false) => new Promise<{
            status: number;
            data: Record<string, unknown>;
        }>((resolve, reject) => {
            const body = data === undefined ? undefined : form ? new URLSearchParams(data).toString() : JSON.stringify(data);
            const transportTimeout = currentDeadline().remaining(10000);
            const req = rawRequest({ hostname: '127.0.0.1', port, path, method, localAddress: '127.0.0.10', headers: { Accept: 'application/json', ...(jar.size ? { Cookie: [...jar].map(([key, value]) => key + '=' + value).join('; ') } : {}), ...(body === undefined ? {} : { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', 'Content-Length': Buffer.byteLength(body) }), ...(token ? { 'X-CSRF-TOKEN': token } : {}) } }, response => {
                for (const raw of response.headers['set-cookie'] ?? []) {
                    const [pair] = raw.split(';'), [name, ...value] = pair.split('=');
                    jar.set(name, value.join('='));
                }
                let text = '';
                response.setEncoding('utf8');
                response.on('data', chunk => { text += chunk; });
                response.on('end', () => {
                    try {
                        resolve({ status: response.statusCode ?? 0, data: text ? JSON.parse(text) as Record<string, unknown> : {} });
                    }
                    catch (error) {
                        reject(error);
                    }
                });
            });
            const absoluteTimer = setTimeout(() => req.destroy(new Error('Browser operation transport deadline exceeded')), transportTimeout);
            req.once('close', () => clearTimeout(absoluteTimer));
            req.on('error', reject);
            req.setTimeout(10000, () => req.destroy(new Error('isolated F authentication timeout')));
            req.end(body);
        });
        const csrf = await call('/api/auth/csrf', 'GET');
        expect(csrf.status).toBe(200);
        expect(csrf.data.headerName).toBe('X-CSRF-TOKEN');
        const response = await call(s.guest ? '/api/guest/access/login' : '/api/auth/login', 'POST', s.guest ? { orderNumber: s.orderNumber, phone: s.phone, password: '  guest lookup 密碼  ' } : { email: s.buyerEmail, password: ' A1-private-passphrase-가나다 ' }, String(csrf.data.token), !s.guest);
        expect(response.status).toBe(200);
        expect((await call('/api/__test/refunds/source', 'GET')).data.source).toBe('127.0.0.10');
        if (s.guest)
            expect(jar.has('GUEST_ACCESS')).toBe(true);
        await context.addCookies([...jar].map(([name, value]) => ({ name, value, domain: '127.0.0.1', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' as const })));
        expect((await context.request.get(s.guest ? '/api/guest/access/references' : '/api/auth/me')).status()).toBe(200);
    });
}
async function database(context: BrowserContext, s: Seed) { return operation("refund-reconciliation: database", async () => { const r = await context.request.get('/api/__test/refunds/' + s.orderNumber + '/database'); expect(r.status(), await r.text()).toBe(200); return await r.json() as Database; }); }
async function work(context: BrowserContext, s: Seed, action: 'AUTO' | 'SUCCESS') { return operation("refund-reconciliation: work", async () => { const r = await context.request.post('/api/__test/refunds/' + s.orderNumber + '/work', { headers: await headers(context), data: { action } }); expect(r.status(), await r.text()).toBe(200); expect(await r.json()).toMatchObject({ actualJobs: true, providerMode: 'DEMO', fabricatedFinancialCompletion: false }); }); }
test('F real refund status follows UNKNOWN then five-query operations review and verified DEMO completion without UI refund POST', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'member');
    await prepare("refund-reconciliation: F real refund status follows UNKNOWN then five-query operations review and verified DEMO completion without UI refund POST [1]", async () => {
        await login(context, s);
    });
    const { before, writes } = await operation("refund-reconciliation: F real refund status follows UNKNOWN then five-query operations review and verified DEMO completion without UI refund POST [2]", async () => {
        const before = await database(context, s);
        expect(before.attempts.find(a => a.id === s.attemptId)?.state).toBe('UNKNOWN');
        expect(before.provider).toHaveLength(1);
        expect(before.provider[0]).toMatchObject({ submit_calls: 1, query_calls: 0, effects: 0 });
        const writes: string[] = [];
        page.on('request', r => {
            if (r.method() === 'POST' && /\/api\/(orders|admin)\//.test(r.url()))
                writes.push(r.url());
        });
        return { before, writes };
    });
    await operation("refund-reconciliation: F real refund status follows UNKNOWN then five-query operations review and verified DEMO completion without UI refund POST [3]", async () => {
        await page.goto('/account/orders/' + s.orderNumber);
        await expect(panel(page).getByText('환불 확인 중', { exact: true })).toBeVisible();
        expect(await database(context, s)).toEqual(before);
    });
    await operation("refund-reconciliation: 환불 상태 다시 조회 [4]", async () => {
        await work(context, s, 'AUTO');
        await panel(page).getByRole('button', { name: '환불 상태 다시 조회' }).click();
        await expect(panel(page).getByText('운영 확인 필요', { exact: true })).toBeVisible();
        const reviewed = await database(context, s);
        expect(reviewed.provider[0]).toMatchObject({ submit_calls: 1, query_calls: 5, effects: 0 });
        expect(reviewed.consumptions.filter(c => c.decision === 'CALL')).toHaveLength(5);
        expect(reviewed.budget).toEqual(before.budget);
        expect(reviewed.holds).toEqual(before.holds);
    });
    await operation("refund-reconciliation: 환불 상태 다시 조회 [5]", async () => {
        await work(context, s, 'SUCCESS');
        await panel(page).getByRole('button', { name: '환불 상태 다시 조회' }).click();
        await expect(panel(page).getByText('제공자 환불 완료', { exact: true })).toBeVisible();
        const after = await database(context, s);
        expect(after.provider[0]).toMatchObject({ submit_calls: 1, query_calls: 6, effects: 1 });
        expect(after.attempts.find(a => a.id === s.attemptId)?.state).toBe('SUCCEEDED');
        const shipping = after.attempts.filter(a => a.purpose === 'SHIPPING');
        expect(shipping).toHaveLength(1);
        expect(shipping[0].state).toBe('RESERVED');
        await expect(panel(page).getByText('환불 확인 중', { exact: true })).toBeVisible();
        expect(BigInt(after.budget[0].succeeded)).toBe(BigInt(s.amount));
        expect(after.payment[0]).toMatchObject({ state: 'SUCCEEDED', compensation: 'NONE' });
        expect(writes).toEqual([]);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
});
test('F actual GAS lifetime logout discards an old refund response and parent order while server credentials remain valid after response loss', async ({ page, context }, info) => {
    const s = await seed(context, info.project.name, 'guest');
    await prepare("refund-reconciliation: F actual GAS lifetime logout discards an old refund response and parent order while server credentials remain valid after response loss [6]", async () => {
        await login(context, s);
    });
    const { before } = await operation("refund-reconciliation: /api/auth/me [7]", async () => {
        const before = await database(context, s);
        await page.goto('/guest/orders/' + s.orderNumber);
        await expect(page.getByTestId('order-number')).toHaveText(s.orderNumber);
        await expect(panel(page).getByText('환불 확인 중', { exact: true })).toBeVisible();
        expect((await context.request.get('/api/auth/me')).status()).toBe(401);
        return { before };
    });
    let release!: () => void, arrived!: () => void, finished!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; }), ready = new Promise<void>(resolve => { arrived = resolve; }), delivered = new Promise<void>(resolve => { finished = resolve; });
    await prepare("refund-reconciliation: F actual GAS lifetime logout discards an old refund response and parent order while server credentials remain valid after response loss [8]", async () => {
        await page.route('**/api/orders/' + s.orderNumber + '/refunds', async (route) => {
            const response = await route.fetch();
            expect(response.status()).toBe(200);
            expect((await response.json()).some((r: {
                attemptId: string;
            }) => r.attemptId === s.attemptId)).toBe(true);
            arrived();
            await pending;
            await route.fulfill({ response });
            finished();
        });
    });
    await operation("refund-reconciliation: 환불 상태 다시 조회 [9]", async () => {
        await panel(page).getByRole('button', { name: '환불 상태 다시 조회' }).click();
        await ready;
    });
    let failedLogout = 0;
    await prepare("refund-reconciliation: F actual GAS lifetime logout discards an old refund response and parent order while server credentials remain valid after response loss [10]", async () => {
        await page.route('**/api/guest/access/logout', async (route) => { await route.abort('failed'); failedLogout++; });
    });
    await operation("refund-reconciliation: /api/guest/access/references [11]", async () => {
        const manifest = JSON.parse(readFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-app', '.vite', 'manifest.json'), 'utf8'));
        const entry = manifest['src/guest/GuestAccessStore.ts'];
        expect(entry?.isEntry).toBe(true);
        expect(entry.file).toMatch(/^assets\/[\w.-]+\.js$/);
        const result = await page.evaluate(async (path: string) => { const { GuestAccessStore } = await import(path); const store = new GuestAccessStore(); await store.logout(); const result = store.getSnapshot(); store.dispose(); return { authenticated: result.authenticated, logoutPending: result.logoutPending }; }, '/' + entry.file);
        expect(result).toEqual({ authenticated: false, logoutPending: true });
        expect(failedLogout).toBe(1);
        await expect(page.getByTestId('order-number')).toHaveCount(0);
        await expect(panel(page)).toHaveCount(0);
        release();
        await delivered;
        await expect(page.getByTestId('order-number')).toHaveCount(0);
        await expect(panel(page)).toHaveCount(0);
        expect((await context.request.get('/api/guest/access/references')).status()).toBe(200);
        expect((await context.request.get('/api/auth/me')).status()).toBe(401);
        expect(await database(context, s)).toEqual(before);
    });
});
