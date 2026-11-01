import {returnPolicyValid,type ReturnPolicy} from '../catalog/ReturnPolicy'
import { requestJson } from '../api/http'
import { apiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'
import { uuid } from '../cart/GuestCart'
export type LineRequest={skuId:string;quantity:number}
type Benefit={id:string;version:number;title:string;selectionReason:string}
type Unit={ordinal:number;gross:string;promotionDiscount:string;couponDiscount:string;net:string}
type Line={skuId:string;productId:string;productVersion:number;skuVersion:number;title:string;options:string[];quantity:number;unitPrice:string;gross:string;promotion:Benefit|null;promotionDiscount:string;couponDiscount:string;net:string;units:Unit[];returnPolicy:ReturnPolicy}
type Store={storeId:string;slug:string;name:string;version:number;lines:Line[];subtotal:string;productDiscount:string;afterPromotion:string;coupon:{id:string;version:number;templateVersion:number;title:string}|null;couponDiscount:string;shippingBase:string;shippingPromotion:Benefit|null;shippingDiscount:string;total:string}
export type Quote={policyVersion:string;evaluatedAt:string;fingerprint:string;total:string;stores:Store[]}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const amount=(v:unknown):v is string=>typeof v==='string'&&/^(0|[1-9]\d*)$/.test(v)&&v.length<=19&&BigInt(v)<=9223372036854775807n
const version=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0
const benefit=(v:unknown)=>v===null||(object(v)&&uuid(v.id)&&version(v.version)&&typeof v.title==='string'&&v.selectionReason==='MAX_DISCOUNT_ID_TIE')
const moneyFields=(v:Record<string,unknown>,keys:string[])=>keys.every(k=>amount(v[k]))
export function quoteValid(value:unknown):value is Quote {
 if(!object(value)||value.policyVersion!=='P07-C-1'||typeof value.evaluatedAt!=='string'||!Number.isFinite(Date.parse(value.evaluatedAt))||typeof value.fingerprint!=='string'||! /^[a-f0-9]{64}$/.test(value.fingerprint)||!amount(value.total)||!Array.isArray(value.stores)||value.stores.length<1||value.stores.length>20)return false
 const stores=new Set<string>(),skus=new Set<string>(),coupons=new Set<string>();let total=0n
 for(const s of value.stores){
  if(!object(s)||!uuid(s.storeId)||stores.has(s.storeId)||typeof s.slug!=='string'||typeof s.name!=='string'||!version(s.version)||!moneyFields(s,['subtotal','productDiscount','afterPromotion','couponDiscount','shippingBase','shippingDiscount','total'])||!benefit(s.shippingPromotion)||!Array.isArray(s.lines)||!s.lines.length)return false
  stores.add(s.storeId)
  if(s.coupon!==null){const c=s.coupon;if(!object(c)||!uuid(c.id)||coupons.has(c.id)||!version(c.version)||!version(c.templateVersion)||typeof c.title!=='string')return false;coupons.add(c.id)}
  let gross=0n,promo=0n,coupon=0n
  for(const l of s.lines){
   if(!object(l)||!returnPolicyValid(l.returnPolicy,String(l.productId))||!uuid(l.skuId)||skus.has(l.skuId)||!uuid(l.productId)||!version(l.productVersion)||!version(l.skuVersion)||typeof l.title!=='string'||!Array.isArray(l.options)||!l.options.every(x=>typeof x==='string')||!Number.isInteger(l.quantity)||Number(l.quantity)<1||Number(l.quantity)>99||!moneyFields(l,['unitPrice','gross','promotionDiscount','couponDiscount','net'])||!benefit(l.promotion)||!Array.isArray(l.units)||l.units.length!==l.quantity)return false
   skus.add(l.skuId);const line=l as unknown as Line;let ug=0n,up=0n,uc=0n,un=0n
   for(const [index,u] of l.units.entries()){
    if(!object(u)||u.ordinal!==index||!moneyFields(u,['gross','promotionDiscount','couponDiscount','net']))return false
    const unit=u as unknown as Unit;const g=BigInt(unit.gross),p=BigInt(unit.promotionDiscount),c=BigInt(unit.couponDiscount),n=BigInt(unit.net)
    if(g!==BigInt(line.unitPrice)||g-p-c!==n)return false;ug+=g;up+=p;uc+=c;un+=n
   }
   if(ug!==BigInt(line.gross)||up!==BigInt(line.promotionDiscount)||uc!==BigInt(line.couponDiscount)||un!==BigInt(line.net)||(!line.promotion&&up!==0n))return false
   gross+=ug;promo+=up;coupon+=uc
  }
  const store=s as unknown as Store,after=gross-promo,shipping=after>=50000n?0n:3000n,sd=BigInt(store.shippingDiscount)
  if(gross!==BigInt(store.subtotal)||promo!==BigInt(store.productDiscount)||after!==BigInt(store.afterPromotion)||coupon!==BigInt(store.couponDiscount)||(!store.coupon&&coupon!==0n)||shipping!==BigInt(store.shippingBase)||sd>shipping||(!store.shippingPromotion&&sd!==0n)||after-coupon+shipping-sd!==BigInt(store.total))return false
  total+=BigInt(store.total)
 }
 return skus.size<=20&&total===BigInt(value.total)
}
export const formatMoney=(value:string)=>BigInt(value).toLocaleString('ko-KR')+'원'
type State={quote:Quote|null;selected:Record<string,string>;busy:boolean;error:string}
export class QuoteStore {
 private state:State={quote:null,selected:{},busy:false,error:''};private lines:LineRequest[]=[];private generation=0;private controller=new AbortController();private listeners=new Set<()=>void>()
 constructor(private auth:AuthStore){}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(state:State){this.state=state;this.listeners.forEach(fn=>fn())}
 private cancel(){this.generation++;this.controller.abort()}
 connect=()=>{const sync=()=>{this.cancel();this.set({quote:null,selected:{},busy:false,error:''});void this.refresh()};const off=this.auth.subscribe(sync);sync();return()=>{off();this.cancel()}}
 configure=(lines:LineRequest[])=>{this.lines=lines.map(l=>({...l}));this.cancel();this.set({...this.state,quote:null,busy:false,error:''});void this.refresh()}
 clearCoupons=()=>{this.cancel();this.set({quote:null,selected:{},busy:false,error:''});void this.refresh()}
 selectCoupon=(storeId:string,couponId:string)=>{if(this.state.busy||!uuid(storeId)||(couponId&&!uuid(couponId)))return;const selected={...this.state.selected};if(couponId)selected[storeId]=couponId;else delete selected[storeId];this.set({...this.state,selected});void this.refresh()}
 refresh=async()=>{
  const auth=this.auth.getSnapshot();if(!auth.checked||auth.busy||auth.error||!this.lines.length)return
  this.cancel();this.controller=new AbortController();const generation=this.generation,signal=this.controller.signal,owner=auth.member?.id,lines=this.lines.map(l=>({...l})),selected={...this.state.selected}
  const current=()=>generation===this.generation&&!signal.aborted&&this.auth.getSnapshot().member?.id===owner
  this.set({...this.state,quote:null,busy:true,error:''})
  const request=async(path:string,init:RequestInit={})=>{const r=await requestJson(path,{...init,credentials:'same-origin',cache:'no-store',signal:AbortSignal.any([signal,AbortSignal.timeout(10000)])});if(!current())throw new Error('cancelled');if(r.kind!=='success'){if(r.kind==='http'&&[401,403].includes(r.status))this.auth.rejectSession({message:'로그인 상태를 다시 확인해 주세요.'});throw new Error(apiErrorView(r)?.message??'견적을 확인하지 못했습니다.');}return r.data}
  try{
   const csrf=await request('/api/auth/csrf');if(!object(csrf)||csrf.headerName!=='X-CSRF-TOKEN'||typeof csrf.token!=='string'||!csrf.token)throw new Error('요청 확인 정보를 받지 못했습니다.')
   const q=await request('/api/quotes',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.token},body:JSON.stringify({lines,coupons:Object.entries(selected).map(([storeId,couponId])=>({storeId,couponId}))})})
   if(!quoteValid(q))throw new Error('견적 금액과 배분 결과를 확인하지 못했습니다.')
   const received=q.stores.flatMap(s=>s.lines)
   if(received.length!==lines.length||!lines.every(l=>received.some(r=>r.skuId===l.skuId&&r.quantity===l.quantity))||q.stores.some(s=>(s.coupon?.id??undefined)!==selected[s.storeId])||Object.keys(selected).some(id=>!q.stores.some(s=>s.storeId===id)))throw new Error('선택한 상품·수량·쿠폰과 견적이 다릅니다.')
   if(current())this.set({...this.state,quote:q,busy:false,error:''})
  }catch(e){if(current())this.set({...this.state,quote:null,busy:false,error:e instanceof Error?e.message:'견적 확인 실패'})}
 }
}
