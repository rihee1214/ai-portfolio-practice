import type {AuthStore} from '../auth/AuthStore'
import {requestJson} from '../api/http'
import type {SandboxSetup,TossConfirmation} from './TossPaymentAdapter'
export type PaymentView={orderNumber:string;orderState:string;paymentState:string;compensation:string;reviewRequired:boolean;amount:string;mode:string;providerOrderId?:string|null}
export class PaymentStore {
 private state={busy:false,blocked:false,error:'',view:null as PaymentView|null}
 private listeners=new Set<()=>void>()
 private approved=false
 private owner:string|null|undefined
 private generation=0
 private controller=new AbortController()
 private unsubscribe:(()=>void)|null
 constructor(private auth:AuthStore,private number:string,private fetcher:typeof fetch=fetch){this.unsubscribe=auth.subscribe(this.invalidate)}
 getSnapshot=()=>this.state
 subscribe=(fn:()=>void)=>{if(!this.unsubscribe)this.unsubscribe=this.auth.subscribe(this.invalidate);this.listeners.add(fn);return()=>{this.listeners.delete(fn)}}
 private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
 private clear(){++this.generation;this.controller.abort();this.controller=new AbortController();this.set({busy:false,error:'',view:null})}
 private invalidate=()=>{this.clear();this.owner=undefined;this.approved=true}
 dispose=()=>{this.clear();this.unsubscribe?.();this.unsubscribe=null}
 private current(g=this.generation){const a=this.auth.getSnapshot();return g===this.generation&&!this.state.blocked&&a.checked&&!a.busy&&!a.error&&(a.member?.id??null)===this.owner}
 private request(path:string,init:RequestInit={}){return requestJson(path,{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',signal:this.controller.signal,...init})}
 private deny(){this.clear();this.auth.rejectSession({message:'결제 결과 조회를 위해 인증을 확인해 주세요.'});this.set({blocked:true,error:'인증이 만료되었습니다. 다시 로그인해도 자동 승인하지 않습니다.'})}
 approve=async()=>{if(this.state.busy||this.state.blocked||this.approved||this.state.view&&this.state.view.paymentState!=='NONE')return;await this.send(true)}
 poll=async()=>{await this.send(false)}
 sandbox=async():Promise<SandboxSetup|null>=>{
  if(this.state.busy||this.state.blocked||this.state.view?.mode!=='SANDBOX'||this.state.view.paymentState!=='NONE'||!this.current())return null
  const g=this.generation;this.set({busy:true,error:''})
  try{
   const csrf=await this.request('/api/auth/csrf'),data=csrf.kind==='success'?csrf.data as {headerName?:string;token?:string}:null
   if(!this.current(g))return null
   if(csrf.kind==='http'&&[401,403].includes(csrf.status)){this.deny();return null}
   if(!data||data.headerName!=='X-CSRF-TOKEN'||typeof data.token!=='string')throw new Error('csrf')
   const response=await this.request('/api/payments/'+encodeURIComponent(this.number)+'/sandbox',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':data.token},body:'{}'})
   if(!this.current(g))return null
   if(response.kind==='http'&&[401,403].includes(response.status)){this.deny();return null}
   if(response.kind!=='success')throw new Error('response')
   const setup=response.data as SandboxSetup
   if(!setup||setup.amount!==this.state.view?.amount||typeof setup.clientKey!=='string'||!setup.clientKey.startsWith('test_ck_')||typeof setup.orderId!=='string'||setup.customerKey!==setup.orderId)throw new Error('schema')
   return this.current(g)?setup:null
  }catch{if(this.current(g))this.set({error:'테스트 결제창을 준비하지 못했습니다. 서버 설정과 결과를 확인해 주세요.'});return null}finally{if(this.current(g))this.set({busy:false})}
 }
 confirm=async(input:TossConfirmation)=>{if(this.state.busy||this.state.blocked||this.approved||this.state.view?.mode!=='SANDBOX'||this.state.view.paymentState!=='NONE')return;await this.send(true,input)}
 private async send(approval:boolean,confirmation?:TossConfirmation){
  if(this.state.busy||this.state.blocked)return
  const auth=this.auth.getSnapshot();if(!auth.checked||auth.busy||auth.error)return
  if(this.owner===undefined)this.owner=auth.member?.id??null
  if(!this.current()){this.invalidate();return}
  const g=this.generation;this.set({busy:true,error:''})
  try{
   const headers:Record<string,string>={}
   if(approval){const csrf=await this.request('/api/auth/csrf');if(!this.current(g))return;if(csrf.kind==='http'&&[401,403].includes(csrf.status)){this.deny();return}if(csrf.kind!=='success'||!csrf.data||typeof csrf.data!=='object'||!('token'in csrf.data)||typeof csrf.data.token!=='string'||!('headerName'in csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN')throw new Error('csrf');headers['X-CSRF-TOKEN']=csrf.data.token;headers['Content-Type']='application/json'}
   if(!this.current(g))return
   if(approval)this.approved=true
   const response=await this.request('/api/payments/'+encodeURIComponent(this.number)+(approval?(confirmation?'/confirm':'/approve'):''),{method:approval?'POST':'GET',headers,...(approval?{body:confirmation?JSON.stringify(confirmation):'{}'}:{})})
   if(!this.current(g))return
   if(response.kind==='http'&&[401,403].includes(response.status)){this.deny();return}
   if(response.kind!=='success')throw new Error('response')
   const view=response.data as PaymentView
   if(!view||view.orderNumber!==this.number||!['PENDING_PAYMENT','PAID','CANCELED','EXPIRED','CLOSED'].includes(view.orderState)||!['NONE','CREATED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED'].includes(view.paymentState)||!['NONE','PENDING','UNKNOWN','CANCELED','FAILED'].includes(view.compensation)||typeof view.reviewRequired!=='boolean'||typeof view.amount!=='string'||!/^(0|[1-9]\d*)$/.test(view.amount)||BigInt(view.amount)>9223372036854775807n||!['DEMO','SANDBOX'].includes(view.mode))throw new Error('schema')
   if(!this.current(g))return
   if(!approval&&view.paymentState==='NONE'&&view.orderState==='PENDING_PAYMENT')this.approved=false
   this.set({view})
  }catch{if(this.current(g))this.set({error:'응답을 확인하지 못했습니다. 새 승인 없이 서버의 결제 결과를 확인해 주세요.'})}finally{if(this.current(g))this.set({busy:false})}
 }
}
