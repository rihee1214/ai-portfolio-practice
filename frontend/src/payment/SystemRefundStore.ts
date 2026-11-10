import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
import {isRefundStatus,type Refund} from './RefundStatusStore'

type Evidence={operation:'POST'|'GET';state:'SUCCEEDED'|'UNKNOWN';transactionKey:string|null;fingerprint:string|null;observedAt:string}
type Accepted={operationId:string;attemptId:string;acceptedVersion:number;action:'QUERY'|'RECONCILE';state:string}
type Action='query'|'reconcile'
type Pending={id:string;action:Action;body:{key:string;expectedVersion:number;reason:string;evidenceReference?:string;transactionKey?:string}}
type State={rows:Refund[];selected:Refund|null;evidence:Evidence[];receipt:Accepted|null;busy:boolean;sending:boolean;uncertain:boolean;blocked:boolean;available:boolean;error:string;notice:string;reason:string;evidenceReference:string;transactionKey:string;lookupId:string}
const empty=():State=>({rows:[],selected:null,evidence:[],receipt:null,busy:false,sending:false,uncertain:false,blocked:false,available:false,error:'',notice:'',reason:'',evidenceReference:'',transactionKey:'',lookupId:''})
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const uuid=(v:unknown)=>typeof v==='string'&&/^[0-9a-f-]{36}$/i.test(v)
const text=(v:string,max:number)=>!!v.trim()&&v.length<=max&&[...v].every(c=>{const n=c.codePointAt(0)!;return n>31&&(n<127||n>159)})
class ReadFailure extends Error {constructor(readonly status:number){super('refund operation response')}}

