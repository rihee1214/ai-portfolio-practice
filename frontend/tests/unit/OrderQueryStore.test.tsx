import {notifyGuestAccessChange} from '../../src/guest/GuestAccessLifecycle'
import {it,expect,vi} from 'vitest'
import {OrderQueryStore} from '../../src/order/OrderQueryStore'
import {AuthStore} from '../../src/auth/AuthStore'
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
const number='O'+'a'.repeat(32),other='O'+'b'.repeat(32)
const row={orderNumber:number,state:'PAID',total:'1000',mode:'DEMO',createdAt:'2026-10-28T00:00:00Z'}
const detail={...row,policyVersion:'v1',contact:{buyerName:'구매자',buyerPhone:'01012345678',recipientName:'수령인',recipientPhone:'01087654321',postalCode:'01234',addressLine1:'주소',addressLine2:''},stores:[{sellerOrderId:'seller',storeId:'store',name:'첫 매대',subtotal:'1000',productDiscount:'0',couponDiscount:'0',shippingBase:'0',shippingDiscount:'0',total:'1000',items:[{id:'item',skuId:'sku',title:'과거 상품',options:[],quantity:1,unitPrice:'1000',gross:'1000',promotionDiscount:'0',couponDiscount:'0',net:'1000',units:[{ordinal:0,gross:'1000',promotionDiscount:'0',couponDiscount:'0',net:'1000'}]}]}]}
const history={orderNumber:number,orderState:'PAID',paymentState:'SUCCEEDED',compensation:'NONE',reviewRequired:false,amount:'1000',mode:'DEMO',events:[{action:'SUCCEEDED',occurredAt:'2026-10-28T00:00:01Z'}]}
async function auth(){let id='00000000-0000-0000-0000-000000000001';vi.stubGlobal('fetch',vi.fn(async()=>json({id,roles:['BUYER']})));const store=new AuthStore();await store.refresh();return {store,switchUser:async()=>{id='00000000-0000-0000-0000-000000000002';await store.refresh()}}}
it('reads real order snapshots and payment events without posting and requests the next server cursor',async()=>{
 const a=await auth(),calls:{path:string;cache:unknown;credentials:unknown;method:unknown}[]=[]
 const store=new OrderQueryStore(a.store,'member',vi.fn(async(path,init)=>{calls.push({path:String(path),cache:init?.cache,credentials:init?.credentials,method:init?.method??'GET'});if(String(path).endsWith('/history'))return json(history);if(String(path).includes('/api/orders/'))return json(detail);return json({orders:[row],nextCursor:String(path).includes('cursor=')?null:'cursor-next'})}))
 await store.load();expect(store.getSnapshot().orders).toEqual([row]);await store.next();expect(calls[1].path).toBe('/api/account/orders?cursor=cursor-next');await store.open(number);expect(store.getSnapshot().detail).toEqual(detail);expect(store.getSnapshot().history).toEqual(history);expect(calls.every(c=>c.cache==='no-store'&&c.credentials==='same-origin'&&c.method==='GET')).toBe(true)
})
it('conceals previous private data during loading and after failed or malformed responses',async()=>{
 const a=await auth();let fail=false,release:((r:Response)=>void)|undefined
 const store=new OrderQueryStore(a.store,'member',vi.fn(async()=>fail?await new Promise<Response>(r=>{release=r}):json({orders:[row],nextCursor:null})))
 await store.load();expect(store.getSnapshot().orders).toHaveLength(1);fail=true;const loading=store.load();expect(store.getSnapshot().orders).toEqual([]);release!(json({},503));await loading;expect(store.getSnapshot().orders).toEqual([]);expect(store.getSnapshot().error).not.toBe('')
})
it('discards a delayed account A response immediately when auth changes to account B',async()=>{
 const a=await auth();let release:((r:Response)=>void)|undefined
 const store=new OrderQueryStore(a.store,'member',vi.fn(async()=>await new Promise<Response>(r=>{release=r})))
 const loading=store.load();await a.switchUser();expect(store.getSnapshot().orders).toEqual([]);release!(json({orders:[row],nextCursor:null}));await loading;expect(store.getSnapshot().orders).toEqual([]);expect(store.getSnapshot().detail).toBeNull();store.dispose()
})
it('clears guest history and drops late responses when lookup logout starts or its response is lost',async()=>{
 const a=await auth();let release:((r:Response)=>void)|undefined
 const store=new OrderQueryStore(a.store,'guest',vi.fn(async()=>await new Promise<Response>(r=>{release=r})))
 const loading=store.load();store.clear();release!(json({orders:[row],nextCursor:null}));await loading;expect(store.getSnapshot().orders).toEqual([]);expect(store.getSnapshot().history).toBeNull()
})
it('rejects mismatched order or payment generations and conceals previously opened details',async()=>{
 const a=await auth();let mismatch=false
 const store=new OrderQueryStore(a.store,'member',vi.fn(async(path)=>String(path).endsWith('/history')?json(mismatch?{...history,orderNumber:other}:history):json(detail)))
 await store.open(number);expect(store.getSnapshot().detail).toEqual(detail);mismatch=true;await store.open(number);expect(store.getSnapshot().detail).toBeNull();expect(store.getSnapshot().history).toBeNull();expect(store.getSnapshot().error).not.toBe('')
})
it('resubscribes to auth after strict effect cleanup and hides data before another identity renders',async()=>{
 const a=await auth();const store=new OrderQueryStore(a.store,'member',vi.fn(async()=>json({orders:[row],nextCursor:null})))
 store.dispose();const unsubscribe=store.subscribe(()=>{});await store.load();expect(store.getSnapshot().orders).toHaveLength(1);a.store.stop();expect(store.getSnapshot().orders).toEqual([]);unsubscribe();store.dispose()
})
it('discards a list when auth changes after the read resolves but before the list is published',async()=>{
 const a=await auth(),data={orders:[row],nextCursor:null};const response=json(data)
 vi.spyOn(response,'json').mockImplementation(()=>Promise.resolve(data).then(value=>{queueMicrotask(()=>queueMicrotask(()=>queueMicrotask(()=>a.store.stop())));return value}))
 const store=new OrderQueryStore(a.store,'member',vi.fn(async()=>response));await store.load();expect(a.store.getSnapshot().checked).toBe(false);expect(store.getSnapshot().orders).toEqual([])
})
it('discards detail when both reads resolve before an auth change at the combined result boundary',async()=>{
 const a=await auth();const all=Promise.all.bind(Promise)
 const store=new OrderQueryStore(a.store,'member',vi.fn(async path=>String(path).endsWith('/history')?json(history):json(detail)))
 const scheduler=vi.spyOn(Promise,'all').mockImplementationOnce(values=>all(values).then(result=>{a.store.stop();return result}))
 await store.open(number);scheduler.mockRestore();expect(store.getSnapshot().detail).toBeNull();expect(store.getSnapshot().history).toBeNull()
})

