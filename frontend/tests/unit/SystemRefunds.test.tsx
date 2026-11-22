import {act,render,screen,waitFor,within} from '@testing-library/react'
import {expect,test,vi} from 'vitest'
import App from '../../src/App'
import {StrictMode} from 'react'
import {AuthStore} from '../../src/auth/AuthStore'
import {AuthContext} from '../../src/auth/AuthContext'
import {SystemRefundStore} from '../../src/payment/SystemRefundStore'
import {SystemRefundsPage} from '../../src/payment/SystemRefundsPage'

const actor='00000000-0000-4000-8000-000000000001'
const attempt='00000000-0000-4000-8000-000000000002'
const seller='00000000-0000-4000-8000-000000000003'
const claim='00000000-0000-4000-8000-000000000004'
const status={attemptId:attempt,sellerOrderId:seller,claimId:claim,purpose:'ITEM',sequence:1,amount:'9900',state:'UNKNOWN',version:2,mode:'DEMO',automaticQueries:5,reviewRequired:true,nextDue:null,deadline:'2026-11-05T00:00:00Z',providerReceipt:false,providerReference:null,resultAt:null}
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})

test('G SYSTEM refund page reads the existing queue without SUPPORT access or automatic financial POST',async()=>{
 window.history.replaceState(null,'','/admin/refunds')
 const http=vi.fn(async (input:RequestInfo|URL,init?:RequestInit)=>{
  const path=String(input)
  if(path==='/api/auth/me')return json({id:actor,roles:['ADMIN']})
  if(path==='/api/admin/system/access')return json({access:'SYSTEM'})
  if(path.endsWith('/access'))return json({code:'FORBIDDEN',message:'Forbidden'},403)
  if(path==='/api/admin/system/refunds'&&(!init?.method||init.method==='GET'))return json([status])
  return json({})
 })
 vi.stubGlobal('fetch',http)
 render(<App />)
 expect(await screen.findByRole('region',{name:'SYSTEM 환불 대사'})).toBeVisible()
 expect(screen.getByRole('heading',{name:'환불 대사'})).toBeVisible()
 expect(await screen.findByText('운영 확인 필요',{exact:true})).toBeVisible()
 expect(screen.getByText(/^상품 환불 · 9,900원/)).toBeVisible()
 expect(http.mock.calls.some(([path])=>String(path)==='/api/admin/system/access')).toBe(true)
 const menu=within(screen.getByRole('navigation',{name:'운영자 메뉴'}))
 expect(await menu.findByRole('link',{name:'환불 대사'})).toBeVisible()
 expect(menu.queryByRole('link',{name:/고객지원|거래 관리/})).not.toBeInTheDocument()
 const supportCalls=http.mock.calls.filter(([path])=>String(path).startsWith('/api/admin/support/'))
 expect(new Set(supportCalls.map(([path])=>String(path)))).toEqual(new Set(['/api/admin/support/access']))
 expect(supportCalls.every(([,init])=>(init?.method??'GET')==='GET')).toBe(true)
 expect(http.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(0)
})


type Reply=(path:string,init?:RequestInit)=>Response|Promise<Response>|undefined
async function setup(reply:Reply=()=>undefined){
 const http=vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{const path=String(input),answer=reply(path,init);if(answer!==undefined)return await answer;if(path==='/api/auth/me')return json({id:actor,roles:['ADMIN']});if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'actual-unit-csrf'});if(path==='/api/admin/system/refunds')return json([status]);if(path.endsWith('/evidence'))return json([]);if(path==='/api/admin/system/refunds/'+attempt)return json(status);return json({})})
 vi.stubGlobal('fetch',http);const auth=new AuthStore();await auth.refresh();const store=new SystemRefundStore(auth);store.connect();await waitFor(()=>expect(store.getSnapshot().rows).toHaveLength(1));return {http,auth,store}
}
const receipt=(action:'QUERY'|'RECONCILE',version=2)=>({operationId:'00000000-0000-4000-8000-000000000005',attemptId:attempt,acceptedVersion:version,action,state:action==='QUERY'?'QUERY_REQUESTED':'EVIDENCE_RECORDED_QUERY_ONLY'})

test('G SUPPORT-only admin cannot open the SYSTEM refund page or read its queue',async()=>{
 window.history.replaceState(null,'','/admin/refunds')
 const http=vi.fn(async(input:RequestInfo|URL)=>String(input)==='/api/auth/me'?json({id:actor,roles:['ADMIN']}):String(input)==='/api/admin/system/access'?json({code:'FORBIDDEN',message:'Forbidden'},403):json({}))
 vi.stubGlobal('fetch',http);render(<App />);expect(await screen.findByRole('alert')).toHaveTextContent('권한');expect(http.mock.calls.some(([p])=>String(p)==='/api/admin/system/refunds')).toBe(false);expect(screen.queryByRole('region',{name:'환불 대기열'})).not.toBeInTheDocument()
})

