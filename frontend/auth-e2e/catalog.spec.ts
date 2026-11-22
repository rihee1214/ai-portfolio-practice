import { operation, prepare, cleanup } from './business-deadline';
import { test, expect, type Page, type Locator } from './business-deadline';
import { resolve } from 'node:path';
async function login(page: Page, email: string) {
    return operation("catalog: login", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
        await page.getByLabel('이메일', { exact: true }).fill(email);
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByRole('button', { name: '로그인', exact: true }).click();
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
}
async function findOnPages(page: Page, target: Locator, label: string) {
    const pager = page.getByRole('navigation', { name: label, exact: true });
    for (let count = 0; count < 100; count++) {
        if (await operation('catalog: locate target on current page', async () => target.count()))
            break;
        const next = pager.getByRole('button', { name: '다음', exact: true });
        if (await operation('catalog: inspect next-page availability', async () => next.isDisabled()))
            break;
        await operation("catalog: findOnPages [1]", async () => {
            const previous = await pager.locator('span').innerText();
            await next.click();
            await expect(pager.locator('span')).not.toHaveText(previous);
        });
    }
    await operation("catalog: findOnPages [2]", async () => {
        await expect(target).toHaveCount(1);
    });
}
test('카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다', async ({ page, browser }, info) => {
    page.setDefaultTimeout(10000);
    const suffix = info.project.name;
    const category = 'P04 browser ' + suffix;
    const title = 'P04 상품 ' + suffix;
    const slug = 'p04-browser-product-' + suffix;
    await prepare("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [3]", async () => {
        await login(page, 'c-admin-' + suffix + '@example.com');
    });
    const { csrf } = await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [4]", async () => {
        await page.goto('/admin/catalog');
        await expect(page.getByLabel('카테고리 이름')).toBeEnabled();
        const csrf = await (await page.request.get('/api/auth/csrf')).json();
        return { csrf };
    });
    for (let index = 0; index < 21; index++) {
        await operation("catalog: P05-B paging  [5]", async () => {
            const created = await page.request.post('/api/admin/content/categories', {
                headers: { [csrf.headerName]: csrf.token },
                data: { name: 'P05-B paging ' + suffix + ' ' + index, active: true, version: 0 },
            });
            expect(created.status()).toBe(201);
        });
    }
    await operation("catalog: 목록 다시 확인 [6]", async () => {
        await page.getByRole('button', { name: '목록 다시 확인', exact: true }).click();
        await expect(page.getByLabel('카테고리 이름')).toBeEnabled();
        const secondPage = await (await page.request.get('/api/admin/content/categories?page=1')).json();
        expect(secondPage.items.length).toBeGreaterThan(0);
        const existing = secondPage.items[0];
        await findOnPages(page, page.getByRole('button', { name: existing.name + (existing.active ? ' · 활성' : ' · 비활성'), exact: true }), '카테고리 목록 페이지');
        await expect(page.getByRole('navigation', { name: '카테고리 목록 페이지', exact: true }).locator('span')).not.toHaveText('1 페이지');
    });
    await operation("catalog: 카테고리 등록 [7]", async () => {
        await page.getByLabel('카테고리 이름').fill(category);
        await page.getByRole('button', { name: '카테고리 등록', exact: true }).click();
        await expect(page.getByText('저장했습니다.', { exact: true })).toBeVisible();
        await findOnPages(page, page.getByRole('button', { name: category + ' · 활성', exact: true }), '카테고리 목록 페이지');
        await expect(page.getByRole('button', { name: category + ' · 활성', exact: true })).toBeVisible();
    });
    const { sellerContext } = await prepare("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [8]", async () => {
        const sellerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:24176', viewport: page.viewportSize() ?? undefined });
        return { sellerContext };
    });
    const { seller } = await prepare("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [9]", async () => {
        const seller = await sellerContext.newPage();
        return { seller };
    });
    seller.setDefaultTimeout(10000);
    try {
        await prepare("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [10]", async () => {
            await login(seller, 'p4-seller-' + suffix + '@example.com');
        });
        const { descriptionImage } = await operation("catalog: 상품 등록 [11]", async () => {
            await seller.goto('/seller/products');
            await seller.getByLabel('상품 주소').fill(slug);
            await seller.getByLabel('상품명', { exact: true }).fill(title);
            await seller.getByRole('textbox', { name: '상품 설명' }).fill('브라우저에서 등록한 상품\n둘째 줄 설명');
            await findOnPages(seller, seller.getByRole('option', { name: category, exact: true }), '상품 카테고리 페이지');
            await seller.getByLabel('카테고리', { exact: true }).selectOption({ label: category });
            await seller.getByRole('button', { name: '이미지 삽입', exact: true }).click();
            await seller.getByLabel('이미지 파일', { exact: true }).setInputFiles(resolve('../backend/src/test/resources/media/alpha.webp'));
            await seller.getByLabel('이미지 대체 텍스트', { exact: true }).fill('설명에 넣은 사진');
            await seller.getByRole('button', { name: '설명에 이미지 추가', exact: true }).click();
            await seller.getByRole('button', { name: '상품 등록', exact: true }).click();
            await seller.getByRole('button', { name: title + ' · 초안', exact: true }).click();
            const editor = seller.getByRole('textbox', { name: '상품 설명' });
            await expect(editor).toHaveText(/브라우저에서 등록한 상품\s*둘째 줄 설명/, { useInnerText: true });
            const preview = editor.getByRole('img', { name: '설명에 넣은 사진', exact: true });
            await expect(preview).toHaveAttribute('src', /^\/api\/media\/owner\/[^/]+\/preview$/);
            await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(4);
            const descriptionImage = (await preview.getAttribute('src'))!.split('/')[4];
            expect((await seller.request.get('/api/media/' + descriptionImage + '/content')).status()).toBe(404);
            return { descriptionImage };
        });
        await operation("catalog: 상품 저장 [12]", async () => {
            await seller.getByLabel('상품 상태').selectOption('ACTIVE');
            await seller.getByRole('button', { name: '상품 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: title + ' · 공개 등록', exact: true })).toBeVisible();
            expect((await seller.request.get('/api/products/' + slug)).status()).toBe(200);
            const detail = await (await page.request.get('/api/browse/products/' + slug)).json();
            expect(detail.descriptionBlocks).toContainEqual(expect.objectContaining({ kind: 'image', assetId: descriptionImage, alt: '설명에 넣은 사진' }));
            expect((await page.request.get('/api/media/' + descriptionImage + '/content')).status()).toBe(200);
            expect(await (await page.request.get('/api/media/public/products/' + slug)).json()).toEqual([]);
        });
        const { stockButton } = await operation("catalog: 기본 SKU · 0원 · 판매 중지 [13]", async () => {
            await seller.getByRole('button', { name: '기본 SKU · 0원 · 판매 중지', exact: true }).click();
            await seller.getByLabel('SKU 가격 (원)', { exact: true }).fill('1200');
            await seller.getByLabel('SKU 판매 활성', { exact: true }).check();
            await seller.getByRole('button', { name: 'SKU 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: '기본 SKU · 1200원 · 판매 활성', exact: true })).toBeVisible();
            expect((await (await seller.request.get('/api/products/' + slug + '/skus')).json()).items[0].price).toBe(1200);
            const stockButton = seller.getByRole('button', { name: /sku-.* · 보유 0 · 예약 0 · 가용 0/ }).first();
            return { stockButton };
        });
        const { productId } = await operation("catalog: 재고 조정 저장 [14]", async () => {
            await stockButton.click();
            await seller.getByLabel('보유 수량', { exact: true }).fill('12');
            await seller.getByLabel('재고 조정 사유', { exact: true }).fill('초기 입고');
            await seller.getByRole('button', { name: '재고 조정 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: /sku-.* · 보유 12 · 예약 0 · 가용 12/ })).toBeVisible();
            await expect(seller.getByRole('region', { name: '재고 조정 이력' })).toContainText('초기 입고');
            const productId = (await (await seller.request.get('/api/seller/products')).json()).items.find((row: {
                slug: string;
            }) => row.slug === slug).id;
            return { productId };
        });
        await operation("catalog: 상품 재고 조회 [15]", async () => {
            await page.getByLabel('재고 조회 상품 식별자').fill(productId);
            await page.getByRole('button', { name: '상품 재고 조회', exact: true }).click();
            await page.getByRole('button', { name: /sku-.* · 보유 12 · 예약 0 · 가용 12/ }).click();
            await page.getByLabel('보유 수량', { exact: true }).fill('9');
            await page.getByLabel('재고 조정 사유', { exact: true }).fill('운영 실사');
            await page.getByRole('button', { name: '재고 조정 저장', exact: true }).click();
            await expect(page.getByRole('button', { name: /sku-.* · 보유 9 · 예약 0 · 가용 9/ })).toBeVisible();
        });
        await operation("catalog: 재고 조정 저장 [16]", async () => {
            await seller.getByLabel('보유 수량', { exact: true }).fill('15');
            await seller.getByLabel('재고 조정 사유', { exact: true }).fill('낡은 화면');
            await seller.getByRole('button', { name: '재고 조정 저장', exact: true }).click();
            await expect(seller.getByRole('alert')).toBeVisible();
            await seller.getByRole('button', { name: '재고 다시 확인', exact: true }).click();
        });
        await operation("catalog: 재고 조정 이력 [17]", async () => {
            await seller.getByRole('button', { name: /sku-.* · 보유 9 · 예약 0 · 가용 9/ }).click();
            await expect(seller.getByLabel('보유 수량', { exact: true })).toHaveValue('9');
            await expect(seller.getByRole('region', { name: '재고 조정 이력' })).toContainText('운영 실사');
            await seller.getByRole('region', { name: '재고 관리', exact: true }).screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04d-stock-' + suffix + '.png') });
            await page.getByRole('region', { name: '재고 관리', exact: true }).screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04d-admin-stock-' + suffix + '.png') });
        });
        await operation("catalog: 새 옵션 SKU [18]", async () => {
            await seller.getByRole('button', { name: '새 옵션 SKU', exact: true }).click();
            await seller.getByLabel('옵션 이름 1', { exact: true }).fill('색상');
            await seller.getByLabel('옵션 값 1', { exact: true }).fill('빨강');
            await seller.getByLabel('옵션 이름 2', { exact: true }).fill('크기');
            await seller.getByLabel('옵션 값 2', { exact: true }).fill('M');
            await seller.getByLabel('SKU 가격 (원)', { exact: true }).fill('2500');
            await seller.getByLabel('SKU 판매 활성', { exact: true }).check();
            await seller.getByRole('button', { name: '옵션 SKU 등록', exact: true }).click();
            await expect(seller.getByRole('alert')).toHaveText('옵션 등록 후 기본 SKU 복귀가 불가함을 확인해 주세요.');
        });
        await operation("catalog: 옵션 SKU 등록 [19]", async () => {
            await seller.getByLabel('기본 SKU 복귀 불가와 조합 변경 불가를 확인합니다').check();
            await seller.getByRole('button', { name: '옵션 SKU 등록', exact: true }).click();
            await expect(seller.getByRole('button', { name: '기본 SKU · 1200원 · 판매 중지', exact: true })).toBeVisible();
        });
        await operation("catalog: 색상: 빨강 / 크기: M · 2500원 · 판매 활성 [20]", async () => {
            await seller.getByRole('button', { name: '색상: 빨강 / 크기: M · 2500원 · 판매 활성', exact: true }).click();
            await seller.getByLabel('SKU 가격 (원)', { exact: true }).fill('3500');
            await seller.getByRole('button', { name: 'SKU 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: '색상: 빨강 / 크기: M · 3500원 · 판매 활성', exact: true })).toBeVisible();
            await seller.reload();
        });
        await operation("catalog: 색상: 빨강 / 크기: M · 3500원 · 판매 활성 [21]", async () => {
            await seller.getByRole('button', { name: title + ' · 공개 등록', exact: true }).click();
            await seller.getByRole('button', { name: '색상: 빨강 / 크기: M · 3500원 · 판매 활성', exact: true }).click();
            await expect(seller.getByLabel('SKU 가격 (원)', { exact: true })).toHaveValue('3500');
            const publicSkus = (await (await seller.request.get('/api/products/' + slug + '/skus')).json()).items;
            expect(publicSkus).toHaveLength(1);
            expect(publicSkus[0].options).toEqual([{ name: '색상', value: '빨강' }, { name: '크기', value: 'M' }]);
        });
        await operation("catalog: SKU 저장 [22]", async () => {
            await seller.getByLabel('SKU 판매 활성', { exact: true }).uncheck();
            await seller.getByRole('button', { name: 'SKU 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: '색상: 빨강 / 크기: M · 3500원 · 판매 중지', exact: true })).toBeVisible();
            expect((await (await seller.request.get('/api/products/' + slug + '/skus')).json()).items).toEqual([]);
        });
        await operation("catalog: SKU 저장 [23]", async () => {
            await seller.getByLabel('SKU 판매 활성', { exact: true }).check();
            await seller.getByRole('button', { name: 'SKU 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: '색상: 빨강 / 크기: M · 3500원 · 판매 활성', exact: true })).toBeVisible();
        });
        for (const width of [375, 768, 1440]) {
            await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [24]", async () => {
                await seller.setViewportSize({ width, height: 900 });
                expect(await seller.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
            });
        }
        const { oldUrl } = await operation("catalog: 이미지 1 업로드 [25]", async () => {
            await seller.setViewportSize({ width: suffix === 'mobile' ? 375 : 1440, height: 1000 });
            await seller.getByLabel('이미지 파일 1', { exact: true }).setInputFiles(resolve('../backend/src/test/resources/media/alpha.webp'));
            await seller.getByLabel('이미지 설명 1', { exact: true }).fill('WebP 상품 사진');
            await seller.getByRole('button', { name: '이미지 1 업로드', exact: true }).click();
            const preview = seller.getByRole('img', { name: 'WebP 상품 사진', exact: true });
            await expect(preview).toBeVisible();
            await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(4);
            const oldUrl = (await preview.getAttribute('src'))!;
            await seller.getByRole('region', { name: '이미지 관리' }).screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04c-images-' + suffix + '.png') });
            return { oldUrl };
        });
        await operation("catalog: 이미지 1 교체 [26]", async () => {
            await seller.getByLabel('이미지 파일 1', { exact: true }).setInputFiles(resolve('../backend/src/test/resources/media/lossy.webp'));
            await seller.getByLabel('이미지 설명 1', { exact: true }).fill('교체 상품 사진');
            await seller.getByRole('button', { name: '이미지 1 교체', exact: true }).click();
            await expect(seller.getByRole('img', { name: '교체 상품 사진', exact: true })).toBeVisible();
            expect((await seller.request.get(oldUrl)).status()).toBe(404);
        });
        await operation("catalog: 이미지 1 삭제 [27]", async () => {
            await seller.getByLabel('이미지 1 삭제 확인', { exact: true }).check();
            await seller.getByRole('button', { name: '이미지 1 삭제', exact: true }).click();
            await expect(seller.getByRole('button', { name: '이미지 1 업로드', exact: true })).toBeVisible();
            await seller.getByRole('region', { name: 'SKU 관리' }).screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04b-sku-' + suffix + '.png') });
        });
        await operation("catalog: 카테고리 저장 [28]", async () => {
            await page.getByRole('button', { name: category + ' · 활성', exact: true }).click();
            await page.getByLabel('활성 카테고리').uncheck();
            await page.getByRole('button', { name: '카테고리 저장', exact: true }).click();
            await expect(page.getByRole('button', { name: category + ' · 비활성', exact: true })).toBeVisible();
            expect((await seller.request.get('/api/products/' + slug)).status()).toBe(404);
            expect((await seller.request.get('/api/products/' + slug + '/skus')).status()).toBe(404);
        });
        await operation("catalog: 카테고리 저장 [29]", async () => {
            await page.getByLabel('활성 카테고리').check();
            await page.getByRole('button', { name: '카테고리 저장', exact: true }).click();
            await expect(page.getByText('저장했습니다.', { exact: true })).toBeVisible();
            await findOnPages(page, page.getByRole('button', { name: category + ' · 활성', exact: true }), '카테고리 목록 페이지');
            await expect(page.getByRole('button', { name: category + ' · 활성', exact: true })).toBeVisible();
            expect((await seller.request.get('/api/products/' + slug)).status()).toBe(200);
        });
        await operation("catalog: 상품 저장 [30]", async () => {
            await seller.getByRole('button', { name: title + ' · 공개 등록', exact: true }).click();
            await seller.getByLabel('상품 상태').selectOption('STOPPED');
            await seller.getByRole('button', { name: '상품 저장', exact: true }).click();
            await expect(seller.getByRole('button', { name: title + ' · 판매 중지', exact: true })).toBeVisible();
            expect((await seller.request.get('/api/products/' + slug)).status()).toBe(404);
            expect((await seller.request.get('/api/products/' + slug + '/skus')).status()).toBe(404);
        });
        await operation("catalog: 상품 저장 [31]", async () => {
            await seller.getByLabel('상품 상태').selectOption('ARCHIVED');
            await seller.getByRole('button', { name: '상품 저장', exact: true }).click();
            await expect(seller.getByRole('alert')).toHaveText('보관 후 복구할 수 없음을 확인해 주세요.');
        });
        await operation("catalog: 상품 저장 [32]", async () => {
            await seller.getByLabel('보관 후 복구할 수 없음을 확인합니다').check();
            await seller.getByRole('button', { name: '상품 저장', exact: true }).click();
            await expect(seller.getByText('보관된 상품입니다. 기록은 유지됩니다.')).toBeVisible();
            await expect(seller.getByRole('button', { name: '상품 저장', exact: true })).toBeDisabled();
            await seller.reload();
        });
        await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [33]", async () => {
            await seller.getByRole('button', { name: title + ' · 보관', exact: true }).click();
            await expect(seller.getByText('보관된 상품입니다. 기록은 유지됩니다.')).toBeVisible();
        });
        for (const width of [375, 768, 1440]) {
            await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [34]", async () => {
                await seller.setViewportSize({ width, height: 900 });
                expect(await seller.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
            });
        }
        await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [35]", async () => {
            await seller.setViewportSize({ width: suffix === 'mobile' ? 375 : 1440, height: 1000 });
            await seller.locator('.store-card').screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04-product-' + suffix + '.png') });
            await page.locator('.store-card').screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04-category-' + suffix + '.png') });
        });
    }
    catch (error) {
        await operation("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [36]", async () => {
            await seller.screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04-seller-failure-' + suffix + '.png') }).catch(() => { });
            throw error;
        });
    }
    finally {
        await cleanup("catalog: 카테고리 운영과 상품 등록·공개·중지·보관을 실제 DB로 연결한다 [37]", async () => {
            await sellerContext.close().catch(() => { });
        });
    }
});
