import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
export type ShipmentItem={orderItemId:string;quantity:number}
export type ShipmentEvent={id:string;source:string;actor:string;occurredAt:string;processedAt:string;previousState:string|null;nextState:string;version:number;applied:boolean}
export type Shipment={id:string;sellerOrderId:string;state:string;version:number;provider:string;items:ShipmentItem[];events:ShipmentEvent[]}
export type SellerSummary={sellerOrderId:string;orderNumber:string;orderState:string;total:string;createdAt:string}
export type SellerOrder={sellerOrderId:string;orderNumber:string;orderState:string;total:string;recipient:{name:string;phone:string;postalCode:string;addressLine1:string;addressLine2:string};items:{id:string;title:string;options:string[];quantity:number}[]}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const date=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const states=['PREPARING','SHIPPED','IN_TRANSIT','DELIVERED']
const summary=(v:unknown):v is SellerSummary=>object(v)&&uuid(v.sellerOrderId)&&typeof v.orderNumber==='string'&&/^O[0-9a-f]{32}$/.test(v.orderNumber)&&['PENDING_PAYMENT','PAID','CLOSED','CANCELED','EXPIRED'].includes(String(v.orderState))&&typeof v.total==='string'&&/^(0|[1-9]\d*)$/.test(v.total)&&date(v.createdAt)
const shipment=(v:unknown):v is Shipment=>object(v)&&uuid(v.id)&&uuid(v.sellerOrderId)&&states.includes(String(v.state))&&Number.isSafeInteger(v.version)&&Number(v.version)>=0&&v.provider==='MOCK'&&Array.isArray(v.items)&&v.items.length>0&&v.items.every(i=>object(i)&&uuid(i.orderItemId)&&Number.isInteger(i.quantity)&&Number(i.quantity)>0&&Number(i.quantity)<=99)&&Array.isArray(v.events)&&v.events.every(e=>object(e)&&uuid(e.id)&&typeof e.source==='string'&&typeof e.actor==='string'&&date(e.occurredAt)&&date(e.processedAt)&&(e.previousState===null||states.includes(String(e.previousState)))&&states.includes(String(e.nextState))&&Number.isSafeInteger(e.version)&&typeof e.applied==='boolean')
const sellerOrder=(v:unknown):v is SellerOrder=>object(v)&&uuid(v.sellerOrderId)&&typeof v.orderNumber==='string'&&/^O[0-9a-f]{32}$/.test(v.orderNumber)&&typeof v.orderState==='string'&&typeof v.total==='string'&&object(v.recipient)&&['name','phone','postalCode','addressLine1','addressLine2'].every(k=>typeof (v.recipient as Record<string,unknown>)[k]==='string')&&Array.isArray(v.items)&&v.items.length>0&&v.items.every(i=>object(i)&&uuid(i.id)&&typeof i.title==='string'&&Array.isArray(i.options)&&i.options.every(o=>typeof o==='string')&&Number.isInteger(i.quantity)&&Number(i.quantity)>0)
const empty=()=>({busy:false,shipping:false,blocked:false,error:'',loaded:false,orders:[] as SellerSummary[],nextCursor:null as string|null,order:null as SellerOrder|null,shipments:[] as Shipment[],confirmed:{} as Record<string,boolean>,uncertain:null as string|null})
type Pending={shipmentId:string;sellerOrderId:string;key:string;version:number;items:ShipmentItem[]}
class ReadError extends Error {constructor(readonly status:number){super('read')}}
export class ShipmentStore {
 private state=empty()
 private listeners=new Set<()=>void>()
 private generation=0
 private controller=new AbortController()
 private unsubscribe:(()=>void)|null
 private pending:Pending|null=null
 constructor(private auth:AuthStore,private mode:'seller'|'member'|'guest',private fetcher:typeof fetch=fetch){this.unsubscribe=auth.subscribe(this.clear)}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{if(!this.unsubscribe)this.unsubscribe=this.auth.subscribe(this.clear);this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 clear=()=>{++this.generation;this.controller.abort();this.controller=new AbortController();this.pending=null;this.state=empty();this.listeners.forEach(fn=>fn())}
 dispose=()=>{this.clear();this.unsubscribe?.();this.unsubscribe=null}
 private ready(){const a=this.auth.getSnapshot();return a.checked&&!a.busy&&!a.error&&(this.mode==='guest'||a.member!==null)&&(this.mode!=='seller'||a.member?.roles.includes('SELLER'))}
 private current(g:number){return g===this.generation&&this.ready()}
 private request(path:string,init:RequestInit={}){return requestJson(path,{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',signal:this.controller.signal,...init})}
 private async read(path:string,g:number){const r=await this.request(path);if(!this.current(g))throw new Error('cancelled');if(r.kind!=='success')throw new ReadError(r.kind==='http'?r.status:0);return r.data}
 private fail(error:unknown,g:number){if(!this.current(g))return;const blocked=error instanceof ReadError&&[401,403].includes(error.status);this.pending=null;this.set({...empty(),blocked,error:blocked?'인증이 만료되었습니다. 다시 인증하고 직접 조회해 주세요.':'배송 정보를 확인하지 못했습니다. 조회 인증과 유효시간을 확인하고 다시 시도해 주세요.'})}
 private async run(action:(g:number)=>Promise<void>){this.clear();if(!this.ready())return;const g=this.generation;this.set({busy:true});try{await action(g)}catch(e){this.fail(e,g)}finally{if(this.current(g))this.set({busy:false})}}
 list=async(cursor?:string)=>{await this.run(async g=>{const data=await this.read('/api/seller/orders'+(cursor?'?cursor='+encodeURIComponent(cursor):''),g);if(!object(data)||!Array.isArray(data.orders)||!data.orders.every(summary)||!(data.nextCursor===null||typeof data.nextCursor==='string'))throw new Error('schema');if(this.current(g))this.set({orders:data.orders,nextCursor:data.nextCursor as string|null,loaded:true})})}
 next=async()=>{if(this.state.nextCursor)await this.list(this.state.nextCursor)}
 private async seller(id:string,g:number){if(!uuid(id))throw new Error('id');const [order,rows]=await Promise.all([this.read('/api/seller/orders/'+id,g),this.read('/api/seller/orders/'+id+'/shipments',g)]);if(!sellerOrder(order)||order.sellerOrderId!==id||!Array.isArray(rows)||!rows.every(shipment)||rows.some(s=>s.sellerOrderId!==id||s.items.some(i=>!order.items.some(o=>o.id===i.orderItemId&&o.quantity===i.quantity))))throw new Error('schema');if(this.current(g))this.set({order,shipments:rows,loaded:true})}
 openSeller=async(id:string)=>{await this.run(g=>this.seller(id,g))}
 loadBuyer=async(number:string)=>{await this.run(async g=>{if(!/^O[0-9a-f]{32}$/.test(number))throw new Error('number');const rows=await this.read('/api/orders/'+number+'/shipments',g);if(!Array.isArray(rows)||!rows.every(shipment))throw new Error('schema');if(this.current(g))this.set({shipments:rows,loaded:true})})}
 confirm=(id:string,value:boolean)=>{if(!this.state.shipping&&!this.pending)this.set({confirmed:{...this.state.confirmed,[id]:value}})}
 ship=async(id:string)=>{
  if(this.mode!=='seller'||this.state.busy||this.state.shipping||this.state.blocked||!this.ready()||!this.state.confirmed[id])return
  const row=this.state.shipments.find(s=>s.id===id)
  if(this.pending){if(this.pending.shipmentId!==id)return}else{if(!row||row.state!=='PREPARING'||this.state.order?.orderState!=='PAID')return;this.pending={shipmentId:id,sellerOrderId:this.state.order.sellerOrderId,key:crypto.randomUUID(),version:row.version,items:row.items.map(i=>({...i}))}}
  const g=this.generation,command=this.pending,seller=command.sellerOrderId
  let reading=false,conflict=false;this.set({shipping:true,error:''})
  try{
   const csrf=await this.request('/api/auth/csrf');if(!this.current(g))return
   if(csrf.kind==='http'&&[401,403].includes(csrf.status)){this.deny(g);return}
   if(csrf.kind!=='success'||!object(csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN'||typeof csrf.data.token!=='string')throw new Error('csrf')
   const body={key:command.key,version:command.version,items:command.items}
   const r=await this.request('/api/seller/shipments/'+id+'/ship',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:JSON.stringify(body)})
   if(!this.current(g))return
   if(r.kind==='http'&&[401,403].includes(r.status)){this.deny(g);return}
   if(r.kind==='http'&&r.status===409){this.pending=null;conflict=true;reading=true;await this.seller(seller,g);if(this.current(g))this.set({uncertain:null,error:'배송 정보가 변경되었습니다. 현재 상태를 확인해 주세요.'});return}
   if(r.kind!=='success'){this.set({uncertain:id,order:null,shipments:[],loaded:false});throw new Error('post')}
   // Replayed POST is the original result. Only GET supplies current state.
   reading=true;await this.seller(seller,g);if(this.current(g)){this.pending=null;this.set({uncertain:null})}
  }catch(e){if(this.current(g)){if(e instanceof ReadError&&[401,403].includes(e.status)){this.deny(g);return}if(reading)this.set({order:null,shipments:[],loaded:false,uncertain:conflict?null:id});this.set({error:conflict?'배송 정보가 변경되었지만 현재 조회를 확인하지 못했습니다. 판매 주문 다시 조회로 확인해 주세요.':'발송 응답을 확인하지 못했습니다. 같은 요청으로 결과를 확인해 주세요.'})}}finally{if(this.current(g))this.set({shipping:false})}
 }
 private deny(g:number){if(!this.current(g))return;this.clear();this.set({blocked:true,error:'인증이 만료되었습니다. 다시 로그인한 뒤 직접 확인해 주세요. 자동으로 발송하지 않습니다.'})}
}
