import { operation } from './business-deadline';
import { expect, test, type Page } from './business-deadline';
export async function openManagedStore(page: Page, slug: string) {
    return test.step('운영 매대 목록을 페이지별로 탐색한다', async () => {
        const refresh = page.getByRole('button', { name: '목록 새로고침', exact: true });
        async function load(index: number, action: () => Promise<unknown>) {
            return operation("managed-store: load", async () => {
                const [response] = await Promise.all([
                    page.waitForResponse(response => {
                        const url = new URL(response.url());
                        return response.request().method() === 'GET' && url.pathname === '/api/admin/accounts/stores'
                            && url.searchParams.get('page') === String(index);
                    }),
                    action(),
                ]);
                expect(response.status()).toBe(200);
                await expect(page.getByText(`${index + 1}페이지`, { exact: true })).toBeVisible();
                await expect(refresh).toBeEnabled();
            });
        }
        const { card } = await operation("managed-store: openManagedStore [1]", async () => {
            await load(0, () => page.goto('/admin/stores'));
            const card = page.getByRole('article', { name: slug, exact: true });
            return { card };
        });
        for (let index = 0; index <= 10000; index++) {
            if (await operation('managed-store: inspect current-page target', async () => card.count())) {
                return operation("managed-store: openManagedStore [2]", async () => {
                    await expect(card).toBeVisible();
                    console.info(`MANAGED_STORE found on page ${index + 1}`);
                    return { card, pageIndex: index };
                });
            }
            const { next } = await operation("managed-store: 다음 목록 [3]", async () => {
                const next = page.getByRole('button', { name: '다음 목록', exact: true });
                await expect(next, `매대 ${slug}가 현재/이후 목록에 있어야 한다`).toBeEnabled();
                return { next };
            });
            await operation("managed-store: openManagedStore [4]", async () => {
                await load(index + 1, () => next.click());
            });
        }
        throw new Error('Store not found within the supported page range');
    });
}
