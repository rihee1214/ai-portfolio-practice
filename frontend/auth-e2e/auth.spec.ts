import { operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
import { randomUUID } from 'node:crypto';
const password = 'a3-browser-password';
async function login(page: Page, email: string) {
    return operation("auth: login", async () => {
        await page.goto('/login');
        await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(password);
        await page.getByLabel('비밀번호', { exact: true }).press('Enter');
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
test('실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃', async ({ page, browser, request }, info) => {
    const { email } = await operation("auth: 회원가입 [1]", async () => {
        const email = 'browser-' + randomUUID() + '@example.com';
        await page.goto('/signup');
        await expect(page.getByRole('button', { name: '회원가입', exact: true })).toBeEnabled();
        return { email };
    });
    await operation("auth: 회원가입 [2]", async () => {
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(password);
        await page.getByRole('button', { name: '회원가입', exact: true }).click();
        await expect(page.getByText(/가입 신청이 완료/)).toBeVisible();
    });
    const { token } = await operation("auth: 이메일 확인하기 [3]", async () => {
        await page.getByRole('link', { name: '이메일 확인하기' }).click();
        await expect(page.getByRole('heading', { name: '이메일 확인', exact: true })).toBeVisible();
        const mail = await request.get('http://127.0.0.1:' + process.env.A3_API_PORT + '/__test/mail', { params: { email } });
        expect(mail.ok()).toBeTruthy();
        const { token } = await mail.json();
        await expect(page.getByLabel('이메일 확인 코드')).toBeEnabled();
        return { token };
    });
    await operation("auth: 이메일 확인 [4]", async () => {
        await page.getByLabel('이메일 확인 코드').fill(token);
        await page.getByRole('button', { name: '이메일 확인', exact: true }).click();
        await expect(page.getByText('이메일 확인을 완료했습니다. 로그인할 수 있습니다.')).toBeVisible();
    });
    await prepare("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [5]", async () => {
        await login(page, email);
    });
    const { oldCsrf } = await operation("auth: /api/auth/logout [6]", async () => {
        const oldCsrf = await (await page.request.get('/api/auth/csrf')).json();
        expect((await page.request.post('/api/auth/logout', { data: {} })).status()).toBe(403);
        await page.reload();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        expect(await page.evaluate(() => localStorage.length)).toBe(0);
        expect(await page.evaluate(() => sessionStorage.length)).toBe(0);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        await page.screenshot({ scale: 'css', path: info.outputPath('account.png') });
        return { oldCsrf };
    });
    const { tab } = await prepare("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [7]", async () => {
        const tab = await page.context().newPage();
        return { tab };
    });
    await operation("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [8]", async () => {
        await tab.goto('/account');
        await expect(tab.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
    const { separate } = await prepare("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [9]", async () => {
        const separate = await browser.newContext();
        return { separate };
    });
    const { independent } = await prepare("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [10]", async () => {
        const independent = await separate.newPage();
        return { independent };
    });
    try {
        await operation("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [11]", async () => {
            await independent.goto('http://127.0.0.1:24176/account');
            await expect(independent.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible();
            const main = independent.getByRole('main');
            await expect(main.getByRole('link', { name: '로그인', exact: true })).toHaveAttribute('href', '/login');
            await expect(main.getByRole('link', { name: '회원가입', exact: true })).toHaveAttribute('href', '/signup');
            await expect(main.getByRole('navigation', { name: '회원 메뉴' })).toHaveCount(0);
        });
        await operation("auth: 로그인 [12]", async () => {
            await independent.goto('http://127.0.0.1:24176/login');
            await expect(independent.getByRole('button', { name: '로그인', exact: true })).toBeEnabled();
        });
        await operation("auth: 로그인 [13]", async () => {
            await independent.getByLabel('이메일', { exact: true }).fill(email);
            await independent.getByLabel('비밀번호', { exact: true }).fill(password);
            await independent.getByRole('button', { name: '로그인', exact: true }).click();
            await expect(independent.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        });
        await operation("auth: 로그아웃 [14]", async () => {
            await tab.getByRole('button', { name: '로그아웃', exact: true }).click();
            await expect(tab.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible();
            await page.bringToFront();
            await expect(page.getByText('로그인이 필요합니다.', { exact: true })).toBeVisible();
            await expect(page.getByText('회원 ID', { exact: true })).toHaveCount(0);
            await expect(page.getByRole('navigation', { name: '회원 메뉴' })).toHaveCount(0);
        });
        await operation("auth: /api/auth/logout [15]", async () => {
            expect((await page.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': oldCsrf.token }, data: {} })).status()).toBe(403);
            await independent.reload();
            await expect(independent.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        });
    }
    finally {
        await cleanup("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [16]", async () => {
            await separate.close();
        });
        await cleanup("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [17]", async () => {
            await tab.close();
        });
    }
    await prepare("auth: 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥과 로그아웃 [18]", async () => {
        await login(page, email);
    });
});
test('입력·미확인·토큰 오류·반복 실패를 화면에서 처리', async ({ page }) => {
    const { email } = await operation("auth: 회원가입 [19]", async () => {
        const email = 'missing-' + randomUUID() + '@example.com';
        await page.goto('/signup');
        await expect(page.getByRole('button', { name: '회원가입', exact: true })).toBeEnabled();
        return { email };
    });
    await operation("auth: 회원가입 [20]", async () => {
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill('short');
        await page.getByRole('button', { name: '회원가입', exact: true }).click();
        await expect(page.getByRole('alert')).toContainText('12~128');
    });
    await operation("auth: 입력·미확인·토큰 오류·반복 실패를 화면에서 처리 [21]", async () => {
        await page.goto('/verify-email');
        await expect(page.getByLabel('이메일 확인 코드')).toBeEnabled();
    });
    await operation("auth: 이메일 확인 [22]", async () => {
        await page.getByLabel('이메일 확인 코드').fill('invalid');
        await page.getByRole('button', { name: '이메일 확인', exact: true }).click();
        await expect(page.getByRole('alert')).toContainText('요청 내용을 확인');
    });
    await operation("auth: 입력·미확인·토큰 오류·반복 실패를 화면에서 처리 [23]", async () => {
        await page.goto('/login');
    });
    for (let index = 0; index < 6; index++) {
        await operation("auth: 로그인 [24]", async () => {
            await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeEnabled();
        });
        await operation("auth: 로그인 [25]", async () => {
            await page.getByLabel('이메일', { exact: true }).fill(email);
            await page.getByLabel('비밀번호', { exact: true }).fill(password);
            await page.getByRole('button', { name: '로그인', exact: true }).click();
            await expect(page.getByRole('alert')).toContainText(index < 5 ? '이메일·비밀번호' : '요청이 많습니다');
            await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('');
        });
    }
});
