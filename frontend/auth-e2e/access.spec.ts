import { operation, prepare } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
async function login(page: Page, email: string) {
    return operation("access: login", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByLabel('비밀번호', { exact: true }).press('Enter');
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
test('직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인', async ({ page }, info) => {
    await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [1]", async () => {
        await page.goto('/admin/moderation');
        await expect(page.getByText('로그인이 필요합니다.', { exact: false })).toBeVisible();
        await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toHaveCount(0);
    });
    await prepare("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [2]", async () => {
        await login(page, 'c-seller-' + info.project.name + '@example.com');
    });
    await operation("access: 상품 관리 [3]", async () => {
        await page.goto('/seller/products');
        await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible();
        await page.reload();
        await expect(page.getByRole('heading', { name: '상품 관리', exact: true })).toBeVisible();
    });
    await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [4]", async () => {
        await page.goto('/admin/users');
        await expect(page.getByRole('alert')).toContainText('권한');
    });
    await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [5]", async () => {
        await page.goto('/account/profile');
        await expect(page.getByLabel('표시 이름')).toBeEnabled();
    });
    await operation("access: 로그아웃 [6]", async () => {
        await page.getByRole('button', { name: '로그아웃', exact: true }).click();
    });
    await prepare("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [7]", async () => {
        await login(page, 'c-admin-' + info.project.name + '@example.com');
    });
    await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [8]", async () => {
        await page.goto('/admin/moderation');
        await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible();
        await page.reload();
        await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        await page.screenshot({ scale: 'css', path: info.outputPath('permission.png') });
    });
    await operation("access: 권한 다시 확인 [9]", async () => {
        await page.goto('/admin/users');
        await expect(page.getByRole('alert')).toContainText('권한');
        await page.getByRole('button', { name: '권한 다시 확인' }).focus();
    });
    await operation("access: /api/admin/accounts/access [10]", async () => {
        await page.keyboard.press('Enter');
        await expect(page.getByRole('alert')).toContainText('권한');
        expect((await page.request.get('/api/admin/accounts/access')).status()).toBe(403);
        expect((await page.request.get('/api/admin/content/access')).status()).toBe(200);
    });
    for (const path of ['/admin/users/', '/ADMIN/users', '/admin/sellers/', '/admin/orders/']) {
        await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [11]", async () => {
            await page.goto(path);
            await expect(page.getByRole('alert')).toContainText('권한');
            await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toHaveCount(0);
        });
    }
    await operation("access: 직접 접근·새로고침·역할/개별 운영 권한 경계를 화면에서 확인 [12]", async () => {
        await page.goto('/ADMIN/moderation/');
        await expect(page.getByText('공간 접근 권한을 확인했습니다.', { exact: false })).toBeVisible();
    });
});
