import type { AuthStore } from '../auth/AuthStore'
import { GuestCart, uuid, GUEST_KEY, JOURNAL_KEY } from './GuestCart'
import { detailValid, type Detail } from '../catalog/Browse'
import { requestJson } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
export type Line = { skuId: string; quantity: number; detail: Detail | null; revision?: number }
export type PurchaseCart = {owner:string|null;items:{skuId:string;quantity:number;revision:number|string}[]}
export type View = { version: number; items: Line[] }
type State = View & { busy: boolean; ready: boolean; member: boolean; error?: ApiErrorView; notice: string }
function valid(v: unknown): v is View {
  if (!v || typeof v !== 'object') return false
  const value = v as View
  return Number.isSafeInteger(value.version) && value.version >= 0 && Array.isArray(value.items) && value.items.length <= 100
    && new Set(value.items.map(i => i?.skuId)).size === value.items.length && value.items.every(i => i && uuid(i.skuId) && Number.isInteger(i.quantity) && i.quantity >= 1 && i.quantity <= 99
      && (i.detail === null || detailValid(i.detail) && i.detail.offers.length === 1 && i.detail.offers[0].id === i.skuId))
}
const empty = (): State => ({ version: 0, items: [], busy: false, ready: false, member: false, notice: '' })
export async function browserCartLock<T>(work: () => Promise<T>): Promise<T> {
  if (!navigator.locks) throw new Error('이 브라우저는 안전한 장바구니 저장을 지원하지 않습니다.')
  return navigator.locks.request('marketplace-cart', work)
}
export class CartStore {
  private state = empty()
  private generation = 0
  private controller = new AbortController()
  private listeners = new Set<() => void>()
  constructor(private auth: AuthStore, private guest: GuestCart, private lock: <T>(work: () => Promise<T>) => Promise<T> = browserCartLock) { }
  getSnapshot = () => this.state
  capturePurchase = ():PurchaseCart => ({owner:this.auth.getSnapshot().member?.id??null,items:this.state.member?this.state.items.map(i=>({skuId:i.skuId,quantity:i.quantity,revision:i.revision??-1})):this.guest.capture()})
  reconcilePurchase = async (orderId:string,submitted:PurchaseCart) => {
    if(!submitted.items.length)return
    let succeeded=false
    await this.run(async(send,owner)=>{
      if(owner!==submitted.owner)throw new Error('구매 계정이 변경되었습니다.')
      let view:View
      if(owner)view=await send('/api/cart/reconcile','POST',{owner,orderId,items:submitted.items})
      else{this.guest.reconcile(orderId,submitted.items.map(i=>({...i,revision:String(i.revision)})));const items=this.guest.read();view=items.length?await send('/api/cart/preview','POST',{items}):{version:0,items:[]}}
      succeeded=true;return{view,notice:'주문한 항목을 장바구니에 반영했습니다. 이후 변경한 항목은 유지합니다.'}
    })
    if(!succeeded)throw new Error('장바구니 반영을 확인하지 못했습니다.')
  }
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit(state: State) { this.state = state; this.listeners.forEach(fn => fn()) }
  private stop = () => { ++this.generation; this.controller.abort(); this.emit(empty()) }
  connect = () => {
    const sync = () => { void this.refresh() }; const unsubscribe = this.auth.subscribe(sync)
    const onStorage = (e: StorageEvent) => { if (e.key === GUEST_KEY || e.key === JOURNAL_KEY || e.key === null) sync() }
    window.addEventListener('storage', onStorage); sync()
    return () => { unsubscribe(); window.removeEventListener('storage', onStorage); this.stop() }
  }
  private async run(work: (send: (path: string, method?: string, body?: unknown) => Promise<View>, owner: string | null, check: () => void) => Promise<{ view: View; notice?: string }>) {
    const generation = ++this.generation; this.controller.abort(); this.controller = new AbortController(); const controller = this.controller
    const auth = this.auth.getSnapshot(), owner = auth.member?.id ?? null
    this.emit({ ...empty(), busy: auth.busy || !auth.checked })
    if (!auth.checked || auth.busy || auth.error) { if (auth.error) this.emit({ ...empty(), error: auth.error }); return }
    this.emit({ ...empty(), busy: true })
    const check = () => { if (generation !== this.generation || controller.signal.aborted || this.auth.getSnapshot().member?.id !== auth.member?.id || this.auth.getSnapshot().busy) throw new Error('cancelled') }
    const request = async (path: string, init: RequestInit = {}) => {
      check(); const result = await requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) }); check()
      if (result.kind !== 'success') {
        if (result.kind === 'http' && result.status === 401 && owner) this.auth.rejectSession({ message: '로그인 상태를 다시 확인해 주세요.' })
        throw new Error(apiErrorView(result)?.message ?? '장바구니 응답을 확인하지 못했습니다. 다시 시도해 주세요.')
      }
      return result
    }
    const send = async (path: string, method = 'GET', body?: unknown): Promise<View> => {
      const headers: Record<string, string> = {}
      if (method !== 'GET') {
        const csrf = await request('/api/auth/csrf'); const token = csrf.data as { headerName?: string; token?: string }
        if (csrf.status !== 200 || token?.headerName !== 'X-CSRF-TOKEN' || typeof token.token !== 'string' || !token.token) throw new Error('요청 확인 정보를 받지 못했습니다.')
        headers['X-CSRF-TOKEN'] = token.token; headers['Content-Type'] = 'application/json'
      }
      const result = await request(path, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
      if (result.status !== 200 || !valid(result.data)) throw new Error('장바구니 응답 형식을 확인하지 못했습니다.')
      return result.data
    }
    try {
      await this.lock(async () => {
        check(); const result = await work(send, owner, check); check()
        this.emit({ ...result.view, busy: false, ready: true, member: Boolean(owner), notice: result.notice ?? '' })
      })
    } catch (e) {
      if (generation !== this.generation || controller.signal.aborted) return
      this.emit({ ...empty(), error: { message: e instanceof Error ? e.message : '장바구니 저장소를 사용할 수 없습니다.' } })
    }
  }
  refresh = async () => this.run(async (send, owner, check) => {
    if (owner) {
      const journal = this.guest.prepare()
      if (journal) {
        const view = await send('/api/cart/merge', 'POST', { owner, ...journal }); check(); this.guest.acknowledge(journal.requestId)
        return { view, notice: '방문자 장바구니를 가져왔습니다. 중복 수량은 합산하여 최대 99개로 맞췄습니다.' }
      }
      return { view: await send('/api/cart?owner=' + owner) }
    }
    const items = this.guest.read()
    return { view: items.length ? await send('/api/cart/preview', 'POST', { items }) : { version: 0, items: [] } }
  })
  set = async (sku: string, quantity: number) => {
    if (!this.state.ready || this.state.busy) return
    const version = this.state.version
    return this.run(async (send, owner) => {
      if (owner) return { view: await send('/api/cart/items/' + encodeURIComponent(sku), 'PUT', { owner, quantity, version }), notice: '장바구니를 변경했습니다.' }
      this.guest.set(sku, quantity); const items = this.guest.read()
      return { view: items.length ? await send('/api/cart/preview', 'POST', { items }) : { version: 0, items: [] }, notice: '방문자 장바구니를 변경했습니다.' }
    })
  }
  add = async (sku: string, quantity: number) => {
    if (!this.state.ready || this.state.busy || !uuid(sku) || !Number.isInteger(quantity) || quantity < 1 || quantity > 99) return
    const version = this.state.version, memberQuantity = this.state.items.find(i => i.skuId === sku)?.quantity ?? 0
    await this.run(async (send, owner) => {
      if (owner) return { view: await send('/api/cart/items/' + encodeURIComponent(sku), 'PUT', { owner, quantity: Math.min(99, memberQuantity + quantity), version }), notice: '장바구니에 담았습니다. 합산 수량은 최대 99개입니다.' }
      const current = this.guest.read().find(i => i.skuId === sku)?.quantity ?? 0
      this.guest.set(sku, Math.min(99, current + quantity))
      return { view: await send('/api/cart/preview', 'POST', { items: this.guest.read() }), notice: '방문자 장바구니를 변경했습니다.' }
    })
  }
  clear = async () => {
    if (!this.state.ready || this.state.busy) return
    const version = this.state.version
    await this.run(async (send, owner) => {
      if (owner) return { view: await send('/api/cart?owner=' + owner + '&version=' + version, 'DELETE'), notice: '회원 장바구니를 비웠습니다.' }
      this.guest.clear(); return { view: { version: 0, items: [] }, notice: '방문자 장바구니를 비웠습니다.' }
    })
  }
  discardGuest = async () => this.run(async (send, owner) => {
    this.guest.clear()
    return { view: owner ? await send('/api/cart?owner=' + owner) : { version: 0, items: [] }, notice: '방문자 가져오기를 취소했습니다. 이미 반영된 회원 장바구니는 유지됩니다.' }
  })
}
