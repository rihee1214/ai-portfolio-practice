import { CatalogStore } from '../catalog/CatalogStore'
import { requestJson } from '../api/http'
import { apiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'
import { uuid } from '../cart/GuestCart'
export type Terms={kind:'FIXED'|'RATE';value:number;cap:number|null;minimum:number;startsAt:string;endsAt:string}
export type Coupon={storeSlug:string;storeName:string;id:string;templateId:string;storeId:string;title:string;terms:Terms;templateVersion:number;state:'AVAILABLE'|'RESERVED'|'USED';version:number}
export type Template={id:string;storeId:string;title:string;terms:Terms;issueStart:string;issueEnd:string;totalLimit:number;issuedCount:number;state:'DRAFT'|'ACTIVE'|'STOPPED'|'ARCHIVED';version:number;storeSlug:string;storeName:string}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const amount=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0
const date=(v:unknown):v is string=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const termsValid=(v:unknown):v is Terms=>object(v)&&['FIXED','RATE'].includes(String(v.kind))&&amount(v.value)&&Number(v.value)>0&&(v.kind!=='RATE'||Number(v.value)<=10000)&&(v.cap===null||amount(v.cap))&&amount(v.minimum)&&date(v.startsAt)&&date(v.endsAt)&&Date.parse(v.startsAt)<Date.parse(v.endsAt)
const base=(v:unknown):v is Record<string,unknown>=>object(v)&&uuid(v.id)&&uuid(v.storeId)&&typeof v.title==='string'&&!!v.title.trim()&&amount(v.version)&&termsValid(v.terms)
export const couponValid=(v:unknown):v is Coupon=>base(v)&&typeof v.storeSlug==='string'&&typeof v.storeName==='string'&&uuid(v.templateId)&&amount(v.templateVersion)&&['AVAILABLE','RESERVED','USED'].includes(String(v.state))
export const templateValid=(v:unknown):v is Template=>base(v)&&date(v.issueStart)&&date(v.issueEnd)&&Date.parse(v.issueStart)<Date.parse(v.issueEnd)&&amount(v.totalLimit)&&Number(v.totalLimit)>0&&amount(v.issuedCount)&&Number(v.issuedCount)<=Number(v.totalLimit)&&['DRAFT','ACTIVE','STOPPED','ARCHIVED'].includes(String(v.state))&&typeof v.storeSlug==='string'&&typeof v.storeName==='string'
export const couponStatus=(c:Coupon,now=Date.now())=>c.state==='USED'?'사용 완료':c.state==='RESERVED'?'예약 중':now>=Date.parse(c.terms.endsAt)?'만료':now<Date.parse(c.terms.startsAt)?'사용 전':'사용 가능'
export class CouponTemplateStore extends CatalogStore<Template>{constructor(auth:AuthStore){super(auth,'/api/seller/coupons',templateValid)}}
type State={owned:Coupon[];available:Template[];ownedPage:number;availablePage:number;ownedMore:boolean;availableMore:boolean;busy:boolean;ready:boolean;error:string;notice:string}
const empty=():State=>({owned:[],available:[],ownedPage:0,availablePage:0,ownedMore:false,availableMore:false,busy:false,ready:false,error:'',notice:''})
export class CouponStore {
 private state=empty();private generation=0;private controller=new AbortController();private listeners=new Set<()=>void>()
 constructor(private auth:AuthStore){}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(value:State){this.state=value;this.listeners.forEach(fn=>fn())}
 private stop=()=>{this.generation++;this.controller.abort();this.set(empty())}
 connect=()=>{const sync=()=>{this.stop();void this.load()};const off=this.auth.subscribe(sync);sync();return()=>{off();this.stop()}}
 load=async(ownedPage=this.state.ownedPage,availablePage=this.state.availablePage)=>{if(!this.state.busy)await this.run(null,ownedPage,availablePage)}
 claim=async(id:string)=>{if(!this.state.busy&&uuid(id))await this.run(id,this.state.ownedPage,this.state.availablePage)}
 private async run(id:string|null,ownedPage:number,availablePage:number){
  const member=this.auth.getSnapshot();if(!member.member||member.busy||member.error||ownedPage<0||availablePage<0||ownedPage>10000||availablePage>10000)return
  const owner=member.member.id,generation=++this.generation;this.controller.abort();this.controller=new AbortController();const signal=this.controller.signal
  this.set({...this.state,busy:true,error:'',notice:''})
  const current=()=>generation===this.generation&&!signal.aborted&&this.auth.getSnapshot().member?.id===owner
  const request=async(path:string,init:RequestInit={})=>{const r=await requestJson(path,{...init,credentials:'same-origin',cache:'no-store',signal:AbortSignal.any([signal,AbortSignal.timeout(10000)])});if(!current())throw new Error('cancelled');if(r.kind!=='success'){if(r.kind==='http'&&[401,403].includes(r.status))this.auth.rejectSession({message:'로그인 상태를 다시 확인해 주세요.'});throw new Error(apiErrorView(r)?.message??'쿠폰 결과를 확인하지 못했습니다. 목록을 다시 확인해 주세요.')}return r}
  try{
   if(id){const csrf=await request('/api/auth/csrf');if(!object(csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN'||typeof csrf.data.token!=='string'||!csrf.data.token)throw new Error('요청 확인 정보를 받지 못했습니다.')
    const r=await request('/api/account/coupons/claim',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:JSON.stringify({templateId:id})});if(r.status!==200||!couponValid(r.data))throw new Error('발급 응답을 확인하지 못했습니다.');ownedPage=0
   }
   const own=await request('/api/account/coupons?page='+ownedPage),available=await request('/api/account/coupons/available?page='+availablePage)
   const a=own.data,b=available.data
   if(!object(a)||!object(b)||a.page!==ownedPage||b.page!==availablePage||typeof a.hasMore!=='boolean'||typeof b.hasMore!=='boolean'||!Array.isArray(a.items)||!Array.isArray(b.items)||a.items.length>20||b.items.length>20||!a.items.every(couponValid)||!b.items.every(templateValid))throw new Error('쿠폰 목록 형식을 확인하지 못했습니다.')
   if(current())this.set({owned:a.items,available:b.items,ownedPage,availablePage,ownedMore:a.hasMore,availableMore:b.hasMore,busy:false,ready:true,error:'',notice:id?'쿠폰을 받았습니다.':''})
  }catch(e){if(current())this.set({...this.state,busy:false,error:e instanceof Error?e.message:'쿠폰 확인 실패'})}
 }
}
