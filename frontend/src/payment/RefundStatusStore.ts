import {guestAccessGeneration,onGuestAccessChange} from '../guest/GuestAccessLifecycle'
import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
import {decimalAmount} from '../catalog/ReturnPolicy'
export type Refund={attemptId:string;sellerOrderId:string;claimId:string;purpose:'ITEM'|'SHIPPING';sequence:number;amount:string;state:string;version:number;mode:string;automaticQueries:number;reviewRequired:boolean;nextDue:string|null;deadline:string|null;providerReceipt:boolean;providerReference:string|null;resultAt:string|null}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const time=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const uuid=(v:unknown)=>typeof v==='string'&&/^[0-9a-f-]{36}$/i.test(v)
export function isRefundStatus(v:unknown):v is Refund{return object(v)&&['attemptId','sellerOrderId','claimId'].every(k=>uuid(v[k]))&&['ITEM','SHIPPING'].includes(String(v.purpose))&&Number.isSafeInteger(v.sequence)&&Number(v.sequence)>0&&decimalAmount(v.amount)&&Number.isSafeInteger(v.version)&&Number(v.version)>=0&&['RESERVED','PROCESSING','UNKNOWN','FAILED','SUCCEEDED','WITHDRAWN'].includes(String(v.state))&&['DEMO','SANDBOX'].includes(String(v.mode))&&Number.isInteger(v.automaticQueries)&&Number(v.automaticQueries)>=0&&Number(v.automaticQueries)<=5&&typeof v.reviewRequired==='boolean'&&typeof v.providerReceipt==='boolean'&&(v.nextDue===null||time(v.nextDue))&&(v.deadline===null||time(v.deadline))&&(v.state!=='SUCCEEDED'||typeof v.providerReference==='string'&&v.providerReference.length>0&&time(v.resultAt))}

export const refundStatusText=(r:Refund)=>r.state==='SUCCEEDED'?'제공자 환불 완료':r.state==='FAILED'?'환불 확정 실패':r.state==='WITHDRAWN'?'전송 전 환불 철회':r.reviewRequired?'운영 확인 필요':'환불 확인 중'

type State={rows:Refund[];error:boolean;busy:boolean;available:boolean}
export class RefundStatusStore {
 private state:State={rows:[],error:false,busy:false,available:false}
 private listeners=new Set<()=>void>();private generation=0;private controller=new AbortController();private unsubscribeAuth:(()=>void)|null=null;private unsubscribeGuest:(()=>void)|null=null
 constructor(private readonly auth:AuthStore,private readonly number:string,private readonly guest:boolean){}
 getSnapshot=()=>this.state
 subscribe=(listener:()=>void)=>{this.listeners.add(listener);return()=>{this.listeners.delete(listener)}}
 private ready(){const s=this.auth.getSnapshot();return s.checked&&!s.busy&&!s.error&&(this.guest||!!s.member)}
 private set(state:State){this.state=state;this.listeners.forEach(listener=>listener())}
 private clear(){this.generation++;this.controller.abort();this.controller=new AbortController();this.set({rows:[],error:false,busy:false,available:this.ready()})}
 connect=()=>{this.unsubscribeAuth?.();this.unsubscribeGuest?.();if(this.guest)this.unsubscribeGuest=onGuestAccessChange(()=>this.clear());this.unsubscribeAuth=this.auth.subscribe(()=>{this.clear();if(this.ready())void this.load()});void this.load();return this.disconnect}
 disconnect=()=>{this.unsubscribeAuth?.();this.unsubscribeAuth=null;this.unsubscribeGuest?.();this.unsubscribeGuest=null;this.clear()}
 load=async()=>{this.clear();if(!this.ready())return;const current=this.generation,controller=this.controller,guestGeneration=guestAccessGeneration();this.set({...this.state,busy:true})
  const result=await requestJson('/api/orders/'+encodeURIComponent(this.number)+'/refunds',{cache:'no-store',credentials:'same-origin',signal:AbortSignal.any([controller.signal,AbortSignal.timeout(10000)])})
  if(current!==this.generation||controller.signal.aborted||!this.ready()||this.guest&&guestGeneration!==guestAccessGeneration())return
  if(result.kind==='success'&&Array.isArray(result.data)&&result.data.every(isRefundStatus)&&new Set(result.data.map(r=>r.attemptId)).size===result.data.length){this.set({rows:result.data,error:false,busy:false,available:true})}else{this.set({rows:[],error:true,busy:false,available:true})}
 }
}
