import { operation } from './business-deadline';
import { expect, test } from './business-deadline';
import { headers } from './purchase-helpers';
import { geometry } from './post-purchase-helpers';
test('accounts operator finds an exact email and verifies suspension and restoration through the selected history', async ({ page, context }, info) => {
    const { email } = await operation("operations-complete: /api/auth/login [1]", async () => {
        const email = 'order-reviewmember-' + info.project.name + '@example.com';
        const login = await context.request.post('/api/auth/login', { headers: await headers(context),
            form: { email: 'p3-admin-' + info.project.name + '@example.com', password: ' A1-private-passphrase-가나다 ' } });
        expect(login.status()).toBe(200);
        return { email };
    });
    const { id } = await operation("operations-complete: 회원 검색 [2]", async () => {
        await page.goto('/admin/users');
        const search = page.getByLabel('회원 ID 또는 정확한 이메일');
        await search.fill(email);
        const found = page.waitForResponse(r => r.url().endsWith('/api/admin/accounts/search') && r.request().postDataJSON()?.term === email);
        await page.getByRole('button', { name: '회원 검색' }).click();
        const result = await found;
        expect(result.status()).toBe(200);
        const { members } = await result.json();
        expect(members).toHaveLength(1);
        expect(Object.keys(members[0]).sort()).toEqual(['id', 'state']);
        const id = members[0].id;
        return { id };
    });
    const { detail, reason } = await operation("operations-complete: 회원 상세 [3]", async () => {
        await page.getByRole('button', { name: id + ' 상세' }).click();
        const detail = page.getByRole('region', { name: '회원 상세' });
        await expect(detail.getByText(id + ' · 활성', { exact: true })).toBeVisible();
        const reason = '운영확인'.repeat(55);
        return { detail, reason };
    });
    await operation("operations-complete: 로그인 정지 [4]", async () => {
        await detail.getByLabel('계정 조치 사유').fill(reason);
        await detail.getByRole('button', { name: '로그인 정지' }).click();
        await expect(detail.getByText(reason, { exact: true })).toBeVisible();
        await geometry(page, info, 'account-action-history');
    });
    await operation("operations-complete: 로그인 복원 [5]", async () => {
        await detail.getByLabel('계정 조치 사유').fill('회원 상태 복원 확인');
        await detail.getByRole('button', { name: '로그인 복원' }).focus();
        await page.keyboard.press('Enter');
        await expect(detail.getByText(id + ' · 활성', { exact: true })).toBeVisible();
        await expect(detail.getByText('회원 상태 복원 확인', { exact: true })).toBeVisible();
    });
});
