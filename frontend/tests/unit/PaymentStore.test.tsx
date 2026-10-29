import {it,expect,vi} from 'vitest'
import {PaymentStore} from '../../src/payment/PaymentStore'
import {AuthStore} from '../../src/auth/AuthStore'
import {StrictMode} from 'react'
import {act,render,screen} from '@testing-library/react'
import {MemoryRouter,Routes,Route} from 'react-router'
import {AuthContext} from '../../src/auth/AuthContext'
import {PaymentPage} from '../../src/payment/PaymentPage'
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
async function auth(){vi.stubGlobal('fetch',vi.fn(async()=>json({id:'00000000-0000-0000-0000-000000000001',roles:['BUYER']})));const a=new AuthStore();await a.refresh();return a}
const pending={orderNumber:'O'+'a'.repeat(32),orderState:'PENDING_PAYMENT',paymentState:'UNKNOWN',compensation:'NONE',reviewRequired:false,amount:'1000',mode:'DEMO'}
it('clears the real payment panel immediately when authentication is invalidated in StrictMode',async()=>{
 const a=await auth();vi.stubGlobal('fetch',vi.fn(async()=>json({...pending,orderState:'PAID',paymentState:'SUCCEEDED'})))
 render(<StrictMode><MemoryRouter initialEntries={['/payments/'+pending.orderNumber]}><AuthContext value={a}><Routes><Route path="/payments/:number" element={<PaymentPage/>}/></Routes></AuthContext></MemoryRouter></StrictMode>)
 await screen.findByRole('heading',{name:'결제가 완료되었습니다'});expect(screen.getByText(/1,000/)).toBeVisible()
 act(()=>a.stop());expect(screen.queryByText(/1,000/)).not.toBeInTheDocument();expect(screen.queryByRole('heading',{name:'결제가 완료되었습니다'})).not.toBeInTheDocument()
})
it.each(['success','error','same identity'])('drops late payment %s after an authentication generation changes without replaying approval',async shape=>{
 const a=await auth();let release!:(r:Response)=>void;const calls:string[]=[]
 const s=new PaymentStore(a,pending.orderNumber,vi.fn(async(_p,i)=>{calls.push(i?.method??'GET');return await new Promise<Response>(r=>release=r)}))
 const loading=s.poll();a.stop();if(shape!=='same identity')vi.stubGlobal('fetch',vi.fn(async()=>json({id:'00000000-0000-0000-0000-000000000002',roles:['BUYER']})));await a.refresh()
 release(shape==='error'?json({},401):json({...pending,orderState:'PAID',paymentState:'SUCCEEDED'}));await loading
 expect(s.getSnapshot().view).toBeNull();expect(a.getSnapshot().error).toBeNull();await s.approve();expect(calls).toEqual(['GET'])
})
it('removes previously displayed payment information on 401 and blocks automatic retry',async()=>{
 const a=await auth();let denied=false;const s=new PaymentStore(a,pending.orderNumber,vi.fn(async()=>denied?json({},401):json(pending)))
 await s.poll();expect(s.getSnapshot().view?.amount).toBe('1000');denied=true;await s.poll();expect(s.getSnapshot().view).toBeNull();expect(s.getSnapshot().blocked).toBe(true)
})
it('resumes only a server read after routine auth refresh and requires a fresh explicit approval',async()=>{
 const a=await auth(),methods:string[]=[];const s=new PaymentStore(a,pending.orderNumber,vi.fn(async(p,i)=>{methods.push(i?.method??'GET');return String(p).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):json({...pending,paymentState:'NONE'})}))
 await s.poll();await a.refresh();expect(s.getSnapshot().view).toBeNull();await s.approve();expect(methods).toEqual(['GET']);await s.poll();expect(s.getSnapshot().view?.amount).toBe('1000');expect(methods).toEqual(['GET','GET']);await s.approve();expect(methods).toEqual(['GET','GET','GET','POST'])
})
it.each(['DEMO','SANDBOX'])('clears private %s state if CSRF preparation reports expired authentication',async mode=>{
 const a=await auth();const s=new PaymentStore(a,pending.orderNumber,vi.fn(async p=>String(p).endsWith('/csrf')?json({},401):json({...pending,mode,paymentState:'NONE'})))
 await s.poll();if(mode==='DEMO')await s.approve();else await s.sandbox();expect(s.getSnapshot().view).toBeNull();expect(s.getSnapshot().blocked).toBe(true)
})
it.each(['success','error'])('discards a late SANDBOX setup %s after authentication changes',async outcome=>{
 const a=await auth();let release!:(r:Response)=>void,waiting!:()=>void;const ready=new Promise<void>(r=>waiting=r)
 const s=new PaymentStore(a,pending.orderNumber,vi.fn(async p=>String(p).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):String(p).endsWith('/sandbox')?(waiting(),await new Promise<Response>(r=>release=r)):json({...pending,mode:'SANDBOX',paymentState:'NONE'})))
 await s.poll();const setup=s.sandbox();await ready;a.stop();await a.refresh();release(outcome==='success'?json({clientKey:'test_ck_fixture',orderId:'provider',customerKey:'provider',amount:'1000'}):json({},401));expect(await setup).toBeNull();expect(s.getSnapshot().view).toBeNull();expect(a.getSnapshot().error).toBeNull()
})
it('confirms an untrusted SDK redirect only after explicit CSRF submission and polls a lost result',async()=>{
 const a=await auth(),calls:{path:string;method:string;body:unknown}[]=[],input={orderId:'00000000-0000-0000-0000-000000000002',amount:'1000',paymentKey:'fixture-only'};let paid=false
 const store=new PaymentStore(a,pending.orderNumber,vi.fn(async(path,init)=>{calls.push({path:String(path),method:init?.method??'GET',body:init?.body});if(String(path).endsWith('/csrf'))return json({headerName:'X-CSRF-TOKEN',token:'csrf'});if(String(path).endsWith('/confirm')){paid=true;throw new TypeError('lost response')}return json({...pending,mode:'SANDBOX',paymentState:paid?'SUCCEEDED':'NONE',orderState:paid?'PAID':'PENDING_PAYMENT'})}))
 await store.poll();expect(calls.filter(c=>c.method==='POST')).toHaveLength(0);await store.confirm(input);expect(calls.filter(c=>c.method==='POST')).toEqual([{path:'/api/payments/'+pending.orderNumber+'/confirm',method:'POST',body:JSON.stringify(input)}]);expect(store.getSnapshot().view?.orderState).toBe('PENDING_PAYMENT');await store.poll();expect(store.getSnapshot().view?.orderState).toBe('PAID');await store.confirm(input);expect(calls.filter(c=>c.method==='POST')).toHaveLength(1)
})
it('polls the saved server result after uncertainty without another approval',async()=>{
 const a=await auth(),calls:{path:string;method:string}[]=[];let paid=false
 const store=new PaymentStore(a,pending.orderNumber,vi.fn(async(path,init)=>{calls.push({path:String(path),method:init?.method??'GET'});if(String(path).endsWith('csrf'))return json({headerName:'X-CSRF-TOKEN',token:'csrf'});return json(paid?{...pending,paymentState:'SUCCEEDED',orderState:'PAID'}:pending)}))
 await store.approve();expect(store.getSnapshot().view?.paymentState).toBe('UNKNOWN');paid=true;await store.poll();expect(store.getSnapshot().view?.orderState).toBe('PAID');await store.approve();expect(calls.filter(c=>c.method==='POST')).toHaveLength(1)
})
it('blocks automatic approval and polling after 401 even after login returns',async()=>{
 const a=await auth(),calls:string[]=[];const store=new PaymentStore(a,pending.orderNumber,vi.fn(async(path,init)=>{calls.push(init?.method??'GET');return String(path).endsWith('csrf')?json({headerName:'X-CSRF-TOKEN',token:'csrf'}):json({},401)}))
 await store.approve();expect(store.getSnapshot().blocked).toBe(true);await a.refresh();await store.approve();await store.poll();expect(calls).toEqual(['GET','POST'])
})
it('loading a payment success location only reads and never approves',async()=>{
 const a=await auth(),calls:string[]=[];const store=new PaymentStore(a,pending.orderNumber,vi.fn(async(_path,init)=>{calls.push(init?.method??'GET');return json(pending)}));await store.poll();expect(calls).toEqual(['GET']);expect(store.getSnapshot().view?.orderState).toBe('PENDING_PAYMENT')
})

