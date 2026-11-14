import { operation, prepare, cleanup } from './business-deadline';
import { expect, test } from './business-deadline';
import { headers } from './purchase-helpers';
import { geometry } from './post-purchase-helpers';
import { checkOrderSupportAndModeration, claimsForOrder } from './operations-journey';
test('confirmed member publishes edits comments and deletes one original item review', async ({ page, context, browser }, info) => {
    const { suffix, number, path, order, product } = await operation("content: /api/auth/login [1]", async () => {
        const suffix = 'reviewmember-' + info.project.name;
        const login = await context.request.post('/api/auth/login', { headers: await headers(context),
            form: { email: 'order-' + suffix + '@example.com', password: ' A1-private-passphrase-가나다 ' } });
        expect(login.status()).toBe(200);
        const orders = await (await context.request.get('/api/account/orders')).json();
        expect(orders.orders).toHaveLength(1);
        const number = orders.orders[0].orderNumber;
        const path = '/api/orders/' + number + '/confirmations';
        const view = await (await context.request.get(path)).json();
        const eligible = view.units.filter((entry: {
            eligible: boolean;
        }) => entry.eligible);
        expect(eligible).toHaveLength(2);
        const order = '/account/orders/' + number;
        const product = '/products/p4b-browse-order-' + suffix + '-a';
        return { suffix, number, path, order, product };
    });
    const { panel } = await operation("content: 구매 확정 [2]", async () => {
        await page.goto(order);
        await expect(page.getByText('구매 확정하고 환불되지 않은 수량이 있어야 작성할 수 있습니다.').first()).toBeVisible();
        const panel = page.getByRole('region', { name: '구매 확정', exact: true });
        return { panel };
    });
    const { editor, text, snapshot, confirmation, claims, item, reviewId } = await operation("content: 선택한 수량을 구매 확정합니다 [3]", async () => {
        await panel.locator('li input[type=checkbox]').first().check();
        await panel.getByRole('checkbox', { name: '선택한 수량을 구매 확정합니다' }).check();
        await panel.getByRole('button', { name: '선택 수량 구매 확정', exact: true }).click();
        const editor = page.getByRole('region', { name: '구매 후기' }).filter({ has: page.getByLabel('후기 본문') });
        const text = '긴후기'.repeat(200);
        await editor.getByLabel('후기 본문').fill(text);
        const snapshot = await (await context.request.get('/api/orders/' + number)).json();
        const confirmation = await (await context.request.get(path)).json();
        const claims = await claimsForOrder(context, number);
        const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/review'));
        await editor.getByRole('button', { name: '후기 등록' }).click();
        const creation = await created;
        expect(creation.status()).toBe(200);
        const item = new URL(creation.url()).pathname.split('/').at(-2)!, reviewId = (await creation.json()).reviewId;
        await expect(editor.getByRole('button', { name: '후기 수정' })).toBeVisible();
        await page.reload();
        await expect(editor.getByLabel('후기 본문')).toHaveValue(text);
        return { editor, text, snapshot, confirmation, claims, item, reviewId };
    });
    const { png } = await operation("content: 후기 수정 [4]", async () => {
        await editor.getByLabel('평점').selectOption('4');
        await editor.getByRole('button', { name: '후기 수정' }).click();
        await expect(editor.getByRole('status')).toHaveText('저장했습니다.');
        const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4XmNgYGD4D8UQBgAd9AP95BvM/wAAAABJRU5ErkJggg==', 'base64');
        return { png };
    });
    await operation("content: review.png [5]", async () => {
        await editor.getByLabel(/이미지 파일/).setInputFiles({ name: 'review.png', mimeType: 'image/png', buffer: png });
        const uploaded = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/review/images/'));
        await editor.getByRole('button', { name: '이미지 저장' }).click();
        expect((await uploaded).status()).toBe(200);
    });
    const { reviews, oldUrl } = await operation("content: 상품 후기 [6]", async () => {
        await page.goto(product);
        const reviews = page.getByRole('region', { name: '상품 후기' });
        await expect(reviews.getByText('평균 4.0점 · 후기 1개')).toBeVisible();
        await expect(reviews.getByText(text, { exact: true })).toBeVisible();
        const image = reviews.getByAltText('후기 이미지 1');
        await expect(image).toBeVisible();
        const oldUrl = await image.getAttribute('src');
        expect(oldUrl).toBeTruthy();
        await geometry(page, info, 'review-long-body');
        return { reviews, oldUrl };
    });
    const { change } = await operation("content: 댓글 등록 [7]", async () => {
        await reviews.getByLabel('댓글 본문').fill('구매자의 추가 설명');
        await reviews.getByRole('button', { name: '댓글 등록' }).click();
        await expect(reviews.getByText('구매자의 추가 설명', { exact: true })).toBeVisible();
        const change = reviews.getByRole('group', { name: '댓글 수정' });
        return { change };
    });
    await operation("content: confirmed member publishes edits comments and deletes one original item review [8]", async () => {
        await change.getByLabel('댓글 본문').fill('댓글 수정 결과');
    });
    const { removed } = await operation("content: 댓글 수정 [9]", async () => {
        await change.getByRole('button', { name: '댓글 수정' }).click();
        await expect(reviews.locator('p').filter({ hasText: /^댓글 수정 결과$/ })).toBeVisible();
        const removed = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/responses'));
        return { removed };
    });
    await operation("content: 댓글 삭제 [10]", async () => {
        await change.getByRole('button', { name: '댓글 삭제' }).click();
        expect((await removed).status()).toBe(200);
        await expect(change).toHaveCount(0);
        await expect(reviews.getByRole('group', { name: '판매자 답글 작성' })).toHaveCount(0);
    });
    const { seller } = await prepare("content: confirmed member publishes edits comments and deletes one original item review [11]", async () => {
        const seller = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { seller };
    });
    try {
        await operation("content: /api/auth/login [12]", async () => {
            const ownLogin = await seller.request.post('/api/auth/login', { headers: await headers(seller),
                form: { email: 'p4b-browse-order-' + suffix + '-a@example.com', password: ' A1-private-passphrase-가나다 ' } });
            expect(ownLogin.status()).toBe(200);
        });
        const { shop } = await prepare("content: confirmed member publishes edits comments and deletes one original item review [13]", async () => {
            const shop = await seller.newPage();
            return { shop };
        });
        await operation("content: 판매자 답글 작성 [14]", async () => {
            await shop.goto(product);
            const reply = shop.getByRole('group', { name: '판매자 답글 작성' });
            await reply.getByLabel('판매자 답글 본문').fill('구매해 주셔서 감사합니다.');
            await reply.getByRole('button', { name: '판매자 답글 등록' }).click();
            await expect(shop.getByText('구매해 주셔서 감사합니다.', { exact: true })).toBeVisible();
        });
        await checkOrderSupportAndModeration(page, context, seller, browser, info, number, product, item, reviewId, oldUrl!, snapshot, confirmation, claims);
    }
    finally {
        await cleanup("content: confirmed member publishes edits comments and deletes one original item review [15]", async () => {
            await seller.close();
        });
    }
    await operation("content: 후기 삭제 [16]", async () => {
        await page.goto(order);
        await editor.getByRole('button', { name: '후기 삭제' }).click();
        await expect(editor.getByRole('button', { name: '후기 등록' })).toBeVisible();
    });
    await operation("content: confirmed member publishes edits comments and deletes one original item review [17]", async () => {
        await page.goto(product);
        await expect(reviews.getByText('등록된 후기가 없습니다.')).toBeVisible();
        expect((await context.request.get(oldUrl!)).status()).toBe(404);
    });
});
