import { expect, test, type Page } from '@playwright/test'

// Exercise the real list controls; a random store UUID need not be on page one.
export async function openManagedStore(page: Page, slug: string) {
  return test.step('운영 매대 목록을 페이지별로 탐색한다', async () => {
    const refresh = page.getByRole('button', { name: '목록 새로고침', exact: true })
    async function load(index: number, action: () => Promise<unknown>) {
      const [response] = await Promise.all([
        page.waitForResponse(response => {
          const url = new URL(response.url())
          return response.request().method() === 'GET' && url.pathname === '/api/admin/accounts/stores'
            && url.searchParams.get('page') === String(index)
        }),
        action(),
      ])
      expect(response.status()).toBe(200)
      await expect(page.getByText(`${index + 1}페이지`, { exact: true })).toBeVisible()
      await expect(refresh).toBeEnabled()
    }
    await load(0, () => page.goto('/admin/stores'))
    const card = page.getByRole('article', { name: slug, exact: true })
    for (let index = 0; index <= 10000; index++) {
      if (await card.count()) {
        await expect(card).toBeVisible()
        console.info(`MANAGED_STORE found on page ${index + 1}`)
        return { card, pageIndex: index }
      }
      const next = page.getByRole('button', { name: '다음 목록', exact: true })
      await expect(next, `매대 ${slug}가 현재/이후 목록에 있어야 한다`).toBeEnabled()
      await load(index + 1, () => next.click())
    }
    throw new Error('Store not found within the supported page range')
  })
}
