import {it,expect,vi} from 'vitest'
import {quoteValid,formatMoney,QuoteStore} from '../../src/promotion/QuoteStore'
import {AuthStore} from '../../src/auth/AuthStore'
const sku='00000000-0000-0000-0000-000000000001',store='00000000-0000-0000-0000-000000000002'
const fixture=(quantity=1)=>{const price=9007199254740993n,gross=String(price*BigInt(quantity));return {policyVersion:'P07-C-1',evaluatedAt:'2026-01-01T00:00:00Z',fingerprint:'a'.repeat(64),total:gross,stores:[{storeId:store,slug:'store-one',name:'매대',version:0,subtotal:gross,productDiscount:'0',afterPromotion:gross,coupon:null,couponDiscount:'0',shippingBase:'0',shippingPromotion:null,shippingDiscount:'0',total:gross,lines:[{skuId:sku,productId:sku,productVersion:0,skuVersion:0,title:'상품',options:[],quantity,unitPrice:String(price),gross,promotion:null,promotionDiscount:'0',couponDiscount:'0',net:gross,units:Array.from({length:quantity},(_,ordinal)=>({ordinal,gross:String(price),promotionDiscount:'0',couponDiscount:'0',net:String(price)}))}]}]}}
const json=(data:unknown)=>new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}})
it('validates exact decimal amounts and every allocation total',()=>{
 const q=fixture();expect(quoteValid(q)).toBe(true);expect(formatMoney(q.total)).toBe('9,007,199,254,740,993원')
 for(const total of ['-1','01','1.5',9007199254740992,'9223372036854775808'])expect(quoteValid({...q,total})).toBe(false)
 const broken=fixture();broken.stores[0].lines[0].units[0].net='0';expect(quoteValid(broken)).toBe(false)
})
it('discards stale quotes and clears selections when the session changes',async()=>{
 const replies:((r:Response)=>void)[]=[];const bodies:unknown[]=[]
 vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{if(path==='/api/auth/me')return json({id:sku,roles:['BUYER']});if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'csrf'});bodies.push(JSON.parse(String(init?.body)));return new Promise<Response>(resolve=>replies.push(resolve))}))
 const auth=new AuthStore();await auth.refresh();const client=new QuoteStore(auth);const stop=client.connect();client.configure([{skuId:sku,quantity:1}]);await vi.waitFor(()=>expect(replies).toHaveLength(1));client.configure([{skuId:sku,quantity:2}]);await vi.waitFor(()=>expect(replies).toHaveLength(2));replies[1](json(fixture(2)));await vi.waitFor(()=>expect(client.getSnapshot().quote?.total).toBe(String(9007199254740993n*2n)));replies[0](json(fixture()));await Promise.resolve();expect(client.getSnapshot().quote?.stores[0].lines[0].quantity).toBe(2)
 expect(bodies[0]).toEqual({lines:[{skuId:sku,quantity:1}],coupons:[]});auth.rejectSession({message:'ended'});expect(client.getSnapshot().quote).toBeNull();expect(client.getSnapshot().selected).toEqual({});stop()
})
it('rejects a response for different selected quantities',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id:sku,roles:['BUYER']}):path==='/api/auth/csrf'?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):json(fixture(2))))
 const auth=new AuthStore();await auth.refresh();const client=new QuoteStore(auth);const stop=client.connect();client.configure([{skuId:sku,quantity:1}]);await vi.waitFor(()=>expect(client.getSnapshot().error).toBeTruthy());expect(client.getSnapshot().quote).toBeNull();stop()
})
import {render,screen} from '@testing-library/react'
import {QuotePanel} from '../../src/promotion/QuotePanel'
it('keeps the coupon select name independent of its options',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id:sku,roles:['BUYER']}):path==='/api/auth/csrf'?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):path.startsWith('/api/account/coupons')?json({items:[],page:0,hasMore:false}):json(fixture())))
 const auth=new AuthStore();await auth.refresh();render(<QuotePanel auth={auth} lines={[{skuId:sku,quantity:1}]}/>);const select=await screen.findByLabelText('적용 쿠폰 — store-one',{exact:true});expect(select).toBeEnabled();expect(select.id).toBe('quote-coupon-'+store);expect(document.querySelector('label[for="'+select.id+'"]')?.textContent).toBe('적용 쿠폰 — store-one')
})
it('clears an actual coupon selection on unauthorized response and restores after login',async()=>{
 let unauthorized=false,delay=false;let late:((r:Response)=>void)|undefined
 vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
  if(path==='/api/auth/me')return json({id:sku,roles:['BUYER']});if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'csrf'});if(unauthorized)return new Response('{}',{status:401,headers:{'Content-Type':'application/json'}})
  const q=fixture();const input=JSON.parse(String(init?.body));if(input.coupons.length)Object.assign(q.stores[0],{coupon:{id:sku,version:0,templateVersion:1,title:'선택 쿠폰'}});if(delay){delay=false;return new Promise<Response>(resolve=>{late=resolve})}return json(q)
 }))
 const auth=new AuthStore();await auth.refresh();const client=new QuoteStore(auth),stop=client.connect();client.configure([{skuId:sku,quantity:1}]);await vi.waitFor(()=>expect(client.getSnapshot().quote).not.toBeNull());client.selectCoupon(store,sku);await vi.waitFor(()=>expect(client.getSnapshot().quote?.stores[0].coupon?.id).toBe(sku));expect(client.getSnapshot().selected).toEqual({[store]:sku});delay=true;const old=client.refresh();await vi.waitFor(()=>expect(late).toBeDefined());unauthorized=true;await client.refresh();expect(auth.getSnapshot().member).toBeNull();expect(client.getSnapshot().selected).toEqual({});expect(client.getSnapshot().quote).toBeNull();late!(json(fixture()));await old;expect(client.getSnapshot().quote).toBeNull();expect(client.getSnapshot().selected).toEqual({});unauthorized=false;await auth.refresh();await vi.waitFor(()=>expect(client.getSnapshot().quote).not.toBeNull());expect(client.getSnapshot().quote?.stores[0].coupon).toBeNull();stop()
})