test('G uncertain SYSTEM query retries the exact immutable key version and body without a refund submit',async()=>{
 const bodies:{path:string;body:Record<string,unknown>}[]=[]
 const {store}=await setup((path,init)=>{if(init?.method!=='POST')return;const body=JSON.parse(String(init.body));bodies.push({path,body});if(bodies.length===1)throw new TypeError('response lost after real server acceptance');return json(receipt('QUERY'),202)})
 try{await store.select(attempt);store.edit('reason','같은 시도 실제 결과 확인');await store.command('query');expect(store.getSnapshot().uncertain).toBe(true);store.edit('reason','변경하면 안 되는 사유');expect(store.getSnapshot().reason).toBe('같은 시도 실제 결과 확인');await store.retry();expect(bodies).toHaveLength(2);expect(bodies[1]).toEqual(bodies[0]);expect(bodies[0].path).toBe('/api/admin/system/refunds/'+attempt+'/query');expect(Object.keys(bodies[0].body).sort()).toEqual(['expectedVersion','key','reason']);expect(bodies[0].body.expectedVersion).toBe(2);expect(store.getSnapshot().uncertain).toBe(false);expect(store.getSnapshot().selected?.state).toBe('UNKNOWN');expect(store.getSnapshot().receipt?.state).toBe('QUERY_REQUESTED')}finally{store.disconnect()}
})

test('G SYSTEM 409 discards the old request and requires current GET selection and a new explicit command',async()=>{
 const bodies:Record<string,unknown>[]=[];let version=2
 const {store}=await setup((path,init)=>{if(path==='/api/admin/system/refunds')return json([{...status,version}]);if(path==='/api/admin/system/refunds/'+attempt)return json({...status,version});if(init?.method==='POST'){bodies.push(JSON.parse(String(init.body)));if(bodies.length===1){version=3;return json({},409)}return json(receipt('QUERY',3),202)}})
 try{await store.select(attempt);store.edit('reason','구버전 사유');await store.command('query');expect(store.getSnapshot().blocked).toBe(true);expect(store.getSnapshot().selected).toBeNull();expect(store.getSnapshot().uncertain).toBe(false);await store.retry();expect(bodies).toHaveLength(1);await store.load();expect(bodies).toHaveLength(1);await store.select(attempt);store.edit('reason','현재 상태 재확인 후 사유');await store.command('query');expect(bodies).toHaveLength(2);expect(bodies[1].expectedVersion).toBe(3);expect(bodies[1].key).not.toBe(bodies[0].key);expect(store.getSnapshot().receipt?.acceptedVersion).toBe(3)}finally{store.disconnect()}
})

test('G reconciliation records only existing query evidence and never turns an unbound UNKNOWN into success',async()=>{
 let body:Record<string,unknown>|undefined
 const {store}=await setup((path,init)=>{if(path.endsWith('/evidence'))return json([{operation:'GET',state:'UNKNOWN',transaction_key:null,response_fingerprint:null,observed_at:'2026-11-04T00:00:00Z',evidence:'{"paymentKey":"private-payment-key","secret":"never-display"}'}]);if(init?.method==='POST'){expect(path).toBe('/api/admin/system/refunds/'+attempt+'/reconcile');body=JSON.parse(String(init.body));return json(receipt('RECONCILE'),202)}})
 try{await store.select(attempt);expect(store.getSnapshot().selected?.state).toBe('UNKNOWN');expect(store.getSnapshot().evidence).toMatchObject([{operation:'GET',state:'UNKNOWN',fingerprint:null}]);expect(JSON.stringify(store.getSnapshot())).not.toContain('private-payment-key');expect(JSON.stringify(store.getSnapshot())).not.toContain('never-display');store.edit('reason','외부 운영기록 확인만');store.edit('evidenceReference','사내 대사기록 123');store.edit('transactionKey','unverified-transaction');await store.command('reconcile');expect(Object.keys(body!).sort()).toEqual(['evidenceReference','expectedVersion','key','reason','transactionKey']);expect(store.getSnapshot().selected).toMatchObject({state:'UNKNOWN',automaticQueries:5,providerReceipt:false});expect(store.getSnapshot().receipt?.state).toBe('EVIDENCE_RECORDED_QUERY_ONLY');expect(store.getSnapshot().notice).toContain('예약 금액을 유지')}finally{store.disconnect()}
})