it('P09C private order rejects decimal amounts above signed long without publishing details',async()=>{const a=await auth(),total='9223372036854775808';const store=new OrderQueryStore(a.store,'member',vi.fn(async p=>String(p).endsWith('/history')?json({...history,amount:total}):json({...detail,total})));await store.open(number);expect(store.getSnapshot().detail).toBeNull();expect(store.getSnapshot().history).toBeNull()})

it('GAS lifetime changes clear the parent order snapshot and discard both late detail and history without AuthStore changes',async()=>{const a=await auth();let pending=false;const releases:((r:Response)=>void)[]=[];const store=new OrderQueryStore(a.store,'guest',vi.fn(async path=>pending?new Promise<Response>(r=>{releases.push(r)}):json(String(path).endsWith('/history')?history:detail)));await store.open(number);expect(store.getSnapshot().detail).toEqual(detail);notifyGuestAccessChange();expect(store.getSnapshot().detail).toBeNull();expect(store.getSnapshot().history).toBeNull();pending=true;const loading=store.open(number);expect(releases).toHaveLength(2);notifyGuestAccessChange();releases[0](json(detail));releases[1](json(history));await loading;expect(store.getSnapshot().detail).toBeNull();expect(store.getSnapshot().history).toBeNull();store.dispose()})
