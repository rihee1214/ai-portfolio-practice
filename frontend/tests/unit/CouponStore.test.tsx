import { it, expect, vi } from 'vitest'
import { CouponStore, couponValid, templateValid, couponStatus } from '../../src/promotion/CouponStore'
import { AuthStore } from '../../src/auth/AuthStore'
const id='00000000-0000-0000-0000-000000000001'
const terms={kind:'FIXED' as const,value:100,cap:null,minimum:0,startsAt:'2020-01-01T00:00:00Z',endsAt:'2099-01-01T00:00:00Z'}
const coupon={storeSlug:"coupon-store",storeName:"사용 매대",id,templateId:id,storeId:id,title:'쿠폰',terms,templateVersion:1,state:'AVAILABLE' as const,version:0}
const template={id,storeId:id,title:'쿠폰',terms,issueStart:terms.startsAt,issueEnd:terms.endsAt,totalLimit:10,issuedCount:0,state:'ACTIVE',version:1,storeSlug:'store',storeName:'매대'}
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
it('validates immutable coupon terms and expiration display',()=>{
 expect(couponValid(coupon)).toBe(true);expect(templateValid(template)).toBe(true)
 expect(couponValid({...coupon,terms:{...terms,value:Number.MAX_SAFE_INTEGER+1}})).toBe(false)
 expect(couponStatus(coupon,Date.parse(terms.endsAt))).toBe('만료')
 expect(couponStatus(coupon,Date.parse(terms.startsAt)-1)).toBe('사용 전')
 expect(couponStatus(coupon,Date.parse(terms.startsAt))).toBe('사용 가능')
})
it('blocks duplicate claims and reloads after a lost response',async()=>{
 let issued=false;let posts=0;let header:string|null=null;let release:()=>void=()=>{}
 vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
  if(path==='/api/auth/me')return json({id,roles:['BUYER']})
  if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'csrf'})
  if(init?.method==='POST'){posts++;header=new Headers(init.headers).get('X-CSRF-TOKEN');issued=true;await new Promise<void>(resolve=>{release=resolve});throw new TypeError('lost')}
  return json({items:path.includes('/available')?issued?[]:[template]:issued?[coupon]:[],page:0,hasMore:false})
 }))
 const auth=new AuthStore();await auth.refresh();const store=new CouponStore(auth);const stop=store.connect()
 await vi.waitFor(()=>expect(store.getSnapshot().ready).toBe(true))
 const first=store.claim(id);await vi.waitFor(()=>expect(posts).toBe(1));await store.claim(id);expect(posts).toBe(1);expect(header).toBe('csrf');release();await first
 expect(store.getSnapshot().error).toBeTruthy();await store.load();expect(store.getSnapshot().owned).toEqual([coupon]);stop()
})
it('drops late coupon results when the member session ends',async()=>{
 let resolve:((r:Response)=>void)|undefined
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id,roles:['BUYER']}):new Promise<Response>(r=>{resolve=r})))
 const auth=new AuthStore();await auth.refresh();const store=new CouponStore(auth);const stop=store.connect()
 await vi.waitFor(()=>expect(resolve).toBeDefined());auth.rejectSession({message:'ended'});resolve!(json({items:[coupon],page:0,hasMore:false}));await Promise.resolve()
 expect(store.getSnapshot().owned).toEqual([]);expect(store.getSnapshot().ready).toBe(false);stop()
})

import {render,screen} from '@testing-library/react'
import {MemoryRouter} from 'react-router'
import {AuthContext} from '../../src/auth/AuthContext'
import {CouponPage} from '../../src/pages/CouponPages'
it('identifies the store on owned coupons after availability ends',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id,roles:['BUYER']}):json({items:path.includes('/available')?[]:[{...coupon,storeSlug:'coupon-store',storeName:'사용 매대'}],page:0,hasMore:false})))
 const auth=new AuthStore();await auth.refresh();render(<MemoryRouter><AuthContext value={auth}><CouponPage/></AuthContext></MemoryRouter>)
 expect(await screen.findByRole('link',{name:'사용 매대'})).toHaveAttribute('href','/stores/coupon-store')
})
it('shows reserved used and restored-expired coupons without extending their period',()=>{
 expect(couponStatus({...coupon,state:'RESERVED'})).toBe('예약 중');expect(couponStatus({...coupon,state:'USED'})).toBe('사용 완료');const end=Date.parse(terms.endsAt);expect(couponStatus({...coupon,state:'AVAILABLE',version:4},end-1)).toBe('사용 가능');expect(couponStatus({...coupon,state:'AVAILABLE',version:4},end)).toBe('만료');expect(coupon.terms.endsAt).toBe('2099-01-01T00:00:00Z')
})
