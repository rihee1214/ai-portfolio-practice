import { operation, prepare } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
async function login(page: Page, project: string) {
    return operation("purchase: login", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill('wish-browser-' + project + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
test('회원 찜은 저장·새로고침·삭제와 로그아웃 후 비노출을 실제 DB로 확인한다', async ({ page }, info) => {
    await operation("purchase: 회원 찜은 저장·새로고침·삭제와 로그아웃 후 비노출을 실제 DB로 확인한다 [1]", async () => {
        await page.goto('/account/wishlist');
        await expect(page.getByText('찜한 상품은 로그인 후 확인할 수 있습니다.', { exact: true })).toBeVisible();
    });
    await prepare("purchase: 회원 찜은 저장·새로고침·삭제와 로그아웃 후 비노출을 실제 DB로 확인한다 [2]", async () => {
        await login(page, info.project.name);
    });
    await operation("purchase: 찜 저장 [3]", async () => {
        await page.goto('/products/p05-browser-1');
        await page.getByRole('button', { name: '찜 저장', exact: true }).click();
        await expect(page.getByText('찜한 상품에 저장했습니다.', { exact: true })).toBeVisible();
    });
    await operation("purchase: 찜 저장 [4]", async () => {
        await page.getByRole('button', { name: '찜 저장', exact: true }).click();
        await expect(page.getByText('찜한 상품에 저장했습니다.', { exact: true })).toBeVisible();
    });
    await operation("purchase: 찜한 상품 보기 [5]", async () => {
        await page.getByRole('link', { name: '찜한 상품 보기', exact: true }).click();
        await expect(page.getByRole('article')).toHaveCount(1);
        await page.reload();
        await expect(page.getByRole('link', { name: 'P05 browser 01', exact: true })).toBeVisible();
    });
    await operation("purchase: 찜 해제 [6]", async () => {
        await page.getByRole('button', { name: '찜 해제', exact: true }).click();
        await expect(page.getByText('찜한 상품이 없습니다.', { exact: true })).toBeVisible();
    });
    await operation("purchase: logout through private wishlist and browser-storage denial", async () => {
        await operation("purchase: 로그아웃 [7]", async () => {
            await page.goto('/account');
            await page.getByRole('button', { name: '로그아웃', exact: true }).click();
            await expect(page.getByText('로그아웃했습니다.', { exact: true })).toBeVisible();
        });
        await operation("purchase: 회원 찜은 저장·새로고침·삭제와 로그아웃 후 비노출을 실제 DB로 확인한다 [8]", async () => {
            await page.goto('/account/wishlist');
            await expect(page.getByText('찜한 상품은 로그인 후 확인할 수 있습니다.', { exact: true })).toBeVisible();
            expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
        });
    });
});
test('방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다', async ({ page }, info) => {
    await operation("purchase: 방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다 [9]", async () => {
        await page.goto('/checkout');
        await expect(page.getByText('장바구니가 비어 있어 주문서를 준비할 수 없습니다.', { exact: true })).toBeVisible();
    });
    for (const [slug, qty] of [['p05-browser-1', '2'], ['p05-c-second', '1']]) {
        await operation("purchase: 장바구니 담기 [10]", async () => {
            await page.goto('/products/' + slug);
            await expect(page.getByRole('button', { name: '장바구니 담기', exact: true })).toBeEnabled();
        });
        await operation("purchase: 장바구니 담기 [11]", async () => {
            await page.getByLabel('담을 수량', { exact: true }).fill(qty);
            await page.getByRole('button', { name: '장바구니 담기', exact: true }).click();
            await expect(page.getByText('방문자 장바구니를 변경했습니다.', { exact: true })).toBeVisible();
        });
    }
    await operation("purchase: 방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다 [12]", async () => {
        await page.goto('/cart');
        await expect(page.getByRole('region', { name: /장바구니 상품$/ })).toHaveCount(2);
    });
    await operation("purchase: 주문서 준비 [13]", async () => {
        await page.getByRole('link', { name: '주문서 준비', exact: true }).click();
        await expect(page.getByRole('region', { name: /준비 상품$/ })).toHaveCount(2);
        await expect(page.getByText('상품 금액 합계: 4,600원', { exact: true })).toBeVisible();
        await page.reload();
        await expect(page.getByText('상품 금액 합계: 4,600원', { exact: true })).toBeVisible();
    });
    await prepare("purchase: 방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다 [14]", async () => {
        await login(page, info.project.name);
    });
    await operation("purchase: 방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다 [15]", async () => {
        await page.goto('/checkout');
        await expect(page.getByText('회원 구매 준비', { exact: true })).toBeVisible();
        await expect(page.getByText('상품 금액 합계: 4,600원', { exact: true })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
    await operation("purchase: 장바구니 비우기 [16]", async () => {
        await page.goto('/cart');
        await page.getByRole('button', { name: '장바구니 비우기', exact: true }).click();
        await expect(page.getByText('장바구니가 비어 있습니다.', { exact: true })).toBeVisible();
        await expect(page.getByRole('link', { name: '주문서 준비', exact: true })).toHaveCount(0);
    });
    await operation("purchase: 방문자와 회원 주문서 준비는 두 매대로 묶고 빈 장바구니 직접 진입을 차단한다 [17]", async () => {
        await page.goto('/checkout');
        await expect(page.getByText('장바구니가 비어 있어 주문서를 준비할 수 없습니다.', { exact: true })).toBeVisible();
    });
});
