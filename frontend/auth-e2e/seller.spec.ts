import { operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
import { randomUUID } from 'node:crypto';
const password = 'p3-browser-member-password';
async function token(page: Page) { return operation("seller: token", async () => { return (await (await page.request.get('/api/auth/csrf')).json()).token as string; }); }
async function login(page: Page, email: string, secret: string) {
    return operation("seller: login", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(secret);
        await page.getByLabel('비밀번호', { exact: true }).press('Enter');
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
test('모의 판매 신청·반려·재신청·승인과 구매자 자격 유지', async ({ page, browser }, info) => {
    const { email, headers, mail } = await operation("seller: /api/auth/signup [1]", async () => {
        const email = 'p3-browser-' + randomUUID() + '@example.com';
        const headers = { 'X-CSRF-TOKEN': await token(page) };
        expect((await page.request.post('/api/auth/signup', { headers, data: { email, password } })).status()).toBe(201);
        const mail = await page.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } });
        return { email, headers, mail };
    });
    await operation("seller: /api/auth/verify-email [2]", async () => {
        expect((await page.request.post('/api/auth/verify-email', { headers, data: { token: (await mail.json()).token } })).status()).toBe(200);
    });
    await prepare("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [3]", async () => {
        await login(page, email, password);
    });
    const { name } = await operation("seller: 판매 신청·이력 [4]", async () => {
        await page.getByRole('link', { name: '판매 신청·이력' }).click();
        const name = '모의 매대 ' + randomUUID().slice(0, 8);
        return { name };
    });
    async function apply() {
        return operation("seller: apply", async () => {
            await expect(page.getByLabel('모의 매대 이름')).toBeEnabled();
            await page.getByLabel('모의 매대 이름').fill(name);
            await page.getByLabel('판매 계획').fill('가상 상품만 판매하는 모의 계획');
            await page.getByRole('button', { name: '판매 신청하기', exact: true }).focus();
            await page.keyboard.press('Enter');
            await expect(page.getByText('처리했습니다.', { exact: true })).toBeVisible();
        });
    }
    await operation("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [5]", async () => {
        await apply();
        await page.reload();
        await expect(page.getByRole('article', { name }).getByText('심사 대기', { exact: true })).toBeVisible();
    });
    const { operatorContext } = await prepare("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [6]", async () => {
        const operatorContext = await browser.newContext({ viewport: page.viewportSize() ?? { width: 1280, height: 720 } });
        return { operatorContext };
    });
    const { operator } = await prepare("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [7]", async () => {
        const operator = await operatorContext.newPage();
        return { operator };
    });
    try {
        await prepare("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [8]", async () => {
            await login(operator, 'p3-admin-' + info.project.name + '@example.com', ' A1-private-passphrase-가나다 ');
        });
        await operation("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [9]", async () => {
            await operator.goto('/admin/sellers');
        });
        let card = operator.getByRole('article', { name }).filter({ has: operator.getByRole('button', { name: '심사 결과 저장' }) });
        await operation("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [10]", async () => {
            await expect(card).toBeVisible();
        });
        await operation("seller: 심사 결과 저장 [11]", async () => {
            await card.getByLabel('심사 결과').selectOption('REJECTED');
            await card.getByLabel('심사 사유').fill('모의 계획 보완');
            await card.getByRole('button', { name: '심사 결과 저장' }).click();
            await expect(operator.getByText('처리했습니다.', { exact: true })).toBeVisible();
        });
        await operation("seller: 목록 새로고침 [12]", async () => {
            await page.getByRole('button', { name: '목록 새로고침' }).click();
            await expect(page.getByRole('article', { name }).getByText('반려', { exact: true })).toBeVisible();
            await apply();
        });
        await operation("seller: 목록 새로고침 [13]", async () => {
            await operator.getByRole('button', { name: '목록 새로고침' }).click();
            card = operator.getByRole('article', { name }).filter({ has: operator.getByRole('button', { name: '심사 결과 저장' }) });
            await card.getByLabel('심사 결과').selectOption('APPROVED');
            await card.getByLabel('심사 사유').fill('모의 승인 완료');
            await card.getByRole('button', { name: '심사 결과 저장' }).click();
            await expect(operator.getByText('처리했습니다.', { exact: true })).toBeVisible();
            await operator.reload();
            await expect(operator.getByRole('article', { name }).getByText('승인', { exact: true })).toBeVisible();
            expect(await operator.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
            await operator.screenshot({ scale: 'css', path: info.outputPath('seller-review.png') });
            await page.reload();
            await expect(page.getByText(/판매자 자격이 있습니다/)).toBeVisible();
            expect((await (await page.request.get('/api/auth/me')).json()).roles.sort()).toEqual(['BUYER', 'SELLER']);
        });
        await operation("seller: /api/account/profile [14]", async () => {
            await page.goto('/seller/products');
            await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible();
            expect((await page.request.get('/api/account/profile')).status()).toBe(200);
        });
    }
    finally {
        await cleanup("seller: 모의 판매 신청·반려·재신청·승인과 구매자 자격 유지 [15]", async () => {
            await operatorContext.close();
        });
    }
});
