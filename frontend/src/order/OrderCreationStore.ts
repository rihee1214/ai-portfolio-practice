import type { AuthStore } from '../auth/AuthStore'
import type { LineRequest } from '../promotion/QuoteStore'
import { requestJson } from '../api/http'
export type Contact = { buyerName: string; buyerPhone: string; recipientName: string; recipientPhone: string; postalCode: string; addressLine1: string; addressLine2: string }
export type OrderInput = { lines: LineRequest[]; coupons: { storeId: string; couponId: string }[]; fingerprint: string; contact: Contact; guestAttemptId: string | null; linkRevision: number | null }
export type OrderResult = { id: string; orderNumber: string; state: 'PENDING_PAYMENT'; total: string; expiresAt: string; stores: number }
export class OrderCreationStore {
  private state = { busy: false, error: '', retry: false, conflict: false, result: null as OrderResult | null, cleanupError: false }
  private listeners=new Set<()=>void>()
  private pending:(OrderInput & {requestKey:string})|null=null
  private owner:string|null=null
  private blocked=false
  constructor(private auth: AuthStore, private cleanup: (result: OrderResult) => Promise<void>, private fetcher: typeof fetch = fetch) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {this.listeners.add(listener);return()=>{this.listeners.delete(listener)}}
  private set(patch:Partial<typeof this.state>){this.state={...this.state,...patch};this.listeners.forEach(fn=>fn())}
  submit = async (input: OrderInput) => {
    const auth=this.auth.getSnapshot()
    if(this.state.busy||this.state.result||this.state.conflict||this.pending||this.blocked||!auth.checked||auth.busy||auth.error)return
    this.owner=auth.member?.id??null
    this.pending=JSON.parse(JSON.stringify({...input,requestKey:crypto.randomUUID()})) as OrderInput & {requestKey:string}
    await this.send()
  }
  retry = async () => {if(this.state.retry&&!this.state.busy&&!this.blocked&&this.pending)await this.send()}
  confirmAgain = () => {if(this.state.conflict&&!this.state.busy){this.pending=null;this.set({conflict:false,error:''})}}
  private async send(){
    if(!this.pending)return
    const current=()=>{const auth=this.auth.getSnapshot();return auth.checked&&!auth.busy&&!auth.error&&(auth.member?.id??null)===this.owner}
    if(!current()){this.blocked=true;this.set({retry:false,error:'로그인 상태가 변경되었습니다. 주문 결과를 확인해 주세요.'});return}
    this.set({busy:true,error:'',retry:false})
    try{
      const csrf=await requestJson('/api/auth/csrf',{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store'})
      if(csrf.kind!=='success'||!csrf.data||typeof csrf.data!=='object'||!('token' in csrf.data)||!('headerName' in csrf.data)||csrf.data.headerName!=='X-CSRF-TOKEN'||typeof csrf.data.token!=='string')throw new Error('csrf')
      if(!current())throw new Error('session')
      const response=await requestJson('/api/orders',{fetcher:this.fetcher,credentials:'same-origin',cache:'no-store',method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.data.token},body:JSON.stringify(this.pending)})
      if(response.kind==='http'&&[401,403].includes(response.status)){
        this.blocked=true;this.auth.rejectSession({message:'로그인 또는 구매 인증을 다시 확인해 주세요.'});this.set({error:'인증이 만료되었습니다. 자동으로 주문을 다시 전송하지 않습니다.',retry:false});return
      }
      if(!current()){this.blocked=true;this.set({error:'로그인 상태가 변경되었습니다. 주문 결과를 확인해 주세요.',retry:false});return}
      if(response.kind==='http'&&response.status===409){this.set({conflict:true,error:'견적이나 구매 상태가 변경되었습니다. 견적을 다시 계산하고 내용을 확인해 주세요.',retry:false});return}
      if(response.kind!=='success')throw new Error('response')
      const value=response.data as OrderResult
      if(!value||typeof value.id!=='string'||!/^O[0-9a-f]{32}$/.test(value.orderNumber)||value.state!=='PENDING_PAYMENT'||typeof value.total!=='string'||!/^(0|[1-9]\d*)$/.test(value.total)||BigInt(value.total)>9223372036854775807n||!Number.isFinite(Date.parse(value.expiresAt))||!Number.isInteger(value.stores)||value.stores<1||value.stores>20)throw new Error('schema')
      this.set({result:value,retry:false});this.pending=null
      try{await this.cleanup(value)}catch{this.set({cleanupError:true})}
    }catch{this.set({retry:!this.blocked,error:'응답을 확인하지 못했습니다. 같은 요청으로 주문 결과를 다시 확인해 주세요.'})}
    finally{this.set({busy:false})}
  }
}
