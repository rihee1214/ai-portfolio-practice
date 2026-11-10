import {expect,test} from '@playwright/test'
import {headers} from './purchase-helpers'
import {geometry} from './post-purchase-helpers'

test('closed product report hides public detail and restores it through the recorded action',async({page,context,browser},info)=>{
  const suffix='questionmember-'+info.project.name
  const slug='p4b-browse-order-'+suffix+'-a'
  const staff=await browser.newContext({baseURL:'http://127.0.0.1:24176'})
  try{
    for(const response of await Promise.all([[context,'order-'+suffix],[staff,'c-admin-'+info.project.name]].map(async pair=>{
      const [client,email]=pair as [typeof context,string]
      return client.request.post('/api/auth/login',{headers:await headers(client),form:{email:email+'@example.com',password:' A1-private-passphrase-가나다 '}})
    })))expect(response.status()).toBe(200)
    const report=await context.request.post('/api/products/'+slug+'/reports',{headers:await headers(context),data:{key:crypto.randomUUID(),reason:'공개 상품 신고'}})
    expect(report.status()).toBe(200)
    const {reportId}=await report.json(),operator=(await (await staff.request.get('/api/auth/me')).json()).id
    for(const [version,cmd] of [{action:'ASSIGN',operator},{action:'START'},{action:'CLOSE',decision:'VIOLATION',reason:'판단'}].entries()){
      const result=await staff.request.post('/api/admin/content/reports/'+reportId+'/commands',{headers:await headers(staff),data:{...cmd,key:crypto.randomUUID(),version}})
      expect(result.status()).toBe(200)
    }
    const admin=await staff.newPage();await admin.goto('/admin/reports?state=CLOSED')
    await admin.locator('article').filter({hasText:reportId}).getByRole('button',{name:'사건 열기'}).click()
    await admin.getByRole('link',{name:'콘텐츠 조치 확인'}).click()
    await page.goto('/products/'+slug)
    const title=await page.locator('section:has(> h1:text-is("상품 상세")) > h2').first().innerText()
    const reason='위반확인'.repeat(75)
    await admin.getByLabel('조치 사유').fill(reason)
    await admin.getByRole('button',{name:'운영 비공개'}).focus()
    await admin.keyboard.press('Enter')
    await expect(admin.getByRole('list',{name:'조치 이력'}).getByText(reason)).toBeVisible()
    await geometry(admin,info,'moderation-history')
    await page.reload()
    await expect(page.getByRole('button',{name:'상품 다시 불러오기'})).toBeVisible()
    await expect(page.getByRole('heading',{name:title,exact:true})).toHaveCount(0)
    await admin.getByLabel('조치 사유').fill('복원 확인')
    await admin.getByRole('button',{name:'운영 숨김 해제'}).click()
    await expect(admin.getByRole('list',{name:'조치 이력'}).getByText('복원 확인')).toBeVisible()
    await page.reload();await expect(page.getByRole('heading',{name:title,exact:true})).toBeVisible()
  }finally{await staff.close()}
})
