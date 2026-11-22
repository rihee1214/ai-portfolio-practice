import { operation, prepare, cleanup } from './business-deadline';
import { expect, test } from './business-deadline';
import { headers } from './purchase-helpers';
import { geometry } from './post-purchase-helpers';
import { PublicationDiagnostic } from './publication-diagnostic';
test('closed product report hides public detail and restores it through the recorded action', async ({ page, context, browser }, info) => {
    const suffix = 'questionmember-' + info.project.name;
    const slug = 'p4b-browse-order-' + suffix + '-a';
    const { staff } = await prepare("moderation: closed product report hides public detail and restores it through the recorded action [1]", async () => {
        const staff = await browser.newContext({ baseURL: 'http://127.0.0.1:24176' });
        return { staff };
    });
    try {
        await operation("moderation: /api/auth/login [2]", async () => {
            for (const response of await Promise.all([[context, 'order-' + suffix], [staff, 'c-admin-' + info.project.name]].map(async (pair) => {
                const [client, email] = pair as [
                    typeof context,
                    string
                ];
                return client.request.post('/api/auth/login', { headers: await headers(client), form: { email: email + '@example.com', password: ' A1-private-passphrase-가나다 ' } });
            })))
                expect(response.status()).toBe(200);
        });
        const { reportId, operator } = await operation("moderation: /api/products/ [3]", async () => {
            const report = await context.request.post('/api/products/' + slug + '/reports', { headers: await headers(context), data: { key: crypto.randomUUID(), reason: '공개 상품 신고' } });
            expect(report.status()).toBe(200);
            const { reportId } = await report.json(), operator = (await (await staff.request.get('/api/auth/me')).json()).id;
            return { reportId, operator };
        });
        for (const [version, cmd] of [{ action: 'ASSIGN', operator }, { action: 'START' }, { action: 'CLOSE', decision: 'VIOLATION', reason: '판단' }].entries()) {
            await operation("moderation: /api/admin/content/reports/ [4]", async () => {
                const result = await staff.request.post('/api/admin/content/reports/' + reportId + '/commands', { headers: await headers(staff), data: { ...cmd, key: crypto.randomUUID(), version } });
                expect(result.status()).toBe(200);
            });
        }
        const { admin } = await prepare("moderation: closed product report hides public detail and restores it through the recorded action [5]", async () => {
            const admin = await staff.newPage();
            return { admin };
        });
        const { title } = await operation("moderation: 사건 열기 [6]", async () => {
            await admin.goto('/admin/reports?state=CLOSED');
            await admin.locator('article').filter({ hasText: reportId }).getByRole('button', { name: '사건 열기' }).click();
            await admin.getByRole('link', { name: '콘텐츠 조치 확인' }).click();
            await page.goto('/products/' + slug);
            const title = await page.getByRole('region', { name: '구매 정보' }).getByRole('heading', { level: 1 }).innerText();
            const reason = '위반확인'.repeat(75);
            await admin.getByLabel('조치 사유').fill(reason);
            await admin.getByRole('button', { name: '운영 비공개' }).focus();
            await admin.keyboard.press('Enter');
            await expect(admin.getByRole('list', { name: '조치 이력' }).getByText(reason)).toBeVisible();
            await geometry(admin, info, 'moderation-history');
            await page.reload();
            await expect(page.getByRole('button', { name: '상품 다시 불러오기' })).toBeVisible();
            await expect(page.getByRole('heading', { name: title, exact: true })).toHaveCount(0);
            return { title };
        });
        await operation("moderation: 운영 숨김 해제 [7]", async () => {
            await admin.getByLabel('조치 사유').fill('복원 확인');
            await admin.getByRole('button', { name: '운영 숨김 해제' }).click();
            await expect(admin.getByRole('list', { name: '조치 이력' }).getByText('복원 확인')).toBeVisible();
            await page.reload();
            await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
        });
        const { notice, literal, direct } = await operation("moderation: publish notice through actual public body", async () => {
            const diagnostic = new PublicationDiagnostic(info.project.name);
            const { notice, literal, saved } = await operation("moderation: 새 공지 작성 [8]", async () => {
                await admin.goto('/admin/publications');
                await admin.getByRole('button', { name: '새 공지 작성' }).click();
                const notice = '배송 안내 ' + info.project.name;
                const literal = '<script>공개 게시 본문</script>' + '긴안내문'.repeat(85);
                await admin.getByLabel('제목', { exact: true }).fill(notice);
                await admin.getByLabel('내용', { exact: true }).fill(literal);
                await admin.getByLabel('게시 상태').selectOption('PUBLISHED');
                await admin.getByLabel('변경 사유').fill('공지 게시');
                await admin.getByRole('button', { name: '게시 설정 저장' }).focus();
                const saved = diagnostic.captureSave(admin, notice, literal);
                await admin.keyboard.press('Enter');
                await expect(admin.getByRole('button', { name: notice + ' · PUBLISHED' })).toBeVisible();
                return { notice, literal, saved };
            });
            const { direct } = await operation("moderation: closed product report hides public detail and restores it through the recorded action [9]", async () => {
                const direct = await diagnostic.publicList(page, staff, saved, () => page.goto('/notices'), async (post, mark) => {
                    const link = page.getByRole('link', { name: notice, exact: true });
                    const href = await link.getAttribute('href');
                    mark('expected-list-link', { expectedPostId: post.id, expectedVersion: post.version, actualHref: href });
                    expect(href).toBe('/notices/' + post.id);
                    await link.click();
                    mark('list-link-clicked', { expectedPostId: post.id, actualUrl: page.url() });
                    await expect(page.getByText(literal, { exact: true })).toBeVisible();
                    mark('exact-detail-body', { expectedPostId: post.id, actualUrl: page.url(), literalBodyMatched: true });
                    await geometry(page, info, 'site-notice-long-body');
                    return page.url();
                });
                return { direct };
            });
            return { notice, literal, direct };
        });
        await operation("moderation: withdraw notice through direct public denial", async () => {
            await operation("moderation: 게시 설정 저장 [10]", async () => {
                await admin.getByRole('button', { name: notice + ' · PUBLISHED' }).click();
                await admin.getByLabel('게시 상태').selectOption('DRAFT');
                await admin.getByLabel('변경 사유').fill('기간 종료 공지 중단');
                await admin.getByRole('button', { name: '게시 설정 저장' }).click();
                await expect(admin.getByRole('button', { name: notice + ' · DRAFT' })).toBeVisible();
            });
            await operation("moderation: closed product report hides public detail and restores it through the recorded action [11]", async () => {
                await page.goto(direct);
                await expect(page.getByText('공개된 게시물을 찾을 수 없습니다.')).toBeVisible();
                await expect(page.getByText(literal)).toHaveCount(0);
            });
        });
    }
    finally {
        await cleanup("moderation: closed product report hides public detail and restores it through the recorded action [12]", async () => {
            await staff.close();
        });
    }
});
