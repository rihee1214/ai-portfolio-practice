import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'

export type Application = { id: string; applicantId: string; displayName: string; description: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; createdAt: string;
  events: { actorId: string; status: string; reason: string; occurredAt: string }[] }
type State = { items: Application[] | null; page: number; hasMore: boolean; busy: boolean; error: ApiErrorView | null; notice: string }
const empty: State = { items: null, page: 0, hasMore: false, busy: false, error: null, notice: '' }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && [...v].length <= max
const timestamp = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
function application(v: unknown): v is Application {
  return object(v) && uuid(v.id) && uuid(v.applicantId) && text(v.displayName, 80) && text(v.description, 500)
    && ['PENDING', 'APPROVED', 'REJECTED'].includes(String(v.status)) && timestamp(v.createdAt)
    && Array.isArray(v.events) && v.events.length >= 1 && v.events.length <= 2
    && v.events.every(e => object(e) && uuid(e.actorId) && ['PENDING', 'APPROVED', 'REJECTED'].includes(String(e.status)) && text(e.reason, 300) && timestamp(e.occurredAt))
}

export function readSellerApplicationsPage(value: unknown, page: number) {
  if (!object(value) || value.page !== page || typeof value.hasMore !== 'boolean'
    || !Array.isArray(value.items) || value.items.length > 20 || !value.items.every(application)) return null
  return { items: value.items, hasMore: value.hasMore }
}

export class SellerApplicationsStore {
  private state: State = empty
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  constructor(private auth: AuthStore, private admin: boolean) {}
  private get path() { return this.admin ? '/api/admin/accounts/seller-applications' : '/api/seller/applications' }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private set(state: State) { this.state = state; this.listeners.forEach(listener => listener()) }
  stop = () => { this.generation++; this.controller.abort(); this.set(empty) }
  connect = () => {
    const sync = () => { this.stop(); if (this.auth.getSnapshot().member && !this.auth.getSnapshot().busy) void this.load() }
    const unsubscribe = this.auth.subscribe(sync); sync()
    return () => { unsubscribe(); this.stop() }
  }
  private request(path: string, init: RequestInit = {}) {
    return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) })
  }
  private begin() { this.controller.abort(); this.controller = new AbortController(); this.set({ ...this.state, busy: true, error: null, notice: '' }); return ++this.generation }
  private fail(result: JsonResult) {
    const error = apiErrorView(result) ?? { message: '응답을 확인하지 못했습니다. 상태를 다시 확인해 주세요.' }
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) { this.stop(); this.auth.rejectSession(error) }
    else this.set({ ...this.state, busy: false, error })
  }
  load = async (page = 0) => {
    if (!this.auth.getSnapshot().member || this.state.busy) return false
    const generation = this.begin()
    const result = await this.request(this.path + '?page=' + page)
    if (generation !== this.generation) return false
    const data = result.kind === 'success' && result.status === 200 ? readSellerApplicationsPage(result.data, page) : null
    if (!data) { this.fail(result); return false }
    this.set({ ...empty, ...data, page }); return true
  }
  save = async (fields: Record<string, string>, id?: string) => {
    if (!this.auth.getSnapshot().member || this.state.busy) return false
    const generation = this.begin()
    const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return false
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || !text(csrf.data.token, 4096)) { this.fail(csrf); return false }
    const result = await this.request(this.path + (id ? '/' + encodeURIComponent(id) + '/decision' : ''), {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.data.token }, body: JSON.stringify(fields),
    })
    if (generation !== this.generation) return false
    if (result.kind !== 'success' || result.status !== (this.admin ? 200 : 201) || !application(result.data)) { this.fail(result); return false }
    const page = this.state.page
    this.set(empty)
    const loaded = await this.load(page)
    if (loaded) this.set({ ...this.state, notice: '처리했습니다.' })
    return loaded
  }
}
