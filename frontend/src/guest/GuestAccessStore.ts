import { requestJson } from '../api/http'
type View = { busy: boolean; authenticated: boolean; orders: string[]; error: string; logoutPending: boolean }
const initial = (): View => ({ busy: false, authenticated: false, orders: [], error: '', logoutPending: false })
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object'
export class GuestAccessStore {
  private view = initial()
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  constructor(private fetcher: typeof fetch = fetch) {}
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  getSnapshot = () => this.view
  private set(patch: Partial<View>) { this.view = { ...this.view, ...patch }; this.listeners.forEach(listener => listener()) }
  dispose() { ++this.generation; this.controller.abort(); this.view = initial() }
  private async call(path: string, body?: unknown) {
    const generation = this.generation, signal = this.controller.signal
    const headers: Record<string, string> = {}
    if (body !== undefined) {
      const csrf = await requestJson('/api/auth/csrf', { fetcher: this.fetcher, signal, credentials: 'same-origin', cache: 'no-store' })
      if (generation !== this.generation) throw new Error('cancelled')
      if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string') throw new Error('csrf')
      headers['X-CSRF-TOKEN'] = csrf.data.token; headers['Content-Type'] = 'application/json'
    }
    const response = await requestJson(path, { fetcher: this.fetcher, signal, credentials: 'same-origin', cache: 'no-store', method: body === undefined ? 'GET' : 'POST', headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    if (generation !== this.generation) throw new Error('cancelled')
    if (response.kind !== 'success' || !object(response.data)) throw new Error(response.kind === 'http' && response.status === 401 ? 'unauthorized' : 'request')
    return response.data
  }
  private async read() {
    const data = await this.call('/api/guest/orders')
    if (!Array.isArray(data.orders) || !data.orders.every(item => object(item) && typeof item.orderNumber === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(item.orderNumber))) throw new Error('schema')
    this.set({ authenticated: true, orders: data.orders.map(item => item.orderNumber as string) })
  }
  private async run(action: () => Promise<void>, quiet = false) {
    if (this.view.busy) return
    if (this.controller.signal.aborted) this.controller = new AbortController()
    const generation = this.generation
    this.set({ ...initial(), busy: true })
    try { await action() }
    catch (error) { if (generation === this.generation && !(quiet && error instanceof Error && error.message === 'unauthorized')) this.set({ error: '조회 인증을 완료하지 못했습니다. 입력 정보나 유효시간을 확인하고 잠시 후 다시 시도해 주세요.' }) }
    finally { if (generation === this.generation) this.set({ busy: false }) }
  }
  load = () => this.view.logoutPending ? Promise.resolve() : this.run(() => this.read(), true)
  login = (orderNumber: string, phone: string, password: string) => this.run(async () => {
    if (!orderNumber || !phone || !password || password.length > 256) throw new Error('input')
    const result = await this.call('/api/guest/access/login', { orderNumber, phone, password })
    if (result.status !== 'AUTHENTICATED') throw new Error('schema')
    await this.read()
  })
  logout = () => this.run(async () => {
    this.set({ logoutPending: true })
    const result = await this.call('/api/guest/access/logout', {})
    if (result.status !== 'LOGGED_OUT') throw new Error('schema')
    this.set({ logoutPending: false })
  })
}
