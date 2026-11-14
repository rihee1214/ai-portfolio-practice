import { operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
import { randomUUID } from 'node:crypto';
const originalPassword = 'b-browser-original-password';
const newPassword = 'b-browser-recovered-password';
test('탈퇴 확인 후 이전 계정 로그인과 직접 회원 API 접근을 차단한다', async ({ page }) => {
    const email = await preparedAccount(page);
    await operation("account: 회원 탈퇴 [1]", async () => {
        await page.getByLabel('탈퇴 확인 비밀번호').fill(originalPassword);
        await page.getByLabel('탈퇴 후 계정을 복구할 수 없음을 확인했습니다.').check();
        await page.getByRole('button', { name: '회원 탈퇴', exact: true }).focus();
        await page.keyboard.press('Enter');
        await expect(page.getByText('회원 탈퇴를 완료했습니다.', { exact: true })).toBeVisible();
        await expect(page.getByLabel('탈퇴 확인 비밀번호')).toHaveCount(0);
        expect((await page.request.get('/api/account/profile')).status()).toBe(401);
        await page.reload();
        await expect(page.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible();
    });
    await operation("account: /api/auth/login [2]", async () => {
        const response = await page.request.post('/api/auth/login', {
            headers: { 'X-CSRF-TOKEN': await csrf(page) }, form: { email, password: originalPassword },
        });
        expect(response.status()).toBe(401);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    });
});
async function csrf(page: Page) {
    return operation("account: csrf", async () => {
        return (await (await page.request.get('/api/auth/csrf')).json()).token as string;
    });
}
async function preparedAccount(page: Page) {
    const { email, headers, token } = await operation("account: /api/auth/signup [3]", async () => {
        const email = 'b-browser-' + randomUUID() + '@example.com';
        const headers = { 'X-CSRF-TOKEN': await csrf(page) };
        expect((await page.request.post('/api/auth/signup', { headers, data: { email, password: originalPassword } })).status()).toBe(201);
        const mail = await page.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } });
        const { token } = await mail.json();
        return { email, headers, token };
    });
    await operation("account: /api/auth/verify-email [4]", async () => {
        expect((await page.request.post('/api/auth/verify-email', { headers, data: { token } })).status()).toBe(200);
    });
    await prepare("account: prepare [5]", async () => {
        await login(page, email, originalPassword);
    });
    return email;
}
async function login(page: Page, email: string, password: string) {
    return operation("account: login", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(password);
        await page.getByLabel('비밀번호', { exact: true }).press('Enter');
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
test('프로필·배송지 등록 수정 삭제와 기본 주소를 실제 DB로 확인', async ({ page }, info) => {
    const email = await preparedAccount(page);
    await operation("account: 프로필·배송지 등록 수정 삭제와 기본 주소를 실제 DB로 확인 [6]", async () => {
        await page.goto('/account/profile');
        await expect(page.getByLabel('표시 이름', { exact: true })).toBeEnabled();
    });
    await operation("account: 프로필 저장 [7]", async () => {
        await page.getByLabel('표시 이름', { exact: true }).fill('테스트 구매자');
        await page.getByLabel('연락처', { exact: true }).fill('010-1234-5678');
        await page.getByRole('button', { name: '프로필 저장', exact: true }).click();
        await expect.poll(async () => (await (await page.request.get('/api/account/profile')).json()).displayName).toBe('테스트 구매자');
        await page.reload();
        await expect(page.getByLabel('표시 이름', { exact: true })).toHaveValue('테스트 구매자');
        expect((await (await page.request.get('/api/account/profile')).json()).email).toBe(email);
    });
    await operation("account: 프로필·배송지 등록 수정 삭제와 기본 주소를 실제 DB로 확인 [8]", async () => {
        await page.goto('/account/addresses');
        await expect(page.getByLabel('이름표', { exact: true })).toBeEnabled();
    });
    let { addresses } = await operation("account: 배송지 추가 [9]", async () => {
        await page.getByLabel('이름표', { exact: true }).fill('집');
        await page.getByLabel('수령인', { exact: true }).fill('테스트 구매자');
        await page.getByLabel('전화번호', { exact: true }).fill('010-1234-5678');
        await page.getByLabel('우편번호', { exact: true }).fill('12345');
        await page.getByLabel('주소', { exact: true }).fill('테스트로 123');
        await page.getByLabel('상세 주소', { exact: true }).fill('101호');
        await page.getByRole('button', { name: '배송지 추가', exact: true }).click();
        await expect(page.getByRole('button', { name: '수정', exact: true })).toHaveCount(1);
        const addresses = (await (await page.request.get('/api/account/addresses')).json()).addresses;
        expect(addresses).toHaveLength(1);
        expect(addresses[0].isDefault).toBe(true);
        return { addresses };
    });
    await operation("account: 수정 [10]", async () => {
        await page.getByRole('button', { name: '수정', exact: true }).click();
        await page.getByLabel('상세 주소', { exact: true }).fill('202호');
        await page.getByRole('button', { name: '배송지 저장', exact: true }).click();
        await expect.poll(async () => (await (await page.request.get('/api/account/addresses')).json()).addresses[0].addressLine2).toBe('202호');
        await page.reload();
        await expect(page.getByText(/202호/)).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        await page.screenshot({ scale: 'css', path: info.outputPath('addresses.png') });
        page.on('dialog', dialog => { void dialog.accept(); });
    });
    await operation("account: 삭제 [11]", async () => {
        await page.getByRole('button', { name: '삭제', exact: true }).click();
        await expect(page.getByRole('button', { name: '수정', exact: true })).toHaveCount(0);
        addresses = (await (await page.request.get('/api/account/addresses')).json()).addresses;
        expect(addresses).toEqual([]);
    });
});
test('비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인', async ({ page, browser }) => {
    const email = await preparedAccount(page);
    const { oldContext } = await prepare("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [12]", async () => {
        const oldContext = await browser.newContext();
        return { oldContext };
    });
    const { oldPage } = await prepare("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [13]", async () => {
        const oldPage = await oldContext.newPage();
        return { oldPage };
    });
    try {
        await operation("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [14]", async () => {
            await oldPage.goto('http://127.0.0.1:24176/login');
            await expect(oldPage.getByLabel('이메일', { exact: true })).toBeEnabled();
        });
        await operation("account: 로그인 [15]", async () => {
            await oldPage.getByLabel('이메일', { exact: true }).fill(email);
            await oldPage.getByLabel('비밀번호', { exact: true }).fill(originalPassword);
            await oldPage.getByRole('button', { name: '로그인', exact: true }).click();
            await expect(oldPage.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        });
        await operation("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [16]", async () => {
            await page.goto('/password/request');
            await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        });
        const { token } = await operation("account: 복구 코드 요청 [17]", async () => {
            await page.getByLabel('이메일', { exact: true }).fill(email);
            await page.getByRole('button', { name: '복구 코드 요청', exact: true }).click();
            await expect(page.getByText(/복구 요청을 접수했습니다/)).toBeVisible();
            const mail = await page.request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/reset-mail', { params: { email } });
            expect(mail.status()).toBe(200);
            const { token } = await mail.json();
            return { token };
        });
        await operation("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [18]", async () => {
            await page.goto('/password/reset');
            await expect(page.getByLabel('복구 코드', { exact: true })).toBeEnabled();
        });
        const { oldToken } = await operation("account: 비밀번호 재설정 [19]", async () => {
            await page.getByLabel('복구 코드', { exact: true }).fill(token);
            await page.getByLabel('새 비밀번호', { exact: true }).fill(newPassword);
            await page.getByRole('button', { name: '비밀번호 재설정', exact: true }).click();
            await expect.poll(async () => (await oldPage.request.get('http://127.0.0.1:24176/api/auth/me')).status()).toBe(401);
            await oldPage.reload();
            await expect(oldPage.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible();
            const oldToken = await (await oldPage.request.get('http://127.0.0.1:24176/api/auth/csrf')).json();
            return { oldToken };
        });
        await operation("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [20]", async () => {
            expect((await oldPage.request.post('http://127.0.0.1:24176/api/auth/login', {
                headers: { 'X-CSRF-TOKEN': oldToken.token }, form: { email, password: originalPassword },
            })).status()).toBe(401);
        });
        await prepare("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [21]", async () => {
            await login(page, email, newPassword);
        });
        await operation("account: /api/auth/password/reset [22]", async () => {
            const headers = { 'X-CSRF-TOKEN': await csrf(page) };
            expect((await page.request.post('/api/auth/password/reset', { headers, data: { token, password: originalPassword } })).status()).toBe(400);
            expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
        });
    }
    finally {
        await cleanup("account: 비밀번호 복구 뒤 이전 세션·비밀번호를 거부하고 새 비밀번호로 로그인 [23]", async () => {
            await oldContext.close();
        });
    }
});
