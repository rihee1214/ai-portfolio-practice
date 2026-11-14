import { operation, prepare } from './business-deadline';
import { test, expect, type Page } from './business-deadline';
async function fits(page: Page) { return operation("input-flow: fits", async () => { expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); }); }
for (const width of [375, 768, 1440])
    test(`입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다`, async ({ page }, info) => {
        const { product } = await operation("input-flow: P05-D input [1]", async () => {
            await page.setViewportSize({ width, height: 900 });
            await page.goto('/products');
            await page.getByLabel('상품 검색', { exact: true }).fill('P05-D input');
            await page.getByLabel('상품 검색', { exact: true }).press('Enter');
            const product = page.getByRole('link', { name: 'P05-D input', exact: true });
            await expect(page).toHaveURL(/q=P05-D\+input/);
            await expect(product).toHaveAttribute('href', /q=P05-D\+input/);
            await expect(product).toBeVisible();
            await product.focus();
            await expect(product).toBeFocused();
            return { product };
        });
        const { option, choices, target } = await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [2]", async () => {
            await product.press('Enter');
            const option = page.getByLabel('상품 옵션', { exact: true });
            await expect(option).toBeVisible();
            const choices = await option.locator('option').evaluateAll(nodes => nodes.map(node => ({ id: (node as HTMLOptionElement).value, label: node.textContent })));
            expect(choices).toHaveLength(2);
            const target = choices.findIndex(choice => choice.label?.includes('큰 것'));
            expect(target).toBeGreaterThanOrEqual(0);
            await option.focus();
            return { option, choices, target };
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [3]", async () => {
            await option.press('Home');
        });
        for (let step = 0; step < target; step++) {
            await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [4]", async () => {
                await option.press('ArrowDown');
            });
        }
        const { quantity } = await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [5]", async () => {
            await option.press('Enter');
            await expect(option).toHaveValue(choices[target].id);
            await expect(page.getByText('선택 가격: 2,500원', { exact: true })).toBeVisible();
            const quantity = page.getByLabel('담을 수량', { exact: true });
            return { quantity };
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [6]", async () => {
            await quantity.fill('0');
            await quantity.press('Enter');
            await expect(quantity).toBeFocused();
            await expect(quantity).toHaveAttribute('aria-invalid', 'true');
        });
        await operation("input-flow: 장바구니 담기 [7]", async () => {
            await quantity.fill('2');
            await quantity.press('Tab');
            await expect(page.getByRole('button', { name: '장바구니 담기', exact: true })).toBeFocused();
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [8]", async () => {
            await page.keyboard.press('Enter');
            await expect(page.getByText('방문자 장바구니를 변경했습니다.', { exact: true })).toBeVisible();
            await fits(page);
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [9]", async () => {
            await page.goto('/cart');
            await expect(page.getByText('상품 금액: 5,000원', { exact: true })).toBeVisible();
            await fits(page);
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [10]", async () => {
            await page.goto('/login');
            await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        });
        await operation("input-flow: 로그인 [11]", async () => {
            await page.getByLabel('이메일', { exact: true }).fill(`input-${info.project.name}-${width}@example.com`);
            await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
            await page.getByRole('button', { name: '로그인', exact: true }).click();
            await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
        });
        await operation("input-flow: 배송지 추가 [12]", async () => {
            await page.goto('/account/addresses');
            await expect(page.getByLabel('이름표', { exact: true })).toBeEnabled();
            await page.getByRole('button', { name: '배송지 추가', exact: true }).focus();
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [13]", async () => {
            await page.keyboard.press('Enter');
            await expect(page.getByLabel('이름표', { exact: true })).toBeFocused();
        });
        for (const [label, value] of Object.entries({ 이름표: '키보드 집', 수령인: '구매자', 전화번호: '01012345678', 우편번호: '12345', 주소: '서울시 테스트 주소', '상세 주소': '101호' })) {
            await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [14]", async () => {
                await expect(page.getByLabel(label, { exact: true })).toBeFocused();
                await page.keyboard.type(value);
            });
            await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [15]", async () => {
                await page.keyboard.press('Tab');
            });
        }
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [16]", async () => {
            await expect(page.getByLabel('기본 배송지', { exact: true })).toBeFocused();
        });
        await operation("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [17]", async () => {
            await page.keyboard.press('Space');
            await page.keyboard.press('Tab');
        });
        let failed = false;
        await prepare("input-flow: 입력 흐름 ${width}px는 키보드 상품·옵션·수량·주소와 실패 복구를 연결한다 [18]", async () => {
            await page.route('**/api/account/addresses', async (route) => {
                if (route.request().method() === 'POST' && !failed) {
                    failed = true;
                    await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
                }
                else
                    await route.continue();
            });
        });
        await operation("input-flow: 배송지 추가 [19]", async () => {
            await page.keyboard.press('Enter');
            await expect(page.getByRole('alert')).toBeVisible();
            await expect(page.getByLabel('주소', { exact: true })).toHaveValue('서울시 테스트 주소');
            await fits(page);
            await page.getByRole('button', { name: '배송지 추가', exact: true }).focus();
        });
        await operation("input-flow: 키보드 집 [20]", async () => {
            await page.keyboard.press('Enter');
            await expect(page.getByText('저장했습니다.', { exact: true })).toBeVisible();
            await page.reload();
            await expect(page.getByRole('article', { name: '키보드 집', exact: true })).toContainText('서울시 테스트 주소');
            await fits(page);
        });
    });
test('상품 요청 대기와 오류를 안내하고 키보드 재시도로 실제 상품을 복원한다', async ({ page }) => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await prepare("input-flow: 상품 요청 대기와 오류를 안내하고 키보드 재시도로 실제 상품을 복원한다 [21]", async () => {
        await page.route('**/api/browse/products/p05-d-input', async (route) => { await held; await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }); });
    });
    await operation("input-flow: 상품 요청 대기와 오류를 안내하고 키보드 재시도로 실제 상품을 복원한다 [22] + recovery", async () => {
        await operation("input-flow: 상품 요청 대기와 오류를 안내하고 키보드 재시도로 실제 상품을 복원한다 [22]", async () => {
            await page.goto('/products/p05-d-input');
            await expect(page.getByText('상품을 불러오고 있습니다.', { exact: true })).toBeVisible();
            await expect(page.getByLabel('상품 옵션')).toHaveCount(0);
            release();
            await expect(page.getByRole('alert')).toBeVisible();
        });
        await operation("input-flow: 상품 요청 대기와 오류를 안내하고 키보드 재시도로 실제 상품을 복원한다 [23]", async () => {
            await page.unroute('**/api/browse/products/p05-d-input');
        });
        await operation("input-flow: 상품 다시 불러오기 [24]", async () => {
            await page.getByRole('button', { name: '상품 다시 불러오기', exact: true }).focus();
            await page.keyboard.press('Enter');
            await expect(page.getByRole('heading', { name: 'P05-D input', exact: true })).toBeVisible();
        });
    });
});
