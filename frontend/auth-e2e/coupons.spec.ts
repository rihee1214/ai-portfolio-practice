import {test,expect} from '@playwright/test'
test.use({trace:'retain-on-failure'})
test('회원 쿠폰은 발급 응답 유실·중복 요청 뒤 복구되고 기존 조건과 만료 표시를 유지한다',async({page,browser},info)=>{
 const login=async(p:typeof page,email:string)=>{await p.goto('/login');await p.getByLabel('이메일',{exact:true}).fill(email);await p.getByLabel('비밀번호',{exact:true}).fill(' A1-private-passphrase-가나다 ');await p.getByRole('button',{name:'로그인',exact:true}).click();await expect(p.getByText('로그인되었습니다.',{exact:true})).toBeVisible()}
 await login(page,'p4b-browse-coupon-browser-'+info.project.name+'@example.com');await page.goto('/seller/coupons')
 const title='쿠폰 '+info.project.name
 await page.getByLabel('쿠폰 이름',{exact:true}).fill(title);await page.getByLabel('쿠폰 할인 값',{exact:true}).fill('1000')
 for(const label of ['발급 시작','사용 시작'])await page.getByLabel(label+' (한국 시간)',{exact:true}).fill('2020-01-01T09:00')
 for(const label of ['발급 종료','사용 종료'])await page.getByLabel(label+' (한국 시간)',{exact:true}).fill('2099-01-01T09:00')
 await page.getByRole('button',{name:'쿠폰 등록',exact:true}).click();await page.getByRole('button',{name:title+' · 초안',exact:true}).click()
 const csrf=await(await page.request.get('/api/auth/csrf')).json();let template=(await(await page.request.get('/api/seller/coupons')).json()).items.find((t:{title:string})=>t.title===title)
 const input={title,kind:template.terms.kind,value:1000,cap:null,minimum:0,issueStart:template.issueStart,issueEnd:template.issueEnd,startsAt:template.terms.startsAt,endsAt:template.terms.endsAt,totalLimit:100,state:'DRAFT',version:template.version}
 expect((await page.request.put('/api/seller/coupons/'+template.id,{headers:{[csrf.headerName]:csrf.token},data:input})).status()).toBe(200)
 await page.getByLabel('쿠폰 상태',{exact:true}).selectOption('ACTIVE');await page.getByRole('button',{name:'쿠폰 저장',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible()
 await page.getByRole('button',{name:'쿠폰 템플릿 다시 확인'}).click();await page.getByRole('button',{name:title+' · 초안',exact:true}).click();await page.getByLabel('쿠폰 상태',{exact:true}).selectOption('ACTIVE');await page.getByRole('button',{name:'쿠폰 저장',exact:true}).click();await expect(page.getByRole('button',{name:title+' · 발급 중',exact:true})).toBeVisible()
 const member=await browser.newContext({baseURL:'http://127.0.0.1:24176'});const guest=await browser.newContext({baseURL:'http://127.0.0.1:24176'})
 let failed=false;let primary:unknown=null
 try{
  console.log('coupon stage: anonymous boundary')
  expect((await guest.request.get('/api/account/coupons')).status()).toBe(401)
  const buyer=await member.newPage();await login(buyer,'coupon-member-'+info.project.name+'@example.com');await buyer.goto('/account/coupons')
console.log('coupon stage: owned loaded');  await expect(buyer.getByText('만료',{exact:true})).toBeVisible()
  const twins=buyer.getByRole('article').filter({has:buyer.getByRole('heading',{name:'같은 이름 '+info.project.name,exact:true})});await expect(twins).toHaveCount(2);const links=await twins.getByRole('link').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('href')));expect(new Set(links).size).toBe(2);console.log('coupon stage: twin reload');await buyer.reload();await expect(twins).toHaveCount(2)
console.log('coupon stage: csrf boundary');  expect((await buyer.request.post('/api/account/coupons/claim',{data:{templateId:template.id}})).status()).toBe(403)
await expect(buyer.getByRole('button',{name:'쿠폰 목록 다시 확인'})).toBeEnabled()
  const claimButton=buyer.getByRole('button',{name:title+' 받기',exact:true})
  for(let pageIndex=0;await claimButton.count()===0;pageIndex++){
   expect(pageIndex).toBeLessThan(100);const next=buyer.getByRole('button',{name:'다음 발급 쿠폰',exact:true});await expect(next).toBeEnabled();await next.click();await expect(buyer.getByRole('navigation',{name:'발급 쿠폰 페이지'}).getByText((pageIndex+2)+' 페이지',{exact:true})).toBeVisible();await expect(buyer.getByRole('button',{name:'쿠폰 목록 다시 확인'})).toBeEnabled()
  }
  await expect(claimButton).toBeVisible()
console.log('coupon stage: claim intercept');  let claims=0;await buyer.route('**/api/account/coupons/claim',async route=>{claims++;const r=await route.fetch();expect(r.status()).toBe(200);await route.abort()},{times:1})
  await buyer.getByRole('button',{name:title+' 받기',exact:true}).click();await expect(buyer.getByRole('alert')).toBeVisible();expect(claims).toBe(1)
console.log('coupon stage: claim recovery');  await buyer.getByRole('button',{name:'쿠폰 목록 다시 확인'}).click();await expect(buyer.getByRole('heading',{name:title,exact:true})).toHaveCount(1)
  const owned=(await(await buyer.request.get('/api/account/coupons')).json()).items.find((c:{title:string})=>c.title===title)
  expect(owned.terms.value).toBe(1000);const token=await(await buyer.request.get('/api/auth/csrf')).json()
console.log('coupon stage: claim idempotent');  const again=await buyer.request.post('/api/account/coupons/claim',{headers:{[token.headerName]:token.token},data:{templateId:template.id}});expect(again.status()).toBe(200);expect((await again.json()).id).toBe(owned.id)
console.log('coupon stage: stop issuance');  await page.getByRole('button',{name:title+' · 발급 중',exact:true}).click();await page.getByLabel('쿠폰 할인 값',{exact:true}).fill('500');await page.getByLabel('쿠폰 상태',{exact:true}).selectOption('STOPPED');await page.getByRole('button',{name:'쿠폰 저장',exact:true}).click();await expect(page.getByRole('button',{name:title+' · 발급 중지',exact:true})).toBeVisible()
console.log('coupon stage: snapshot reload');  await buyer.reload();const card=buyer.getByRole('article').filter({has:buyer.getByRole('heading',{name:title,exact:true})});await expect(card.getByText('1,000원 할인',{exact:true})).toBeVisible();await expect(card.getByText('사용 가능',{exact:true})).toBeVisible()
  template=await(await page.request.get('/api/seller/coupons/'+template.id)).json();expect(template.issuedCount).toBe(1)
console.log('coupon stage: logout');  await buyer.goto('/account');await buyer.getByRole('button',{name:'로그아웃',exact:true}).click();await expect(buyer.getByText('로그아웃했습니다.',{exact:true})).toBeVisible();await buyer.goto('/account/coupons');await expect(buyer.getByRole('link',{name:'로그인하고 쿠폰 확인'})).toBeVisible();await expect(buyer.getByRole('heading',{name:title,exact:true})).toHaveCount(0)
 }catch(error){failed=true;primary=error;console.error('coupon primary failure',error)}finally{const closed=await Promise.allSettled([member.close(),guest.close()]);if(!failed){const failure=closed.find(r=>r.status==='rejected');if(failure?.status==='rejected')primary=failure.reason}}
 if(primary)throw primary
})