test.each([401,403,404])('G SYSTEM read denial %i clears all private rows evidence and command drafts',async code=>{
 let denied=false
 const {store}=await setup((path)=>denied&&path.includes('/api/admin/system/refunds')?json({},code):undefined)
 try{await store.select(attempt);store.edit('reason','남기면 안 되는 개인 사유');store.edit('lookupId',attempt);denied=true;await store.load();expect(store.getSnapshot().rows).toEqual([]);expect(store.getSnapshot().selected).toBeNull();expect(store.getSnapshot().evidence).toEqual([]);expect(store.getSnapshot().reason).toBe('');expect(store.getSnapshot().lookupId).toBe('');expect(store.getSnapshot().receipt).toBeNull();expect(store.getSnapshot().uncertain).toBe(false)}finally{store.disconnect()}
})

test('G SYSTEM logout discards a delayed private read and StrictMode keeps one subscription then zero',async()=>{
 let held=false;const releases:((response:Response)=>void)[]=[]
 const {store,auth}=await setup((path)=>held&&path==='/api/admin/system/refunds'?new Promise<Response>(resolve=>releases.push(resolve)):undefined)
 store.disconnect();let live=0;const original=auth.subscribe;vi.spyOn(auth,'subscribe').mockImplementation(listener=>{live++;const off=original(listener);return()=>{live--;off()}})
 const mounted=render(<StrictMode><AuthContext value={auth}><SystemRefundsPage/></AuthContext></StrictMode>);await screen.findByText('운영 확인 필요',{exact:true});expect(live).toBe(1);held=true;act(()=>window.dispatchEvent(new Event('focus')));await waitFor(()=>expect(releases.length).toBeGreaterThan(0));act(()=>auth.stop());await act(async()=>{for(const release of releases)release(json([{...status,amount:'9007199254740993'}]));await Promise.resolve()});expect(screen.queryByText(/9,007,199,254,740,993/)).not.toBeInTheDocument();expect(screen.queryByText(attempt,{exact:false})).not.toBeInTheDocument();mounted.unmount();expect(live).toBe(0)
})

test.each(['duplicate','contradictory'])('G SYSTEM rejects malformed %s queue instead of publishing untrusted financial completion',async kind=>{
 const bad=kind==='duplicate'?[status,status]:[{...status,state:'SUCCEEDED',providerReference:null,resultAt:null}]
 const {store}=await setup();try{vi.stubGlobal('fetch',vi.fn(async()=>json(bad)));await store.load();expect(store.getSnapshot().rows).toEqual([]);expect(store.getSnapshot().selected).toBeNull();expect(store.getSnapshot().error).not.toBe('')}finally{store.disconnect()}
})


test.each([null,'verified'])('G actual POST GET observation success requires its transaction and fingerprint %s',async mode=>{
 const evidence={operation:'POST',state:'SUCCEEDED',transaction_key:mode?'verified-transaction':null,response_fingerprint:mode?'a'.repeat(64):null,observed_at:'2026-11-04T00:00:00Z',evidence:'{"secret":"never-display"}'}
 const {store}=await setup(path=>path.endsWith('/evidence')?json([evidence]):undefined)
 try{await store.select(attempt);if(mode){expect(store.getSnapshot().selected?.state).toBe('UNKNOWN');expect(store.getSnapshot().evidence[0]).toMatchObject({operation:'POST',state:'SUCCEEDED',transactionKey:'verified-transaction',fingerprint:'a'.repeat(64)});expect(JSON.stringify(store.getSnapshot())).not.toContain('never-display')}else{expect(store.getSnapshot().selected).toBeNull();expect(store.getSnapshot().evidence).toEqual([]);expect(store.getSnapshot().error).not.toBe('')}}finally{store.disconnect()}
})


test('G explicit SYSTEM attempt lookup reads the existing detail outside the bounded queue without a write',async()=>{
 const {store,http}=await setup();try{vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>String(input)==='/api/admin/system/refunds'?json([]):String(input).endsWith('/evidence')?json([]):json(status)));await store.load();expect(store.getSnapshot().rows).toEqual([]);store.edit('lookupId',attempt);await store.select(store.getSnapshot().lookupId);expect(store.getSnapshot().selected?.attemptId).toBe(attempt);expect(store.getSnapshot().lookupId).toBe('');expect(http.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(0)}finally{store.disconnect()}
})
