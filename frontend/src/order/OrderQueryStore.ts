import {onGuestAccessChange} from '../guest/GuestAccessLifecycle'
import {decimalAmount,returnPolicyValid,type ReturnPolicy} from '../catalog/ReturnPolicy'
import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
export type OrderSummary={orderNumber:string;state:string;total:string;mode:string;createdAt:string}
export type OrderUnit={ordinal:number;gross:string;promotionDiscount:string;couponDiscount:string;net:string}
export type OrderItem={id:string;skuId:string;title:string;options:string[];quantity:number;unitPrice:string;gross:string;promotionTitle:string;promotionDiscount:string;couponDiscount:string;net:string;units:OrderUnit[];returnPolicy?:ReturnPolicy|null}
export type OrderStore={sellerOrderId:string;storeId:string;name:string;subtotal:string;productDiscount:string;couponTitle:string;couponDiscount:string;shippingBase:string;shippingPromotionTitle:string;shippingDiscount:string;total:string;items:OrderItem[]}
export type OrderDetail=OrderSummary&{policyVersion:string;contact:{buyerName:string;buyerPhone:string;recipientName:string;recipientPhone:string;postalCode:string;addressLine1:string;addressLine2:string};stores:OrderStore[]}
export type PaymentHistory={orderNumber:string;orderState:string;paymentState:string;compensation:string;reviewRequired:boolean;amount:string;mode:string;events:{action:string;occurredAt:string}[]}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const money=decimalAmount
const number=(v:unknown)=>typeof v==='string'&&/^O[0-9a-f]{32}$/.test(v)
const summary=(v:unknown):v is OrderSummary=>object(v)&&number(v.orderNumber)&&typeof v.state==='string'&&['PENDING_PAYMENT','PAID','CLOSED','CANCELED','EXPIRED'].includes(v.state)&&money(v.total)&&['DEMO','SANDBOX'].includes(String(v.mode))&&typeof v.createdAt==='string'&&Number.isFinite(Date.parse(v.createdAt))
const unit=(v:unknown):boolean=>object(v)&&Number.isInteger(v.ordinal)&&['gross','promotionDiscount','couponDiscount','net'].every(k=>money(v[k]))
const item=(v:unknown):boolean=>object(v)&&(v.returnPolicy===undefined||v.returnPolicy===null||returnPolicyValid(v.returnPolicy))&&typeof v.id==='string'&&typeof v.skuId==='string'&&typeof v.title==='string'&&Array.isArray(v.options)&&v.options.every(o=>typeof o==='string')&&Number.isInteger(v.quantity)&&Number(v.quantity)>0&&Number(v.quantity)<=99&&['unitPrice','gross','promotionDiscount','couponDiscount','net'].every(k=>money(v[k]))&&Array.isArray(v.units)&&v.units.length===v.quantity&&v.units.every(unit)
const detail=(v:unknown):v is OrderDetail=>object(v)&&typeof v.policyVersion==='string'&&object(v.contact)&&['buyerName','buyerPhone','recipientName','recipientPhone','postalCode','addressLine1','addressLine2'].every(k=>typeof (v.contact as Record<string,unknown>)[k]==='string')&&Array.isArray(v.stores)&&v.stores.length>0&&v.stores.every(s=>object(s)&&typeof s.sellerOrderId==='string'&&typeof s.storeId==='string'&&typeof s.name==='string'&&['subtotal','productDiscount','couponDiscount','shippingBase','shippingDiscount','total'].every(k=>money(s[k]))&&Array.isArray(s.items)&&s.items.length>0&&s.items.every(item))&&summary(v)
const history=(v:unknown):v is PaymentHistory=>object(v)&&number(v.orderNumber)&&typeof v.orderState==='string'&&['NONE','CREATED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN'].includes(String(v.paymentState))&&['NONE','PENDING','UNKNOWN','CANCELED','FAILED'].includes(String(v.compensation))&&typeof v.reviewRequired==='boolean'&&money(v.amount)&&['DEMO','SANDBOX'].includes(String(v.mode))&&Array.isArray(v.events)&&v.events.every(e=>object(e)&&typeof e.action==='string'&&typeof e.occurredAt==='string'&&Number.isFinite(Date.parse(e.occurredAt)))
const empty=()=>({busy:false,error:'',orders:[] as OrderSummary[],nextCursor:null as string|null,detail:null as OrderDetail|null,history:null as PaymentHistory|null})
export class OrderQueryStore {
 private state=empty()
 private listeners=new Set<()=>void>()
 private generation=0
 private controller=new AbortController()
 private unsubscribe:(()=>void)|null=null
 private unsubscribeGuest:(()=>void)|null=null
 constructor(private auth:AuthStore,private mode:'member'|'guest',private fetcher:typeof fetch=fetch){}
 private connect(){if(!this.unsubscribe)this.unsubscribe=this.auth.subscribe(()=>this.clear());if(this.mode==='guest'&&!this.unsubscribeGuest)this.unsubscribeGuest=onGuestAccessChange(()=>this.clear())}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{this.connect();this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 clear=()=>{++this.generation;this.controller.abort();this.controller=new AbortController();this.state=empty();this.listeners.forEach(fn=>fn())}
 dispose=()=>{this.clear();this.unsubscribe?.();this.unsubscribe=null;this.unsubscribeGuest?.();this.unsubscribeGuest=null}
 private ready(){const a=this.auth.getSnapshot();return a.checked&&!a.busy&&!a.error&&(this.mode==='guest'||a.member!==null)}
 private async read(path:string,generation:number){const result=await requestJson(path,{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',signal:this.controller.signal});if(generation!==this.generation||!this.ready())throw new Error('cancelled');if(result.kind!=='success')throw new Error('request');return result.data}
 private async run(action:(generation:number)=>Promise<void>){this.connect();this.clear();if(!this.ready())return;const generation=this.generation;this.set({busy:true});try{await action(generation)}catch{if(generation===this.generation)this.set({...empty(),error:'주문 정보를 확인하지 못했습니다. 조회 인증과 유효시간을 확인하고 다시 시도해 주세요.'})}finally{if(generation===this.generation)this.set({busy:false})}}
 load=async(cursor?:string)=>{await this.run(async generation=>{const data=await this.read((this.mode==='guest'?'/api/guest/orders':'/api/account/orders')+(cursor?'?cursor='+encodeURIComponent(cursor):''),generation);if(!object(data)||!Array.isArray(data.orders)||!data.orders.every(summary)||!(data.nextCursor===null||typeof data.nextCursor==='string'))throw new Error('schema');if(generation!==this.generation||!this.ready())return;this.set({orders:data.orders,nextCursor:data.nextCursor as string|null})})}
 next=async()=>{const cursor=this.state.nextCursor;if(cursor)await this.load(cursor)}
 open=async(orderNumber:string)=>{await this.run(async generation=>{if(!number(orderNumber))throw new Error('number');const [saved,payment]=await Promise.all([this.read('/api/orders/'+encodeURIComponent(orderNumber),generation),this.read('/api/payments/'+encodeURIComponent(orderNumber)+'/history',generation)]);if(!detail(saved)||!history(payment)||saved.orderNumber!==orderNumber||payment.orderNumber!==orderNumber||saved.state!==payment.orderState||saved.total!==payment.amount||saved.mode!==payment.mode)throw new Error('schema');if(generation!==this.generation||!this.ready())return;this.set({detail:saved,history:payment})})}
}
