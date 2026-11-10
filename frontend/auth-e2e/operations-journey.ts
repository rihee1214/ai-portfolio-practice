import {expect,type Browser,type BrowserContext,type Page,type TestInfo} from '@playwright/test'
import type {OrderDetail} from '../src/order/OrderQueryStore'
import {headers} from './purchase-helpers'
import {geometry} from './post-purchase-helpers'

export async function claimsForOrder(customer:BrowserContext,number:string){
  return Promise.all(['cancellations','returns','exchanges'].map(async kind=>{
    const result=await customer.request.get('/api/orders/'+number+'/'+kind)
    expect(result.status()).toBe(200);return result.json()
  }))
}
// Continue from the actual confirmed item, uploaded asset and seller reply in the review journey.
export async function checkOrderSupportAndModeration(page:Page,customer:BrowserContext,seller:BrowserContext,browser:Browser,info:TestInfo,
  number:string,product:string,item:string,reviewId:string,asset:string,snapshot:OrderDetail,confirmation:unknown,claims:unknown){
  const store=snapshot.stores.find(row=>row.items.some(i=>i.id===item))
  expect(store).toBeDefined()
  const slug=product.split('/').at(-1)!
  const detail='/api/reviews/'+reviewId, productPath='/api/products/'+slug
  const send=async(client:BrowserContext,path:string,data:object)=>{
    const r=await client.request.post(path,{headers:await headers(client),data})
    expect(r.status(),path).toBe(200);return r.json()
  }
  const question=await send(customer,productPath+'/questions',{key:crypto.randomUUID(),body:'주문 상품 확인',visibility:'PRIVATE'})
  const answer=await send(seller,'/api/questions/'+question.id+'/answer',{key:crypto.randomUUID(),expectedVersion:1,body:'판매자가 확인한 상품입니다.'})
  expect(answer.id).toBeTruthy()
  const ticket=await send(customer,'/api/support/cases',{key:crypto.randomUUID(),body:'같은 주문의 후속 문의',orderNumber:number,sellerOrderId:store!.sellerOrderId})
  await send(seller,'/api/support/cases/'+ticket.id+'/messages?reader=SELLER',{key:crypto.randomUUID(),expectedVersion:1,body:'판매자 주문 답변'})
  const orderCase=await (await customer.request.get('/api/support/cases/'+ticket.id)).json()
  expect(orderCase).toMatchObject({id:ticket.id,orderNumber:number,sellerOrderId:store!.sellerOrderId})
  expect(orderCase.messages.at(-1).body).toBe('판매자 주문 답변')

  await page.goto(product)
  const productQuestions=page.getByRole('region',{name:'상품 문의',exact:true})
  await productQuestions.getByRole('button',{name:'상품 문의 열기'}).click()
  await expect(productQuestions.getByText(/판매자 답변 · 판매자가 확인한 상품입니다/)).toBeVisible()
  const reviews=page.getByRole('region',{name:'상품 후기'})
  const commentText=reviews.getByRole('paragraph').filter({hasText:/^운영 확인 전 댓글$/})
  await reviews.getByLabel('댓글 본문').fill('운영 확인 전 댓글')
  const added=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===detail+'/responses')
  await reviews.getByRole('button',{name:'댓글 등록'}).click()
  const addedResponse=await added;expect(addedResponse.status()).toBe(200)
  const commentId=(await addedResponse.json()).id;expect(commentId).toBeTruthy()
  await expect(commentText).toBeVisible()
  const form=reviews.getByRole('region',{name:'후기 신고',exact:true})
  await form.getByRole('button',{name:'후기 신고'}).click()
  await form.getByLabel('신고 사유').fill('이 후기와 첨부를 확인해 주세요.')
  await form.getByLabel('관련 이미지').selectOption(asset.split('/').at(-1)!)
  const submitted=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/reports/content/REVIEW/'+reviewId+'/'+reviewId+'/1'))
  await form.getByRole('button',{name:'신고 접수'}).click()
  const response=await submitted;expect(response.status()).toBe(200)
  const report=(await response.json()).reportId
  const staff=await browser.newContext({baseURL:'http://127.0.0.1:24176'})
  try{
    expect((await staff.request.post('/api/auth/login',{headers:await headers(staff),form:{email:'c-admin-'+info.project.name+'@example.com',password:' A1-private-passphrase-가나다 '}})).status()).toBe(200)
    const base='/api/admin/content/reports/'+report
    const operator=(await (await staff.request.get('/api/auth/me')).json()).id
    await send(staff,base+'/commands',{key:crypto.randomUUID(),version:0,action:'ASSIGN',operator})
    await send(staff,base+'/commands',{key:crypto.randomUUID(),version:1,action:'START'})
    expect((await (await staff.request.get(base)).json()).targetId).toBe(reviewId)
    await send(staff,base+'/commands',{key:crypto.randomUUID(),version:2,action:'CLOSE',decision:'VIOLATION',reason:'위반 확인'})
    const admin=await staff.newPage()
    await admin.goto('/admin/reports?state=CLOSED')
    await admin.locator('article').filter({hasText:report}).getByRole('button',{name:'사건 열기'}).click()
    await admin.getByRole('link',{name:'콘텐츠 조치 확인'}).click()
    const panel=admin.getByRole('region',{name:'대상 조치'})
    const history=panel.getByRole('list',{name:'조치 이력'})
    const receipt=()=>admin.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===base+'/moderation')
    await panel.getByLabel('조치 사유').fill('후기 비공개')
    const hiding=receipt()
    await panel.getByRole('button',{name:'운영 비공개'}).click()
    const hiddenResponse=await hiding;expect(hiddenResponse.status()).toBe(200)
    const hideId=(await hiddenResponse.json()).id;expect(hideId).toBeTruthy()
    await expect(history.getByText('후기 비공개',{exact:true})).toBeVisible()
    const hidden=await (await staff.request.get(base+'/moderation')).json()
    expect(hidden.targetId).toBe(reviewId);expect(hidden.activeHide).toBe(hideId)
    await page.reload();await expect(reviews.getByText('등록된 후기가 없습니다.')).toBeVisible()
    expect((await customer.request.get(asset)).status()).toBe(404)
    expect((await customer.request.get(detail)).status()).toBe(404)
    expect(await (await customer.request.get(productPath+'/reviews')).json()).toMatchObject({reviews:[],count:0,ratingSum:0})
    await panel.getByLabel('조치 사유').fill('원 후기 복원 확인')
    const restoring=receipt()
    await panel.getByRole('button',{name:'운영 숨김 해제'}).click()
    const restoredResponse=await restoring;expect(restoredResponse.status()).toBe(200)
    const restoreId=(await restoredResponse.json()).id;expect(restoreId).toBeTruthy()
    await expect(history.getByText('원 후기 복원 확인',{exact:true})).toBeVisible()
    const restored=await (await staff.request.get(base+'/moderation')).json()
    expect(restored.history.map((e:{hidden:boolean})=>e.hidden)).toEqual([true,false])
    expect(restored.history[0].id).toBe(hidden.activeHide)
    expect(restored.history[1].id).toBe(restoreId)
    await page.reload()
    await expect(reviews.getByText('평균 4.0점 · 후기 1개')).toBeVisible()
    await expect(commentText).toBeVisible()
    const current=await (await customer.request.get(detail)).json()
    expect(current.responses).toEqual(expect.arrayContaining([expect.objectContaining({id:commentId,body:'운영 확인 전 댓글'})]))
    await expect(reviews.getByText('구매해 주셔서 감사합니다.',{exact:true})).toBeVisible()
    expect(await reviews.getByAltText('후기 이미지 1').getAttribute('src')).toBe(asset)
    expect((await customer.request.get(asset)).status()).toBe(200)
    await geometry(page,info,'integrated-restored-review')
    expect(await (await customer.request.get('/api/orders/'+number)).json()).toEqual(snapshot)
    expect(await (await customer.request.get('/api/orders/'+number+'/confirmations')).json()).toEqual(confirmation)
    expect(await claimsForOrder(customer,number)).toEqual(claims)
  }finally{await staff.close()}
}