/** Consumes F's audited query-only operations; never submits or assigns a customer refund. */
export class SystemRefundStore {
 private state=empty();private listeners=new Set<()=>void>();private generation=0;private controller=new AbortController();private unsubscribe:(()=>void)|null=null;private pending:Pending|null=null
 constructor(private readonly auth:AuthStore){}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(patch:Partial<State>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 private ready(){const a=this.auth.getSnapshot();return a.checked&&!a.busy&&!a.error&&!!a.member}
 private current(g:number){return g===this.generation&&this.ready()}
 private reset(){this.generation++;this.controller.abort();this.controller=new AbortController();this.pending=null;this.state={...empty(),available:this.ready()};this.listeners.forEach(fn=>fn())}
 connect=()=>{this.unsubscribe?.();this.unsubscribe=this.auth.subscribe(()=>{this.reset();if(this.ready())void this.load()});void this.load();return this.disconnect}
 disconnect=()=>{this.unsubscribe?.();this.unsubscribe=null;this.reset()}
 revalidate=()=>this.auth.invalidate()
 private async data(path:string,g:number,init:RequestInit={}){
  const result=await requestJson(path,{credentials:'same-origin',cache:'no-store',signal:AbortSignal.any([this.controller.signal,AbortSignal.timeout(10000)]),...init})
  if(!this.current(g))throw new ReadFailure(-1)
  if(result.kind!=='success')throw new ReadFailure(result.kind==='http'?result.status:0)
  return {data:result.data,status:result.status}
 }
 private path(id?:string){return '/api/admin/system/refunds'+(id?'/'+encodeURIComponent(id):'')}
 private deny(error:unknown,g:number){
  if(!this.current(g))return
  if(error instanceof ReadFailure&&[401,403,404].includes(error.status)){
   const unauthorized=error.status===401;this.reset();this.set({blocked:true,error:'로그인과 현재 SYSTEM 권한을 다시 확인해 주세요.'});if(unauthorized)this.auth.rejectSession({message:'로그인이 필요합니다.'});return
  }
  this.set({selected:null,evidence:[],error:'환불 정보를 확인하지 못했습니다. 현재 상태를 다시 조회해 주세요.'})
 }
 private async readSelected(id:string,g:number){
  const [detail,proof]=await Promise.all([this.data(this.path(id),g),this.data(this.path(id)+'/evidence',g)])
  if(!isRefundStatus(detail.data)||detail.data.attemptId!==id||!Array.isArray(proof.data))throw new ReadFailure(0)
  const evidence:Evidence[]=proof.data.map(v=>{
   if(!object(v)||!['POST','GET'].includes(String(v.operation))||!['SUCCEEDED','UNKNOWN'].includes(String(v.state))||typeof v.observed_at!=='string'||!Number.isFinite(Date.parse(v.observed_at))||(v.state==='UNKNOWN'?(v.transaction_key!==null||v.response_fingerprint!==null):!(typeof v.transaction_key==='string'&&v.transaction_key.trim().length>0&&v.transaction_key.length<=200&&typeof v.response_fingerprint==='string'&&/^[0-9a-f]{64}$/i.test(v.response_fingerprint))))throw new ReadFailure(0)
   // Deliberately project only metadata; provider raw JSON/payment credentials are never view state.
   return {operation:v.operation as Evidence['operation'],state:v.state as Evidence['state'],transactionKey:v.transaction_key as string|null,fingerprint:v.response_fingerprint as string|null,observedAt:v.observed_at}
  })
  if(this.current(g))this.set({selected:detail.data,evidence})
 }
 load=async()=>{
  if(this.pending||this.state.sending)return
  const selected=this.state.selected?.attemptId;this.reset();if(!this.ready())return
  const g=this.generation;this.set({busy:true})
  try{const result=await this.data(this.path(),g);if(!Array.isArray(result.data)||result.data.length>50||!result.data.every(isRefundStatus)||new Set(result.data.map(r=>r.attemptId)).size!==result.data.length)throw new ReadFailure(0);if(this.current(g))this.set({rows:result.data});if(selected)await this.readSelected(selected,g)}catch(e){this.deny(e,g)}finally{if(this.current(g))this.set({busy:false})}
 }
 select=async(id:string)=>{
  if(this.pending||this.state.busy||this.state.sending||!this.ready()||!uuid(id))return
  const rows=this.state.rows;this.reset();const g=this.generation;this.set({rows,busy:true})
  try{await this.readSelected(id,g)}catch(e){this.deny(e,g)}finally{if(this.current(g))this.set({busy:false})}
 }
 edit=(field:'reason'|'evidenceReference'|'transactionKey'|'lookupId',value:string)=>{if(!this.pending&&!this.state.busy&&!this.state.sending&&!this.state.blocked)this.set({[field]:value,error:'',notice:''})}
 command=async(action:Action)=>{
  const s=this.state,r=s.selected;if(!r||!this.ready()||s.busy||s.sending||s.uncertain||s.blocked||!['PROCESSING','UNKNOWN'].includes(r.state))return
  if(!text(s.reason,500)||action==='reconcile'&&(!text(s.evidenceReference,2000)||!text(s.transactionKey,200))){this.set({error:'처리 사유와 필요한 근거를 정확히 입력해 주세요.'});return}
  this.pending={id:r.attemptId,action,body:{key:crypto.randomUUID(),expectedVersion:r.version,reason:s.reason,...(action==='reconcile'?{evidenceReference:s.evidenceReference,transactionKey:s.transactionKey}:{})}}
  await this.send()
 }
 retry=async()=>{if(this.pending&&!this.state.sending&&!this.state.busy&&this.ready())await this.send()}
 private async send(){
  const p=this.pending;if(!p)return;const g=this.generation;this.set({sending:true,uncertain:false,error:'',notice:''})
  try{
   const csrf=await this.data('/api/auth/csrf',g);if(!object(csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN'||typeof csrf.data.token!=='string'||!csrf.data.token)throw new ReadFailure(0)
   const result=await this.data(this.path(p.id)+'/'+p.action,g,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:JSON.stringify(p.body)})
   const a=result.data,action=p.action==='query'?'QUERY':'RECONCILE',state=p.action==='query'?'QUERY_REQUESTED':'EVIDENCE_RECORDED_QUERY_ONLY'
   if(result.status!==202||!object(a)||!uuid(a.operationId)||a.attemptId!==p.id||a.acceptedVersion!==p.body.expectedVersion||a.action!==action||a.state!==state)throw new ReadFailure(0)
   this.pending=null;this.set({receipt:a as Accepted,reason:'',evidenceReference:'',transactionKey:'',notice:p.action==='query'?'같은 환불 시도의 조회가 접수됐습니다. 현재 결과를 다시 확인해 주세요.':'운영 근거를 기록하고 같은 환불 시도의 조회를 요청했습니다. 귀속이 불명확하면 예약 금액을 유지합니다.'})
   await this.readSelected(p.id,g)
  }catch(e){
   if(!this.current(g))return
   if(e instanceof ReadFailure&&[401,403,404].includes(e.status)){this.pending=null;this.deny(e,g)}
   else if(e instanceof ReadFailure&&e.status===409){this.reset();this.set({blocked:true,error:'정보가 변경됐습니다. 현재 상태를 다시 조회하고 사유와 근거를 확인해 주세요.'})}
   else if(this.pending)this.set({uncertain:true,error:'요청 결과를 확인하지 못했습니다. 내용을 바꾸지 말고 같은 요청으로 결과를 확인해 주세요.'})
   else this.deny(e,g)
  }finally{if(this.current(g))this.set({sending:false})}
 }
}
