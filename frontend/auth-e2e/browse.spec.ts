import { operation } from './business-deadline';
import { test, expect } from './business-deadline';
test('공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다', async ({ page }) => {
    await operation("browse: 매대에서 만나는 일상 [1]", async () => {
        await page.goto('/');
        await expect(page.getByRole('heading', { name: '매대에서 만나는 일상' })).toBeVisible();
    });
    await operation("browse: 전체 상품 둘러보기 [2]", async () => {
        await page.getByRole('link', { name: '전체 상품 둘러보기' }).click();
        await page.getByLabel('상품 검색', { exact: true }).fill('P05 browser');
        await page.getByLabel('정렬', { exact: true }).selectOption('price_asc');
        await page.getByRole('button', { name: '검색 적용', exact: true }).click();
        await expect(page.locator('.product-card')).toHaveCount(20);
        await expect(page.locator('.product-card').first()).toContainText('P05 browser 00');
    });
    await operation("browse: 다음 상품 [3]", async () => {
        await page.getByRole('button', { name: '다음 상품', exact: true }).click();
        await expect(page).toHaveURL(/page=1/);
        await expect(page.locator('.product-card')).toHaveCount(2);
        await page.reload();
        await expect(page.getByLabel('상품 검색', { exact: true })).toHaveValue('P05 browser');
        await expect(page.locator('.product-card')).toHaveCount(2);
        await page.goBack();
        await expect(page.locator('.product-card')).toHaveCount(20);
    });
    await operation("browse: P05 browser 00 [4]", async () => {
        await page.getByRole('link', { name: 'P05 browser 00', exact: true }).click();
        await expect(page.getByRole('heading', { name: 'P05 browser 00', exact: true })).toBeVisible();
        await expect(page.getByText('품절', { exact: true })).toBeVisible();
        await expect(page.getByLabel('상품 옵션')).toContainText('1,000원');
    });
    await operation("browse: P04B [5]", async () => {
        await page.getByRole('link', { name: 'P04B', exact: true }).click();
        await expect(page.getByRole('heading', { name: '매대 소개' })).toBeVisible();
        await expect(page.locator('.product-card')).toHaveCount(20);
        await expect(page.locator('.product-card').first()).toContainText('P05 browser 21');
    });
    await operation("browse: 다음 상품 [6]", async () => {
        await page.getByRole('button', { name: '다음 상품', exact: true }).click();
        await expect(page.locator('.product-card')).toHaveCount(2);
        await expect(page.locator('.product-card').filter({ hasText: 'P05 browser 00' })).toHaveCount(1);
    });
    await operation("browse: 공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다 [7]", async () => {
        await page.goto('/products?q=P05+browser&sort=price_asc&inStock=true&minPrice=1100&maxPrice=1200&page=0');
        await expect(page.locator('.product-card')).toHaveCount(2);
        await expect(page.locator('.product-card').first()).toContainText('P05 browser 01');
    });
    await operation("browse: 공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다 [8]", async () => {
        await page.getByLabel('상품 검색', { exact: true }).fill('없는상품-p05');
        await page.getByLabel('상품 검색', { exact: true }).press('Enter');
        await expect(page.getByText('조건에 맞는 상품이 없습니다.')).toBeVisible();
    });
    await operation("browse: 공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다 [9]", async () => {
        await page.goto('/products?page=-1');
        await expect(page.getByRole('alert')).toContainText('검색 주소가 올바르지 않습니다.');
    });
    await operation("browse: 공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다 [10]", async () => {
        await page.goto('/products/nonexistent-product');
        await expect(page.getByRole('alert')).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    });
});
