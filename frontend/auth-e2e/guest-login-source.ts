import { bindDeadline, currentDeadline, prepare } from './business-deadline';
import { expect, type Page } from './business-deadline';
import { request as httpRequest } from 'node:http';
type SourceHandler = (route: Parameters<Parameters<Page['route']>[1]>[0]) => Promise<void>;
type SourceRegistration = {
    handler: SourceHandler;
    installed: SourceHandler;
    arming?: boolean;
};
const isolatedSources = new WeakMap<Page, Map<string, SourceRegistration>>();
/** Preparation records the exact endpoint and transport; commands explicitly arm their own token. */
export async function registerIsolatedSource(page: Page, path: string, handler: SourceHandler) {
    let sources = isolatedSources.get(page);
    if (!sources) {
        sources = new Map();
        isolatedSources.set(page, sources);
    }
    if (sources.has(path))
        throw new Error('Isolated source already configured for ' + path);
    const blocker: SourceHandler = async () => { throw new Error('Isolated endpoint was not armed by its triggering command: ' + path); };
    await page.route('**' + path, blocker);
    sources.set(path, { handler, installed: blocker });
}
/** Pages without a source configured for this exact endpoint intentionally need no proxy. */
export async function armIsolatedSource(page: Page, path: string) {
    const registration = isolatedSources.get(page)?.get(path);
    if (!registration)
        return;
    if (currentDeadline().kind !== 'operation')
        throw new Error('An isolated source must be armed by its triggering command');
    if (registration.arming) throw new Error('Concurrent isolated-source arms for ' + path);
    const bound = bindDeadline(registration.handler);
    registration.arming = true;
    try {
        await page.unroute('**' + path, registration.installed);
        await page.route('**' + path, bound);
        registration.installed = bound;
    } finally { registration.arming = false; }
}
export async function isolateLoginSource(page: Page, source: '127.0.0.3' | '127.0.0.4' = '127.0.0.3') { return prepare("guest-login-source: isolateLoginSource", async () => { await isolateSource(page, '/api/guest/access/login', source); }); }
export async function isolatePhoneSource(page: Page) { return prepare("guest-login-source: isolatePhoneSource", async () => { await isolateSource(page, '/api/guest/verifications', '127.0.0.4'); }); }
async function isolateSource(page: Page, path: '/api/guest/access/login' | '/api/guest/verifications', source: '127.0.0.3' | '127.0.0.4') {
    return prepare("guest-login-source: isolateSource", async () => {
        const port = Number(process.env.A3_API_PORT);
        if (!Number.isInteger(port) || port < 1 || port > 65535)
            throw new Error('isolated Java loopback port required');
        await registerIsolatedSource(page, path, async (route) => {
            if (new URL(route.request().url()).hostname !== '127.0.0.1' || new URL(route.request().url()).pathname !== path || route.request().method() !== 'POST')
                throw new Error('loopback browser destination required');
            const originalHeaders = await route.request().allHeaders();
            expect(Object.hasOwn(originalHeaders, 'cookie')).toBe(true);
            expect(Object.hasOwn(originalHeaders, 'x-csrf-token')).toBe(true);
            const result = await new Promise<{
                status: number;
                headers: Record<string, string>;
                body: Buffer;
            }>((resolve, reject) => {
                const transportTimeout = currentDeadline().remaining(10000);
                const request = httpRequest({ hostname: '127.0.0.1', port, localAddress: source, path, method: 'POST', headers: { ...originalHeaders, host: '127.0.0.1:' + process.env.A3_API_PORT } }, response => {
                    const chunks: Buffer[] = [];
                    response.on('data', chunk => chunks.push(Buffer.from(chunk)));
                    response.on('error', reject);
                    response.on('end', () => {
                        const headers: Record<string, string> = {};
                        for (const [name, value] of Object.entries(response.headers))
                            if (value !== undefined && name !== 'transfer-encoding')
                                headers[name] = Array.isArray(value) ? value.join('\n') : value;
                        resolve({ status: response.statusCode ?? 500, headers, body: Buffer.concat(chunks) });
                    });
                });
                const absoluteTimer = setTimeout(() => request.destroy(new Error('Browser operation transport deadline exceeded')), transportTimeout);
                request.once('close', () => clearTimeout(absoluteTimer));
                request.on('error', reject);
                request.end(route.request().postData() ?? '');
            });
            await route.fulfill(result);
        });
    });
}
