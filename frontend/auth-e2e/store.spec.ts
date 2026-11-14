import { operation, prepare, cleanup } from './business-deadline';
import { test, expect } from './business-deadline';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
test('판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침', async ({ page, browser }, info) => {
    await operation("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [1]", async () => {
        await page.goto('/login');
        await expect(page.getByLabel('이메일', { exact: true })).toBeEnabled();
    });
    await operation("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [2]", async () => {
        await page.getByLabel('이메일', { exact: true }).fill('c-seller-' + info.project.name + '@example.com');
        await page.getByLabel('비밀번호', { exact: true }).fill(' A1-private-passphrase-가나다 ');
        await page.getByLabel('비밀번호', { exact: true }).press('Enter');
        await expect(page.getByText('로그인되었습니다.', { exact: true })).toBeVisible();
    });
    const { slug } = await operation("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [3]", async () => {
        await page.goto('/seller/store');
        const slug = 'store-' + randomUUID().slice(0, 8);
        await expect(page.getByLabel('매대 URL 주소')).toBeEnabled();
        return { slug };
    });
    await operation("store: 매대 만들기 [4]", async () => {
        await page.getByLabel('매대 URL 주소').fill(slug);
        await page.getByLabel('매대 이름', { exact: true }).fill('가상 매대');
        await page.getByLabel('매대 소개', { exact: true }).fill('모의 매대 소개');
        await page.getByLabel('공개용 주소').fill('가상의 공개 주소');
        await page.getByRole('button', { name: '매대 만들기' }).focus();
        await page.keyboard.press('Enter');
        await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible();
        await page.reload();
        await expect(page.getByLabel('매대 URL 주소')).toHaveValue(slug);
        await expect(page.getByLabel('매대 URL 주소')).toHaveAttribute('readonly', '');
    });
    const { visitor } = await prepare("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [5]", async () => {
        const visitor = await browser.newContext({ viewport: page.viewportSize() ?? { width: 1280, height: 720 } });
        return { visitor };
    });
    const { publicPage } = await prepare("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [6]", async () => {
        const publicPage = await visitor.newPage();
        return { publicPage };
    });
    try {
        await operation("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [7]", async () => {
            await publicPage.goto('/stores/' + slug);
            await expect(publicPage.getByRole('alert')).toContainText('찾을 수 없습니다');
        });
        await operation("store: 매대 저장 [8]", async () => {
            await page.getByLabel('매대 공지').fill('모의 공지');
            await page.getByLabel('대표 이미지 키').fill('test-image');
            await page.getByLabel('이미지 대체 텍스트').fill('가상 그림');
            await page.getByLabel('매대 공개', { exact: true }).check();
            await page.getByRole('button', { name: '매대 저장', exact: true }).click();
            await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible();
        });
        const { imageUrl } = await operation("store: 이미지 1 업로드 [9]", async () => {
            await page.getByLabel('이미지 파일 1', { exact: true }).setInputFiles(resolve('../backend/src/test/resources/media/alpha.webp'));
            await page.getByLabel('이미지 설명 1', { exact: true }).fill('실제 매대 이미지');
            await page.getByRole('button', { name: '이미지 1 업로드', exact: true }).click();
            await expect(page.getByRole('img', { name: '실제 매대 이미지' })).toBeVisible();
            const imageUrl = (await page.getByRole('img', { name: '실제 매대 이미지' }).getAttribute('src'))!;
            await publicPage.reload();
            await expect.poll(() => publicPage.getByRole('img', { name: '실제 매대 이미지' }).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(4);
            await publicPage.screenshot({ scale: 'css', path: resolve(process.env.A3_EVIDENCE!, 'p04c-public-store-' + info.project.name + '.png') });
            await expect(publicPage.getByRole('heading', { name: '가상 매대' })).toBeVisible();
            await expect(publicPage.getByText('모의 공지', { exact: true })).toBeVisible();
            expect(await publicPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
            await publicPage.screenshot({ scale: 'css', path: info.outputPath('public-store.png') });
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
            await page.screenshot({ scale: 'css', path: info.outputPath('store-editor.png') });
            return { imageUrl };
        });
        await operation("store: 매대 저장 [10]", async () => {
            await page.getByLabel('매대 공개', { exact: true }).uncheck();
            await page.getByRole('button', { name: '매대 저장', exact: true }).click();
            await expect(page.getByText('매대 정보를 저장했습니다.')).toBeVisible();
            await publicPage.reload();
            await expect(publicPage.getByRole('alert')).toContainText('찾을 수 없습니다');
            expect((await publicPage.request.get(imageUrl)).status()).toBe(404);
            expect((await page.request.get('/api/account/profile')).status()).toBe(200);
        });
    }
    finally {
        await cleanup("store: 판매자 매대 생성·수정·공개·비공개와 공개 페이지 새로고침 [11]", async () => {
            await visitor.close();
        });
    }
});
