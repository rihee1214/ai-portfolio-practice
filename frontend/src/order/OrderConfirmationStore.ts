import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
export type ConfirmationUnit={itemId:string;ordinal:number}
export type ConfirmationEligibility={unit:ConfirmationUnit;lifecycle:string;unitRevision:number;deliveredAt:string|null;dueAt:string|null;policyStatus:string;eligible:boolean;reason:string}
export type ConfirmationView={orderNumber:string;orderState:string;revision:number;policyStatus:string;units:ConfirmationEligibility[]}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const instant=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const identity=(u:ConfirmationUnit)=>u.itemId+':'+u.ordinal
const empty=()=>({view:null as ConfirmationView|null,busy:false,sending:false,error:'',selected:[] as string[],accepted:false,uncertain:false,blocked:false})
class ReadFailure extends Error {constructor(readonly status:number){super('read')}}
export class OrderConfirmationStore {
 private state=empty();private listeners=new Set<()=>void>();private generation=0;private controller=new AbortController();private unsubscribe:(()=>void)|null
 private pending:{key:string;expectedRevision:number;units:ConfirmationUnit[]}|null=null
 constructor(private auth:AuthStore,private guest:boolean,private number:string,private original:ConfirmationUnit[],private fetcher:typeof fetch=fetch){this.unsubscribe=auth.subscribe(this.clear)}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{if(!this.unsubscribe)this.unsubscribe=this.auth.subscribe(this.clear);this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 clear=()=>{++this.generation;this.controller.abort();this.controller=new AbortController();this.pending=null;this.state=empty();this.listeners.forEach(fn=>fn())}
 dispose=()=>{this.clear();this.unsubscribe?.();this.unsubscribe=null}
 private ready(){const a=this.auth.getSnapshot();return a.checked&&!a.busy&&!a.error&&(this.guest||!!a.member)}
 private current(g:number){return g===this.generation&&this.ready()}
 private request(path:string,options:RequestInit={}){return requestJson(path,{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',signal:this.controller.signal,...options})}
 private valid(v:unknown):v is ConfirmationView {
  if(!object(v)||v.orderNumber!==this.number||!['PAID','CLOSED','PENDING_PAYMENT','CANCELED','EXPIRED'].includes(String(v.orderState))||!Number.isSafeInteger(v.revision)||Number(v.revision)<0||!['ACTIVE','LEGACY_UNSET'].includes(String(v.policyStatus))||!Array.isArray(v.units)||v.units.length!==this.original.length)return false
  const seen=new Set<string>()
  return v.units.every(e=>{if(!object(e)||!object(e.unit)||typeof e.unit.itemId!=='string'||!Number.isInteger(e.unit.ordinal)||!['OPEN','HELD','CONFIRMED','REFUNDED'].includes(String(e.lifecycle))||!Number.isSafeInteger(e.unitRevision)||Number(e.unitRevision)<0||e.policyStatus!==v.policyStatus||typeof e.eligible!=='boolean'||typeof e.reason!=='string'||!(e.deliveredAt===null||instant(e.deliveredAt))||!(e.dueAt===null||instant(e.dueAt)))return false;const u={itemId:e.unit.itemId,ordinal:Number(e.unit.ordinal)},key=identity(u);if(seen.has(key)||!this.original.some(o=>identity(o)===key))return false;seen.add(key);if(e.eligible&&(e.lifecycle!=='OPEN'||e.deliveredAt===null||v.orderState!=='PAID'))return false;return !(e.policyStatus==='ACTIVE'&&e.deliveredAt!==null&&e.dueAt===null)})
 }
 private async read(g:number){const r=await this.request('/api/orders/'+this.number+'/confirmations');if(!this.current(g))throw new Error('cancelled');if(r.kind!=='success')throw new ReadFailure(r.kind==='http'?r.status:0);if(!this.valid(r.data))throw new Error('schema');if(this.current(g))this.set({view:r.data,selected:[],accepted:false})}
 open=async()=>{this.clear();if(!this.ready())return;const g=this.generation;this.set({busy:true});try{await this.read(g)}catch(e){if(this.current(g))this.fail(e,g)}finally{if(this.current(g))this.set({busy:false})}}
 private fail(error:unknown,g:number){if(!this.current(g))return;const blocked=error instanceof ReadFailure&&[401,403,404].includes(error.status);this.pending=null;if(blocked)this.auth.rejectSession({message:'조회 인증을 다시 확인한 뒤 직접 조회해 주세요.'});this.set({...empty(),blocked,error:blocked?'인증을 다시 확인한 뒤 직접 조회해 주세요.':'구매 확정 정보를 확인하지 못했습니다. 다시 조회해 주세요.'})}
 select=(unit:ConfirmationUnit,checked:boolean)=>{if(this.state.sending||this.pending||!this.state.view?.units.some(e=>identity(e.unit)===identity(unit)&&e.eligible))return;this.set({selected:checked?[...new Set([...this.state.selected,identity(unit)])]:this.state.selected.filter(k=>k!==identity(unit)),accepted:false})}
 accept=(value:boolean)=>{if(!this.state.sending&&!this.pending)this.set({accepted:value})}
 confirm=async()=>{if(!this.ready()||this.state.busy||this.state.sending||this.state.blocked)return;if(!this.pending){if(!this.state.accepted||!this.state.view||this.state.selected.length===0)return;this.pending={key:crypto.randomUUID(),expectedRevision:this.state.view.revision,units:this.state.view.units.filter(e=>e.eligible&&this.state.selected.includes(identity(e.unit))).map(e=>({...e.unit}))};if(this.pending.units.length!==this.state.selected.length){this.pending=null;return}}
  const body=this.pending,g=this.generation;let conflict=false;this.set({sending:true,error:''})
  try{const csrf=await this.request('/api/auth/csrf');if(!this.current(g))return;if(csrf.kind==='http'&&[401,403,404].includes(csrf.status))throw new ReadFailure(csrf.status);if(csrf.kind!=='success'||!object(csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN'||typeof csrf.data.token!=='string')throw new Error('csrf')
   const result=await this.request('/api/orders/'+this.number+'/confirm',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:JSON.stringify(body)});if(!this.current(g))return;if(result.kind==='http'&&[401,403,404].includes(result.status))throw new ReadFailure(result.status);if(result.kind==='http'&&[400,409].includes(result.status)){conflict=true;this.pending=null;this.set({view:null,selected:[],accepted:false,uncertain:false});await this.read(g);if(this.current(g))this.set({error:'확정 대상이 변경되었습니다. 현재 수량을 확인해 주세요.'});return}if(result.kind!=='success')throw new Error('post');this.set({view:null,selected:[],accepted:false});await this.read(g);if(this.current(g)){this.pending=null;this.set({uncertain:false})}
  }catch(e){if(!this.current(g))return;if(e instanceof ReadFailure&&[401,403,404].includes(e.status)){this.fail(e,g);return}this.set({view:null,selected:[],accepted:false,uncertain:!conflict&&this.pending!==null,error:conflict?'확정 대상이 변경되었지만 현재 정보를 확인하지 못했습니다. 다시 조회해 주세요.':'구매 확정 응답을 확인하지 못했습니다. 같은 요청으로 결과를 확인해 주세요.'})}finally{if(this.current(g))this.set({sending:false})}
 }
}
