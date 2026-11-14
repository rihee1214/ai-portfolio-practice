import { operation, prepare, cleanup } from './business-deadline';
import { expect, test } from './business-deadline';
import { headers } from './purchase-helpers';
import { geometry } from './post-purchase-helpers';
test('members receive private product and order answers from the seller and assigned support', async ({ page, context, browser }, info) => {
    const { slug, password } = await operation("questions-support: /api/auth/login [1]", async () => {
        const suffix = 'questionmember-' + info.project.name;
        const slug = 'p4b-browse-order-' + suffix + '-a';
        const password = ' A1-private-passphrase-가나다 ';
        const buyerLogin = await context.request.post('/api/auth/login', { headers: await headers(context),
            form: { email: 'order-' + suffix + '@example.com', password } });
        expect(buyerLogin.status()).toBe(200);
        return { slug, password };
    });
    const { product, text } = await operation("questions-support: 상품 문의 [2]", async () => {
        await page.goto('/products/' + slug);
        const product = page.getByRole('region', { name: '상품 문의', exact: true });
        await product.getByRole('button', { name: '상품 문의 열기' }).click();
        const text = '비밀문의'.repeat(50);
        await product.getByLabel('문의 본문').fill(text);
        await product.getByRole('button', { name: '문의 등록' }).click();
        await expect(product.getByText(text, { exact: true })).toBeVisible();
        return { product, text };
    });
    const { file } = await operation("questions-support: 첨부 이미지 관리 [3]", async () => {
        await product.getByRole('button', { name: '첨부 이미지 관리' }).click();
        const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4XmNgYGD4D8UQBgAd9AP95BvM/wAAAABJRU5ErkJggg==', 'base64');
        await product.getByLabel(/JPEG·PNG·WebP 파일/).setInputFiles({ name: 'question.png', mimeType: 'image/png', buffer: png });
        await product.getByRole('button', { name: '첨부 저장' }).click();
        const photo = product.getByAltText('첨부 이미지 1');
        await expect(photo).toBeVisible();
        const file = await photo.getAttribute('src');
        expect(file).toBeTruthy();
        return { file };
    });
    const { visitor } = await prepare("questions-support: members receive private product and order answers from the seller and assigned support [4]", async () => {
        const visitor = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { visitor };
    });
    const { seller } = await prepare("questions-support: members receive private product and order answers from the seller and assigned support [5]", async () => {
        const seller = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { seller };
    });
    const { operator } = await prepare("questions-support: members receive private product and order answers from the seller and assigned support [6]", async () => {
        const operator = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { operator };
    });
    try {
        await operation("questions-support: /api/products/ [7]", async () => {
            const publicList = await visitor.request.get('/api/products/' + slug + '/questions');
            expect(publicList.status()).toBe(200);
            expect(await publicList.text()).not.toContain(text);
            expect((await visitor.request.get(file!)).status()).toBe(404);
        });
        await operation("questions-support: /api/auth/login [8]", async () => {
            expect((await seller.request.post('/api/auth/login', { headers: await headers(seller), form: { email: slug + '@example.com', password } })).status()).toBe(200);
        });
        const { shop } = await prepare("questions-support: members receive private product and order answers from the seller and assigned support [9]", async () => {
            const shop = await seller.newPage();
            return { shop };
        });
        await operation("questions-support: 첨부 이미지 보기 [10]", async () => {
            await shop.goto('/seller/questions');
            await shop.locator('li button').first().click();
            await shop.getByRole('button', { name: '첨부 이미지 보기' }).click();
            await expect(shop.getByAltText('첨부 이미지 1')).toBeVisible();
        });
        const { answer } = await operation("questions-support: 답변 등록 [11]", async () => {
            await shop.getByLabel('답변 본문').fill('판매자의 비밀 답변');
            const savedAnswer = shop.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/answer'));
            await shop.getByRole('button', { name: '답변 등록' }).click();
            await expect(shop.getByText(/판매자의 비밀 답변/)).toBeVisible();
            const answer = await (await savedAnswer).json();
            return { answer };
        });
        await operation("questions-support: /api/__test/notifications/questions/ [12]", async () => {
            expect((await context.request.post('/api/__test/notifications/questions/' + answer.id + '/deliver', { headers: await headers(context) })).status()).toBe(200);
        });
        const { notice } = await operation("questions-support: 상품 문의에 답변이 등록되었습니다. [13]", async () => {
            await page.goto('/account/notifications');
            const notice = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: '상품 문의에 답변이 등록되었습니다.' }) });
            await expect(notice).toHaveCount(1);
            return { notice };
        });
        await operation("questions-support: 읽음 표시 [14]", async () => {
            await notice.getByRole('button', { name: '읽음 표시' }).click();
            await expect(notice).toContainText('읽음');
            await expect(notice.getByRole('button')).toHaveCount(0);
            await expect(notice).not.toContainText('판매자의 비밀 답변');
            await geometry(page, info, 'member-notification-read');
        });
        await operation("questions-support: members receive private product and order answers from the seller and assigned support [15]", async () => {
            await notice.getByRole('link').click();
            await expect(page).toHaveURL(new RegExp('/products/' + slug + '$'));
            await page.reload();
        });
        const { number } = await operation("questions-support: 상품 문의 열기 [16]", async () => {
            await product.getByRole('button', { name: '상품 문의 열기' }).click();
            await expect(product.getByText(/판매자의 비밀 답변/)).toBeVisible();
            await geometry(page, info, 'private-product-answer');
            const orders = await (await context.request.get('/api/account/orders')).json();
            const number = orders.orders[0].orderNumber;
            return { number };
        });
        const { desk, id } = await operation("questions-support: 주문 문의 [17]", async () => {
            await page.goto('/account/orders/' + number);
            const desk = page.getByRole('region', { name: '주문 문의' }).first();
            await desk.getByRole('button', { name: '주문 문의 열기' }).click();
            await desk.getByLabel('메시지 본문').fill('배송 내용을 확인하고 싶습니다.');
            const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/support/cases');
            await desk.getByRole('button', { name: '문의 접수하기' }).click();
            const created = await response;
            expect(created.status()).toBe(200);
            const { id } = await created.json();
            return { desk, id };
        });
        await operation("questions-support: /api/auth/login [18]", async () => {
            expect((await operator.request.post('/api/auth/login', { headers: await headers(operator),
                form: { email: 'c-support-b-ui-' + info.project.name + '@example.com', password } })).status()).toBe(200);
        });
        const { admin } = await prepare("questions-support: members receive private product and order answers from the seller and assigned support [19]", async () => {
            const admin = await operator.newPage();
            return { admin };
        });
        await operation("questions-support: 지원 접수함 열기 [20]", async () => {
            await admin.goto('/admin/support');
            await admin.getByRole('button', { name: '지원 접수함 열기' }).click();
            const row = admin.locator('article').filter({ hasText: id.slice(-8) });
            await row.getByRole('button', { name: '내가 담당' }).click();
            await row.getByRole('button', { name: '본문 열기' }).click();
            await expect(admin.getByText('배송 내용을 확인하고 싶습니다.', { exact: true })).toBeVisible();
        });
        await operation("questions-support: 답변 보내기 [21]", async () => {
            await admin.getByLabel('메시지 본문').fill('담당자가 배송을 확인했습니다.');
            await admin.getByRole('button', { name: '답변 보내기' }).click();
            await expect(admin.getByText('담당자가 배송을 확인했습니다.', { exact: true })).toBeVisible();
            await geometry(admin, info, 'assigned-order-case');
        });
        await operation("questions-support: 주문 문의  [22]", async () => {
            await desk.getByRole('button', { name: '주문 문의 ' + id.slice(-8) }).click();
            await expect(desk.getByText('담당자가 배송을 확인했습니다.', { exact: true })).toBeVisible();
        });
    }
    finally {
        await cleanup("questions-support: members receive private product and order answers from the seller and assigned support [23]", async () => {
            await Promise.all([visitor.close(), seller.close(), operator.close()]);
        });
    }
});
