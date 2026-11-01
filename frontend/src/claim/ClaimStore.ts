import {requestJson} from '../api/http'
import {uuid} from '../cart/GuestCart'
import {decimalAmount} from '../catalog/ReturnPolicy'
import type {AuthStore} from '../auth/AuthStore'
import type {OrderDetail} from '../order/OrderQueryStore'
export type ClaimUnit={itemId:string;ordinal:number;net:string}
export type ClaimQuote={fingerprint:string;policyVersion:string;quotedAt:string;revision:number;units:ClaimUnit[];itemAmount:string;conditionalShippingMaximum:string;shippingCondition:string}
export type ClaimReceipt={claimId:string;refundAttemptId:string;policyVersion:string;revision:number;units:ClaimUnit[];itemAmount:string;state:string}
export type ClaimProgress={accepted:ClaimReceipt;state:string;version:number;refunds:{attemptId:string;purpose:string;sequence:number;amount:string;state:string;version:number;reference:string|null;resultAt:string|null}[];history:{action:string;version:number;occurredAt:string}[]}
export function latestRefund(p:ClaimProgress,purpose:'ITEM'|'SHIPPING') {return p.refunds.filter(r=>r.purpose===purpose).reduce<ClaimProgress['refunds'][number]|undefined>((latest,r)=>!latest||r.sequence>latest.sequence?r:latest,undefined)}
export function canWithdrawClaim(p:ClaimProgress){const latest=latestRefund(p,'ITEM');return ['APPROVED','REVIEW_REQUIRED'].includes(p.state)&&!!latest&&['RESERVED','FAILED'].includes(latest.state)}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const instant=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const revision=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0
const unitKey=(u:{itemId:string;ordinal:number})=>u.itemId+':'+u.ordinal
const empty=()=>({progress:null as ClaimProgress[]|null,quote:null as ClaimQuote|null,busy:false,sending:false,error:'',selected:[] as string[],reason:'',accepted:false,uncertain:false,blocked:false})
class ReadFailure extends Error {constructor(readonly status:number){super('claim request')}}
type Pending={kind:'cancel'|'withdraw';id?:string;body:Record<string,unknown>;units?:ClaimUnit[];amount?:string}
export class ClaimStore {
 private state=empty();private listeners=new Set<()=>void>();private generation=0;private controller=new AbortController();private unsubscribe:(()=>void)|null=null;private pending:Pending|null=null
 private originals:Map<string,ClaimUnit>;private sellers:Map<string,string>
 constructor(private auth:AuthStore,private guest:boolean,private detail:OrderDetail,private fetcher:typeof fetch=fetch){this.originals=new Map(detail.stores.flatMap(s=>s.items.flatMap(i=>i.units.map(u=>{const unit={itemId:i.id,ordinal:u.ordinal,net:u.net};return [unitKey(unit),unit] as const}))));this.sellers=new Map(detail.stores.flatMap(s=>s.items.map(i=>[i.id,s.sellerOrderId])))}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{this.connect();this.listeners.add(fn);return()=>{this.listeners.delete(fn);if(!this.listeners.size)this.dispose()}}
 private connect(){if(!this.unsubscribe)this.unsubscribe=this.auth.subscribe(this.clear)}
 private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 clear=()=>{++this.generation;this.controller.abort();this.controller=new AbortController();this.pending=null;this.state=empty();this.listeners.forEach(fn=>fn())}
 dispose=()=>{this.clear();this.unsubscribe?.();this.unsubscribe=null}
 private ready(){const a=this.auth.getSnapshot();return a.checked&&!a.busy&&!a.error&&(this.guest||a.member!==null)}
 private current(g:number){return g===this.generation&&this.ready()}
 private path(){return '/api/orders/'+encodeURIComponent(this.detail.orderNumber)+'/cancellations'}
 private request(path:string,init:RequestInit={}){return requestJson(path,{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',signal:AbortSignal.any([this.controller.signal,AbortSignal.timeout(10000)]),...init})}
 private unitsValid(value:unknown,expected?:ClaimUnit[]):value is ClaimUnit[]{if(!Array.isArray(value)||!value.length||value.length>this.originals.size)return false;const seen=new Set<string>();return value.every(u=>{if(!object(u)||typeof u.itemId!=='string'||!Number.isInteger(u.ordinal)||!decimalAmount(u.net))return false;const key=unitKey({itemId:u.itemId,ordinal:Number(u.ordinal)});if(seen.has(key)||this.originals.get(key)?.net!==u.net)return false;seen.add(key);return !expected||expected.some(e=>unitKey(e)===key)})&&(!expected||value.length===expected.length)}
 private receiptValid(value:unknown,expected?:Pending):value is ClaimReceipt {if(!object(value)||!uuid(value.claimId)||!uuid(value.refundAttemptId)||value.policyVersion!=='P09-C-CANCEL-1'||value.state!=='APPROVED'||!revision(value.revision)||!decimalAmount(value.itemAmount)||!this.unitsValid(value.units,expected?.units))return false;const sum=value.units.reduce((n,u)=>n+BigInt(u.net),0n);return sum===BigInt(value.itemAmount)&&new Set(value.units.map(u=>this.sellers.get(u.itemId))).size===1&&(!expected||value.itemAmount===expected.amount)}
 private progressValid(value:unknown):value is ClaimProgress[]{
  if(!Array.isArray(value))return false
  const ids=new Set<string>()
  return value.every(p=>{
   if(!object(p)||!this.receiptValid(p.accepted)||ids.has(p.accepted.claimId)||!['APPROVED','REVIEW_REQUIRED','COMPLETED','SETTLEMENT_PENDING','WITHDRAWN'].includes(String(p.state))||!revision(p.version)||!Array.isArray(p.refunds)||!p.refunds.length||!Array.isArray(p.history))return false
   ids.add(p.accepted.claimId)
   const accepted=p.accepted,seller=this.detail.stores.find(s=>s.sellerOrderId===this.sellers.get(accepted.units[0].itemId))!,shipping=BigInt(seller.shippingBase)-BigInt(seller.shippingDiscount),attempts=new Set<string>(),sequences=new Set<string>()
   const valid=p.refunds.every(r=>{
    if(!object(r)||!uuid(r.attemptId)||attempts.has(r.attemptId)||!['ITEM','SHIPPING'].includes(String(r.purpose))||!Number.isSafeInteger(r.sequence)||Number(r.sequence)<1||!decimalAmount(r.amount)||!['RESERVED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','WITHDRAWN'].includes(String(r.state))||!revision(r.version))return false
    const sequence=r.purpose+':'+r.sequence
    if(sequences.has(sequence)||(r.purpose==='ITEM'?r.amount!==accepted.itemAmount:BigInt(r.amount)!==shipping))return false
    sequences.add(sequence)
    if(r.state==='SUCCEEDED'){
     if(typeof r.reference!=='string'||!r.reference.trim()||r.reference.length>200||!instant(r.resultAt))return false
    }else if(r.reference!==null||r.resultAt!==null)return false
    attempts.add(r.attemptId)
    return true
   })
   if(!valid||!attempts.has(accepted.refundAttemptId))return false
   const progress=p as unknown as ClaimProgress,item=latestRefund(progress,'ITEM'),shippingResult=latestRefund(progress,'SHIPPING')
   if(!item||p.refunds.find(r=>object(r)&&r.attemptId===accepted.refundAttemptId)?.purpose!=='ITEM')return false
   // T3 may precede its business-result worker. Pending accepts saved outcomes without announcing completion.
   if(p.state==='COMPLETED'&&(item.state!=='SUCCEEDED'||shippingResult&&shippingResult.state!=='SUCCEEDED'))return false
   if(p.state==='SETTLEMENT_PENDING'&&(item.state!=='SUCCEEDED'||!shippingResult))return false
   if(p.state==='WITHDRAWN'&&(!['WITHDRAWN','FAILED'].includes(item.state)||shippingResult))return false
   return p.history.every(h=>object(h)&&typeof h.action==='string'&&revision(h.version)&&instant(h.occurredAt))
  })
 }
 private async data(path:string,g:number,init:RequestInit={}){const r=await this.request(path,init);if(!this.current(g))throw new Error('cancelled');if(r.kind!=='success')throw new ReadFailure(r.kind==='http'?r.status:0);return r.data}
 private async csrf(g:number){const r=await this.data('/api/auth/csrf',g);if(!object(r)||r.headerName!=='X-CSRF-TOKEN'||typeof r.token!=='string'||!r.token)throw new Error('csrf');return {'Content-Type':'application/json','X-CSRF-TOKEN':r.token}}
 private async read(g:number){const p=await this.data(this.path(),g);if(!this.progressValid(p))throw new Error('claim schema');if(this.current(g))this.set({progress:p})}
 private fail(error:unknown,g:number,message:string){if(!this.current(g))return;const blocked=error instanceof ReadFailure&&[401,403,404].includes(error.status);this.pending=null;if(blocked)this.auth.rejectSession({message:'조회 인증을 다시 확인해 주세요.'});this.set({...empty(),blocked,error:blocked?'인증을 다시 확인한 뒤 직접 조회해 주세요.':message})}
 open=async()=>{this.connect();if(this.pending||this.state.sending)return;this.clear();if(!this.ready())return;const g=this.generation;this.set({busy:true});try{await this.read(g)}catch(e){this.fail(e,g,'취소 진행을 확인하지 못했습니다. 다시 조회해 주세요.')}finally{if(this.current(g))this.set({busy:false})}}
 select=(unit:{itemId:string;ordinal:number},checked:boolean)=>{if(this.pending||this.state.busy||this.state.sending||!this.originals.has(unitKey(unit)))return;const seller=this.sellers.get(unit.itemId);if(checked&&this.state.selected.some(key=>this.sellers.get(this.originals.get(key)!.itemId)!==seller))return;this.set({selected:checked?[...new Set([...this.state.selected,unitKey(unit)])]:this.state.selected.filter(k=>k!==unitKey(unit)),quote:null,accepted:false,error:''})}
 setReason=(value:string)=>{if(!this.pending&&!this.state.busy&&!this.state.sending)this.set({reason:value,quote:null,accepted:false,error:''})}
 accept=(value:boolean)=>{if(!this.pending&&!this.state.sending)this.set({accepted:value})}
 private reasonValid(value:string){return !!value.trim()&&value.length<=500&&[...value].every(c=>{const n=c.codePointAt(0)!;return n>=32&&(n<127||n>159)})}
 quote=async()=>{this.connect();if(!this.ready()||this.pending||this.state.busy||this.state.sending||!this.reasonValid(this.state.reason)||!this.state.selected.length)return;const g=this.generation,units=this.state.selected.map(k=>this.originals.get(k)!).sort((a,b)=>unitKey(a).localeCompare(unitKey(b))),reason=this.state.reason;this.set({busy:true,quote:null,accepted:false,error:''});try{const view=await this.data('/api/orders/'+this.detail.orderNumber+'/confirmations',g);if(!object(view)||!revision(view.revision))throw new Error('revision');const value=await this.data(this.path()+'/quote',g,{method:'POST',headers:await this.csrf(g),body:JSON.stringify({units:units.map(({itemId,ordinal})=>({itemId,ordinal})),reason,expectedRevision:view.revision})});const seller=this.detail.stores.find(s=>s.sellerOrderId===this.sellers.get(units[0].itemId))!;if(!object(value)||typeof value.fingerprint!=='string'||! /^[a-f0-9]{64}$/.test(value.fingerprint)||value.policyVersion!=='P09-C-CANCEL-1'||!instant(value.quotedAt)||value.revision!==view.revision||!this.unitsValid(value.units,units)||!decimalAmount(value.itemAmount)||value.units.reduce((n,u)=>n+BigInt(u.net),0n)!==BigInt(value.itemAmount)||!decimalAmount(value.conditionalShippingMaximum)||BigInt(value.conditionalShippingMaximum)!==BigInt(seller.shippingBase)-BigInt(seller.shippingDiscount)||value.shippingCondition!=='ALL_ORIGINAL_UNITS_UNSHIPPED_CANCELLATION_ITEMS_SUCCEEDED')throw new Error('quote schema');if(this.current(g))this.set({quote:value as unknown as ClaimQuote})}catch(e){this.fail(e,g,'취소 견적을 확인하지 못했습니다. 현재 발송·수량 상태와 사유를 확인해 주세요.')}finally{if(this.current(g))this.set({busy:false})}}
 cancel=async()=>{this.connect();if(!this.ready()||this.state.busy||this.state.sending||this.state.blocked)return;if(!this.pending){const q=this.state.quote;if(!q||!this.state.accepted)return;this.pending={kind:'cancel',units:q.units.map(u=>({...u})),amount:q.itemAmount,body:{key:crypto.randomUUID(),units:q.units.map(({itemId,ordinal})=>({itemId,ordinal})),reason:this.state.reason,expectedRevision:q.revision,quoteFingerprint:q.fingerprint}}}await this.send()}
 retry=async()=>{if(this.pending&&!this.state.sending&&!this.state.busy&&this.ready())await this.send()}
 withdraw=async(id:string,reason:string)=>{if(this.pending||this.state.busy||this.state.sending||!this.ready()||!this.reasonValid(reason))return;const p=this.state.progress?.find(p=>p.accepted.claimId===id);if(!p||!canWithdrawClaim(p))return;this.pending={kind:'withdraw',id,body:{key:crypto.randomUUID(),expectedRevision:p.version,reason}};await this.send()}
 private async send(){const pending=this.pending;if(!pending)return;const g=this.generation;let conflict=false;this.set({sending:true,error:''});try{const value=await this.data(this.path()+(pending.kind==='withdraw'?'/'+pending.id+'/withdraw':''),g,{method:'POST',headers:await this.csrf(g),body:JSON.stringify(pending.body)});if(pending.kind==='cancel'?!this.receiptValid(value,pending):!object(value)||value.claimId!==pending.id||value.state!=='WITHDRAWN'||!revision(value.version)||!instant(value.withdrawnAt))throw new Error('receipt schema');this.set({progress:null,quote:null,selected:[],accepted:false,reason:''});await this.read(g);if(this.current(g)){this.pending=null;this.set({uncertain:false})}}catch(e){if(!this.current(g))return;if(e instanceof ReadFailure&&[401,403,404].includes(e.status)){this.fail(e,g,'');return}if(e instanceof ReadFailure&&[400,409].includes(e.status)){conflict=true;this.pending=null;this.set({...empty(),sending:true});try{await this.read(g)}catch(read){if(read instanceof ReadFailure&&[401,403,404].includes(read.status)){this.fail(read,g,'');return}}}if(this.current(g))this.set({progress:conflict?this.state.progress:null,quote:null,selected:[],accepted:false,reason:'',uncertain:!conflict&&this.pending!==null,error:conflict?'취소 대상이 변경되었습니다. 현재 정보를 다시 조회해 주세요.':'취소 응답을 확인하지 못했습니다. 같은 요청으로 결과를 확인해 주세요.'})}finally{if(this.current(g))this.set({sending:false})}}
}
