import {expect,test} from '@playwright/test'
import {headers} from './purchase-helpers'
import {geometry} from './post-purchase-helpers'

test('member reports a question and reads the decision after the assigned investigator reopens the case',async({page,context,browser},info)=>{
  const project=info.project.name,suffix='questionmember-'+project
  const slug='p4b-browse-order-'+suffix+'-b',password=' A1-private-passphrase-가나다 '
  const staff=await browser.newContext({baseURL:'http://127.0.0.1:24176'})
  try{
    const logins=await Promise.all([[context,'order-'+suffix+'@example.com'] as const,[staff,'c-admin-'+project+'@example.com'] as const].map(async([c,email])=>
      c.request.post('/api/auth/login',{headers:await headers(c),form:{email,password}})))
    for(const r of logins)expect(r.status()).toBe(200)
    const question=await context.request.post('/api/products/'+slug+'/questions',{headers:await headers(context),
      data:{key:crypto.randomUUID(),visibility:'PUBLIC',body:'담당자가 확인할 상품 문의'}})
    expect(question.status()).toBe(200)
    await page.goto('/products/'+slug)
    await page.getByRole('button',{name:'상품 문의 열기'}).click()
    const form=page.getByRole('region',{name:'상품 문의 신고',exact:true})
    await form.getByRole('button',{name:'상품 문의 신고'}).focus()
    await page.keyboard.press('Enter')
    const reason='문의 내용 신고 '.repeat(32).trim()
    await form.getByLabel('신고 사유').fill(reason)
    const posted=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/reports/content/QUESTION/'))
    await form.getByRole('button',{name:'신고 접수'}).click()
    const res=await posted;expect(res.status()).toBe(200)
    const {reportId}=await res.json()
    const admin=await staff.newPage()
    await admin.goto('/admin/reports?state=RECEIVED')
    const row=admin.locator('article').filter({hasText:reportId})
    await expect(admin.getByText(reason,{exact:true})).toHaveCount(0)
    await row.getByRole('button',{name:'내가 담당'}).click()
    await row.getByRole('button',{name:'사건 열기'}).click()
    const panel=admin.getByRole('region',{name:'신고 조사'})
    await expect(panel.getByText(reason,{exact:true})).toBeVisible()
    await panel.getByRole('button',{name:'현재 원문 확인'}).click()
    await expect(panel.getByText('담당자가 확인할 상품 문의',{exact:true})).toBeVisible()
    await geometry(admin,info,'report-investigation')
    async function command(label:string){
      const saved=admin.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/api/admin/content/reports/'+reportId+'/commands')
      await panel.getByRole('button',{name:label}).click()
      expect((await saved).status()).toBe(200)
    }
    await command('조사 시작')
    await admin.getByLabel('상태 필터').selectOption('INVESTIGATING')
    await row.getByRole('button',{name:'사건 열기'}).click()
    await panel.getByLabel('판단 사유').fill('위반 확인 근거')
    await command('사건 종결')
    await admin.getByLabel('상태 필터').selectOption('CLOSED')
    await row.getByRole('button',{name:'사건 열기'}).click()
    await page.goto('/account/reports')
    await expect(page.getByText(reportId+' · 종결 위반 확인',{exact:true})).toBeVisible()
    await panel.getByLabel('판단 사유').fill('새 근거에 따른 재개')
    await command('사건 재개')
    await expect(row).toHaveCount(0)
    await page.reload()
    await expect(page.getByText(reportId+' · 조사 중',{exact:true})).toBeVisible()
  }finally{await staff.close()}
})