it.each(['csrf','response'])('requires an explicit retry after %s failure and an authenticated server result with no attempt',async(failure)=>{
 const a=await auth(),calls:string[]=[];let fail=true
 const store=new PaymentStore(a,pending.orderNumber,vi.fn(async(path,init)=>{
  const method=init?.method??'GET';calls.push(method+' '+String(path))
  if(String(path).endsWith('/csrf')){if(fail&&failure==='csrf'){fail=false;throw new TypeError('preflight unavailable')}return json({headerName:'X-CSRF-TOKEN',token:'csrf'})}
  if(method==='POST'){if(fail&&failure==='response'){fail=false;throw new TypeError('response unavailable')}return json({...pending,paymentState:'SUCCEEDED',orderState:'PAID'})}
  return json({...pending,paymentState:'NONE'})
 }))
 await store.poll();await store.approve();const beforeRetry=calls.filter(c=>c.startsWith('POST')).length;expect(beforeRetry).toBe(failure==='csrf'?0:1);expect(store.getSnapshot().error).not.toBe('')
 await store.poll();expect(store.getSnapshot().view?.paymentState).toBe('NONE');expect(calls.filter(c=>c.startsWith('POST'))).toHaveLength(beforeRetry)
 await store.approve();expect(store.getSnapshot().view?.orderState).toBe('PAID');expect(calls.filter(c=>c.startsWith('POST'))).toHaveLength(beforeRetry+1)
})

