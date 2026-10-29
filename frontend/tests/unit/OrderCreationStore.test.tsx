import { it, expect, vi } from 'vitest'
import { OrderCreationStore, type OrderInput } from '../../src/order/OrderCreationStore'
import { AuthStore } from '../../src/auth/AuthStore'
import {render,screen,fireEvent} from '@testing-library/react'
import {OrderCreationPanel} from '../../src/order/OrderCreationPanel'
import type {CartStore,PurchaseCart} from '../../src/cart/CartStore'
import type {Quote} from '../../src/promotion/QuoteStore'
const id='00000000-0000-0000-0000-000000000001'
const input:OrderInput={lines:[{skuId:id,quantity:1}],coupons:[],fingerprint:'a'.repeat(64),contact:{buyerName:'구매자',buyerPhone:'01012345678',recipientName:'수령인',recipientPhone:'01012345678',postalCode:'01234',addressLine1:'주소',addressLine2:''},guestAttemptId:null,linkRevision:null}
const result={id,orderNumber:'O'+'a'.repeat(32),state:'PENDING_PAYMENT',total:'13000',expiresAt:'2030-01-01T00:00:00Z',stores:2}
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
async function ready(){vi.stubGlobal('fetch',vi.fn(async()=>json({id,roles:['BUYER']})));const auth=new AuthStore();await auth.refresh();return auth}
it('reuses one immutable request key for duplicate clicks and lost responses',async()=>{
 const auth=await ready(),bodies:Record<string,unknown>[]=[],cleanup=vi.fn(async()=>{});let fail=true,release:(()=>void)|undefined
 const fetcher=vi.fn(async(path:RequestInfo|URL,init?:RequestInit)=>{if(String(path).endsWith('/csrf'))return json({headerName:'X-CSRF-TOKEN',token:'csrf'});bodies.push(JSON.parse(String(init?.body)));if(fail){await new Promise<void>(resolve=>{release=resolve});throw new TypeError('lost response')}return json(result)})
 const store=new OrderCreationStore(auth,cleanup,fetcher),first=store.submit(input);await vi.waitFor(()=>expect(bodies).toHaveLength(1));await store.submit(input);expect(bodies).toHaveLength(1);release!();await first;expect(store.getSnapshot().retry).toBe(true);expect(cleanup).not.toHaveBeenCalled();fail=false;await store.retry();expect(bodies).toHaveLength(2);expect(bodies[1]).toEqual(bodies[0]);expect(store.getSnapshot().result?.orderNumber).toBe(result.orderNumber);expect(cleanup).toHaveBeenCalledTimes(1);await store.retry();expect(bodies).toHaveLength(2)
})
it('requires a fresh confirmation and key after 409 and never replays after 401',async()=>{
 const auth=await ready(),bodies:Record<string,unknown>[]=[],cleanup=vi.fn(async()=>{});let status=409
 const store=new OrderCreationStore(auth,cleanup,vi.fn(async(path:RequestInfo|URL,init?:RequestInit)=>{if(String(path).endsWith('/csrf'))return json({headerName:'X-CSRF-TOKEN',token:'csrf'});bodies.push(JSON.parse(String(init?.body)));return status===200?json(result):json({},status)}))
 await store.submit(input);expect(store.getSnapshot().conflict).toBe(true);await store.retry();await store.submit(input);expect(bodies).toHaveLength(1);store.confirmAgain();status=401;await store.submit({...input,fingerprint:'b'.repeat(64)});expect(bodies).toHaveLength(2);expect(bodies[1].requestKey).not.toBe(bodies[0].requestKey);await store.retry();await store.submit(input);expect(bodies).toHaveLength(2);expect(cleanup).not.toHaveBeenCalled()
})
it('keeps a committed order successful when cart reconciliation fails',async()=>{
 const auth=await ready(),cleanup=vi.fn(async()=>{throw new Error('cart unavailable')})
 const store=new OrderCreationStore(auth,cleanup,vi.fn(async(path:RequestInfo|URL)=>String(path).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):json(result)))
 await store.submit(input);expect(store.getSnapshot().result?.orderNumber).toBe(result.orderNumber);expect(store.getSnapshot().cleanupError).toBe(true);expect(store.getSnapshot().retry).toBe(false)
})
it.each([false,true])('binds cleanup to approved quote lines and revisions before another tab edits the cart (changed before approval: %s)',async(beforeApproval)=>{
 const auth=await ready(),second='00000000-0000-0000-0000-000000000002'
 const approved:PurchaseCart={owner:id,items:[{skuId:id,quantity:1,revision:1}]};let current=approved
 const reconcile=vi.fn(async()=>{}),cart={capturePurchase:()=>current,reconcilePurchase:reconcile} as unknown as CartStore
 const quote={fingerprint:'a'.repeat(64),stores:[{storeId:id,coupon:null,lines:[{skuId:id,quantity:1}]}]} as unknown as Quote
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):json(result)))
 render(<OrderCreationPanel auth={auth} cart={cart} quote={quote} refresh={async()=>{}}/>)
 for(const [label,value] of Object.entries({'주문 구매자 이름':'구매자','주문 구매자 전화번호':'01012345678','수령인 이름':'수령인','수령인 전화번호':'01012345678','우편번호':'01234','기본 주소':'주소'}))fireEvent.change(screen.getByLabelText(label),{target:{value}})
 const changed={owner:id,items:[{skuId:id,quantity:2,revision:2},{skuId:second,quantity:1,revision:3}]}
 if(beforeApproval)current=changed
 fireEvent.click(screen.getByRole('checkbox'));current=changed
 fireEvent.click(screen.getByRole('button',{name:'주문 생성'}));await vi.waitFor(()=>expect(reconcile).toHaveBeenCalledTimes(1));expect(reconcile).toHaveBeenCalledWith(id,beforeApproval?{owner:id,items:[]}:approved)
})
