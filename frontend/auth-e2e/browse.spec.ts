import { operation } from './business-deadline';
import { test, expect } from './business-deadline';
test('공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다', async ({ page }) => {
    await operation("browse: 매대에서 만나는 일상 [1]", async () => {
        await page.goto('/');
        await expect(page.getByRole('heading', { name: '매대에서 만나는 일상' })).toBeVisible();
    });
    await operation("browse: 전체 상품 둘러보기 [2]", async () => {
        await page.getByRole('link', { name: '전체 상품 둘러보기' }).click();
        const search = page.getByLabel('상품 검색', { exact: true });
        const sort = page.getByLabel('정렬', { exact: true });
        await expect(search).toBeVisible();
        const layout = await page.evaluate(() => {
            const controls = ['browse-q', 'browse-category', 'browse-sort'].map(id => document.getElementById(id)!.getBoundingClientRect());
            return { width: innerWidth, fits: document.documentElement.scrollWidth <= innerWidth, tops: controls.map(rect => rect.top), lefts: controls.map(rect => rect.left) };
        });
        expect(layout.fits).toBe(true);
        if (layout.width > 520) {
            expect(layout.tops).toEqual([layout.tops[0], layout.tops[0], layout.tops[0]]);
            expect(layout.lefts[0]).toBeLessThan(layout.lefts[1]);
            expect(layout.lefts[1]).toBeLessThan(layout.lefts[2]);
        }
        const disclosure = page.locator('summary').filter({ hasText: '가격·재고 필터' });
        const minimum = page.getByLabel('최소 가격', { exact: true });
        await expect(minimum).toBeHidden();
        await disclosure.press('Enter');
        await expect(minimum).toBeVisible();
        await expect(page.getByLabel('재고 있는 상품만')).toBeVisible();
        await disclosure.press('Enter');
        await expect(minimum).toBeHidden();
        await search.fill('P05 browser');
        await sort.selectOption('price_asc');
        await page.getByRole('button', { name: '검색 적용', exact: true }).click();
        await expect(page.locator('.product-card')).toHaveCount(20);
        await expect(page.locator('.product-card').first()).toContainText('P05 browser 00');
        const grid = await page.locator('.product-grid').evaluate(element => ({
            columns: getComputedStyle(element).gridTemplateColumns.split(' ').length,
            width: innerWidth,
            fits: document.documentElement.scrollWidth <= innerWidth,
        }));
        expect(grid.columns).toBe(grid.width <= 760 ? 2 : grid.width < 1100 ? 3 : 4);
        expect(grid.fits).toBe(true);
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
        const titleLink = page.getByRole('link', { name: 'P05 browser 00', exact: true });
        const photoLink = page.getByRole('link', { name: 'P05 browser 00 사진', exact: true });
        await expect(photoLink).toHaveAttribute('href', (await titleLink.getAttribute('href'))!);
        await photoLink.click();
        await expect(page).toHaveURL(/\/products\/p05-browser-0\?q=P05\+browser&sort=price_asc&page=0$/);
        const purchase = page.getByRole('region', { name: '구매 정보', exact: true });
        await expect(purchase.getByRole('heading', { level: 1, name: 'P05 browser 00', exact: true })).toBeVisible();
        const placement = await page.evaluate(() => {
            const gallery = document.querySelector('.product-detail-gallery')!.getBoundingClientRect();
            const purchase = document.querySelector('.product-detail-purchase')!.getBoundingClientRect();
            const information = document.querySelector('.product-detail-information')!.getBoundingClientRect();
            return { width: innerWidth, fits: document.documentElement.scrollWidth <= innerWidth,
                horizontal: gallery.right <= purchase.left, vertical: gallery.bottom <= purchase.top, below: Math.max(gallery.bottom, purchase.bottom) <= information.top };
        });
        expect(placement.width <= 760 ? placement.vertical : placement.horizontal).toBe(true);
        expect(placement.fits && placement.below).toBe(true);
        await expect(page.getByText('품절', { exact: true })).toBeVisible();
        await expect(page.getByLabel('상품 옵션')).toContainText('1,000원');
        await expect(page.getByLabel('담을 수량', { exact: true })).toBeDisabled();
        const add = page.getByRole('button', { name: '장바구니 담기', exact: true });
        await expect(add).toBeDisabled();
        const savedCart = () => page.evaluate(() => localStorage.getItem('marketplace-guest-cart'));
        const before = await savedCart();
        await add.evaluate((button: HTMLButtonElement) => button.click());
        expect(await savedCart()).toBe(before);
        await expect(page.getByRole('link', { name: '상품 목록으로' })).toHaveAttribute('href', '/products?q=P05+browser&sort=price_asc&page=0');
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
        await expect(page.getByLabel('최소 가격', { exact: true })).toHaveValue('1100');
        await expect(page.getByLabel('최대 가격', { exact: true })).toHaveValue('1200');
        await expect(page.getByLabel('재고 있는 상품만')).toBeChecked();
    });
    await operation("browse: 공개 탐색은 실제 상품과 URL 조건·페이지·상세·매대를 연결한다 [8]", async () => {
        await page.getByLabel('상품 검색', { exact: true }).fill('없는상품-p05');
        await page.getByLabel('상품 검색', { exact: true }).press('Enter');
        await expect(page.getByText('검색 결과가 없습니다', { exact: true })).toBeVisible();
        await page.getByRole('link', { name: '조건 초기화', exact: true }).click();
        await expect(page).toHaveURL(/\/products$/);
        await expect(page.getByLabel('상품 검색', { exact: true })).toHaveValue('');
        await expect(page.getByLabel('정렬', { exact: true })).toHaveValue('newest');
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
