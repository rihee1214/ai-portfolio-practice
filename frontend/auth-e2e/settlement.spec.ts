import { operation, prepare, cleanup } from './business-deadline';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test, type Page } from './business-deadline';
import { geometry } from './post-purchase-helpers';
import { headers } from './purchase-helpers';
type Seed = {
    storeId: string;
    sellerOrderId: string;
    causeId: string;
    weekStart: string;
    sellerEmail: string;
    supportEmail: string;
    inquiry: string;
    item: string;
    ordinal: number;
};
type Item = {
    sellerOrderId: string;
    kind: string;
    causeId: string;
    itemId: string | null;
    ordinal: number | null;
    amount: string;
    net: string;
};
type Batch = {
    id: string;
    weekStart: string;
    closed: boolean;
    state: string;
    version: number;
    net: string;
    items: Item[];
};
async function clickForBatch(page: Page, button: string, path: string): Promise<Batch> {
    return operation("settlement: clickForBatch", async () => {
        const [response] = await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST'),
            page.getByRole('button', { name: button, exact: true }).click(),
        ]);
        expect(response.status()).toBe(200);
        return response.json();
    });
}
test('settlement operator traces three confirmed units through review and mock payout of a closed week', async ({ page, context }, info) => {
    const seeds = JSON.parse(readFileSync(resolve(process.env.A3_EVIDENCE!, 'settlement-browser-order.json'), 'utf8')) as Record<string, Seed>;
    expect(seeds.desktop.weekStart).not.toBe(seeds.mobile.weekStart);
    const source = seeds[info.project.name];
    await operation("settlement: /api/auth/login [1]", async () => {
        const login = await context.request.post('/api/auth/login', { headers: await headers(context),
            form: { email: 'c-support-p11-browser@example.com', password: ' A1-private-passphrase-가나다 ' } });
        expect(login.status()).toBe(200);
    });
    await prepare('settlement: closed-week draft input', async () => {
        await page.goto('/admin/settlements');
        await page.getByLabel('정산 주 시작일').fill(source.weekStart);
    });
    const batches = '/api/admin/settlements/batches';
    const { draft, products, detail, store, batchPath } = await operation('settlement: create closed-week draft and verify original-unit ledger', async () => {
        const draft = await clickForBatch(page, '초안 생성', batches);
        expect(draft).toMatchObject({ id: expect.any(String), weekStart: source.weekStart, closed: true, state: 'DRAFT', net: '31501' });
        const items = draft.items.filter(i => i.sellerOrderId === source.sellerOrderId);
        const products = items.filter(i => i.kind === 'PRODUCT');
        expect(products.map(i => i.amount)).toEqual(['10000', '10000', '10001']);
        expect(new Set(products.map(i => i.itemId + '/' + i.ordinal)).size).toBe(3);
        expect(products.every(i => i.causeId === source.causeId)).toBe(true);
        expect(items.reduce((sum, i) => sum + BigInt(i.net), 0n)).toBe(31501n);
        const detail = page.getByRole('region', { name: '배치 상세', exact: true });
        const store = detail.getByRole('region', { name: '매대 ' + source.storeId, exact: true });
        await expect(store.getByText('매대 지급액 31,501원')).toBeVisible();
        await expect(store.getByText(source.causeId, { exact: false }).first()).toBeVisible();
        const batchPath = batches + '/' + draft.id;
        return { draft, products, detail, store, batchPath };
    });
    const reviewed = await operation('settlement: approve reviewed batch and unchanged payout amount', async () => {
        const reviewed = await clickForBatch(page, '검토 확정', batchPath + '/review');
        expect(reviewed).toMatchObject({ id: draft.id, weekStart: source.weekStart, state: 'REVIEWED', net: '31501' });
        await expect(detail.getByRole('heading', { name: source.weekStart + ' · 검토 완료', exact: true })).toBeVisible();
        return reviewed;
    });
    const { settings } = await operation('settlement: simulate payout and verify one closed-week ledger', async () => {
        const paid = await clickForBatch(page, '모의 지급', batchPath + '/simulate-pay');
        expect(paid).toMatchObject({ id: draft.id, weekStart: source.weekStart, state: 'PAID_MOCK', net: '31501' });
        const status = detail.getByRole('heading', { name: source.weekStart + ' · 모의 지급 완료', exact: true });
        const payout = store.getByText('매대 지급액 31,501원', { exact: true });
        await expect(detail.getByText('매대 ' + source.storeId + ' · 전주 미회수 조정 잔액 0원 · 이번 지급 31,501원 · 미회수 조정 잔액 (다음 주 이월) 0원', { exact: true })).toBeVisible();
        await expect(status).toBeVisible();
        await expect(payout).toBeVisible();
        await info.attach('weekly-mock-payout', { contentType: 'application/json', body: JSON.stringify({
                platform: info.project.name, ...source, products: products.map(({ itemId, ordinal, amount, causeId }) => ({ itemId, ordinal, amount, causeId })),
                transitions: [draft, reviewed, paid].map(({ id, state, version, net }) => ({ id, state, version, net })),
                heading: await status.innerText(), storePayout: await payout.innerText(),
            }) });
        await geometry(page, info, 'settlement-original-ledger');
        const settings = { baseURL: new URL(page.url()).origin, viewport: page.viewportSize() };
        return { settings };
    });
    const { seller } = await prepare("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [3]", async () => {
        const seller = await page.context().browser()!.newContext(settings);
        return { seller };
    });
    const { support } = await prepare("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [4]", async () => {
        const support = await page.context().browser()!.newContext(settings);
        return { support };
    });
    try {
        for (const [ctx, email] of [[seller, source.sellerEmail], [support, source.supportEmail]] as const) {
            await operation("settlement: /api/auth/login [5]", async () => {
                expect((await ctx.request.post('/api/auth/login', { headers: await headers(ctx), form: { email, password: ' A1-private-passphrase-가나다 ' } })).status()).toBe(200);
            });
        }
        const { shop } = await prepare("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [6]", async () => {
            const shop = await seller.newPage();
            return { shop };
        });
        await operation("settlement: 내 매대 정산 [7]", async () => {
            await shop.goto('/seller/settlements');
            await expect(shop.getByRole('heading', { name: '내 매대 정산' })).toBeVisible();
            await expect(shop.getByText("미회수 조정 잔액 (다음 주 이월)", { exact: true }).first()).toBeVisible();
            await expect(shop.getByText(new RegExp(source.causeId)).first()).toBeVisible();
            await expect(shop.getByText(new RegExp(seeds[info.project.name === 'desktop' ? 'mobile' : 'desktop'].sellerOrderId))).toHaveCount(0);
        });
        const { desk } = await prepare("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [8]", async () => {
            const desk = await support.newPage();
            return { desk };
        });
        const panel = await operation("settlement: 지원 접수함 열기 [9]", async () => {
            await desk.goto('/admin/support');
            await desk.getByRole('button', { name: '지원 접수함 열기' }).click();
            await desk.locator('article').filter({ hasText: source.inquiry.slice(-8) }).getByRole('button', { name: '본문 열기' }).click();
            await desk.getByRole('button', { name: '회수·검수 기록 열기' }).click();
            const panel = desk.getByRole('region', { name: '구매확정 후 하자 환불' });
            await panel.getByLabel('원 주문 상품 ID').fill(source.item);
            await panel.getByLabel('수량 번호').fill(String(source.ordinal));
            await panel.getByLabel('회수 기록 번호').fill('실제 회수 기록 ' + source.inquiry);
            return panel;
        });
        await operation('settlement: record actual original-unit collection', async () => {
            await panel.getByRole('button', { name: '실제 회수 기록' }).click();
            await expect(panel.getByLabel('검수 기록 번호')).toBeVisible();
        });
        await prepare('settlement: recorded collection inspection input', async () => {
            await panel.getByLabel('검수 기록 번호').fill('검수 ' + source.inquiry);
            await panel.getByLabel('검수 책임').selectOption('SELLER');
            await panel.getByLabel('검수 근거').fill('회수된 원 수량 판매자 책임 확인 '.repeat(24));
        });
        await geometry(desk, info, 'confirmed-refund-inspection-long-reason');
        await operation('settlement: record seller-responsible inspection result', async () => {
            await panel.getByRole('button', { name: '검수 결과 기록' }).click();
            await expect(panel.getByLabel('승인 사유')).toBeVisible();
        });
        await prepare('settlement: exceptional refund approval input', async () => {
            await panel.getByLabel('승인 사유').fill('회수와 검수 원 주문 일치');
        });
        const execute = await operation('settlement: approve exceptional refund with explicit execution gate', async () => {
            await panel.getByRole('button', { name: '예외 환불 승인' }).click();
            const execute = panel.getByRole('button', { name: '환불 실행', exact: true });
            await expect(execute).toBeDisabled();
            return execute;
        });
        await operation("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [10]", async () => {
            await panel.getByRole('checkbox').check();
            await execute.click();
            await expect(panel.getByRole('status')).toContainText('환불 실행 결과');
            await geometry(desk, info, 'confirmed-refund-approved-and-executed');
            await geometry(shop, info, 'own-store-settlement');
        });
        await operation("settlement: 매대 대시보드 [11]", async () => {
            await shop.goto('/seller/dashboard');
            const heading = shop.getByRole('heading', { name: '매대 대시보드', exact: true });
            await expect(heading).toBeVisible();
            const paidValue = shop.locator('dl div').filter({ has: shop.getByText('결제 성공액', { exact: true }) }).locator('dd');
            await expect(paidValue).toHaveText('33,001원');
            await geometry(shop, info, 'own-store-dashboard');
        });
        await operation("settlement: 내 매대 상세 업무 [12]", async () => {
            await shop.getByRole('link', { name: '내 매대 상세 업무' }).click();
            await expect(shop).toHaveURL(/\/seller\/orders$/);
        });
        await operation("settlement: 거래 금액·정산 [13]", async () => {
            await page.goto('/admin/dashboard');
            await expect(page.getByRole('heading', { name: '거래 금액·정산', exact: true })).toBeVisible();
            await expect(page.getByRole('button', { name: '재집계 요청' })).toHaveCount(0);
        });
    }
    finally {
        await cleanup("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [14]", async () => {
            await support.close();
        });
        await cleanup("settlement: settlement operator traces three confirmed units through review and mock payout of a closed week [15]", async () => {
            await seller.close();
        });
    }
});
