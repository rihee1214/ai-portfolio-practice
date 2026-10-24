import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'
import { managed, type ManagedStore } from './StoreEditorStore'
type State = { items: ManagedStore[] | null; page: number; hasMore: boolean; busy: boolean; error: ApiErrorView | null; notice: string }
const empty: State = { items: null, page: 0, hasMore: false, busy: false, error: null, notice: '' }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
export class StoreOperationsStore {
  private state: State = empty
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  constructor(private auth: AuthStore) {}
  getSnapshot = () => this.state
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  private set(state: State) { this.state = state; this.listeners.forEach(fn => fn()) }
  stop = () => { this.generation++; this.controller.abort(); this.set(empty) }
  connect = () => {
    const sync = () => { this.stop(); if (this.auth.getSnapshot().member && !this.auth.getSnapshot().busy) void this.load() }
    const unsubscribe = this.auth.subscribe(sync); sync(); return () => { unsubscribe(); this.stop() }
  }
  private begin() { this.controller.abort(); this.controller = new AbortController(); this.set({ ...this.state, busy: true, error: null, notice: '' }); return ++this.generation }
  private request(path: string, init: RequestInit = {}) {
    return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) })
  }
  private fail(result: JsonResult) {
    const error = apiErrorView(result) ?? { message: '응답을 확인하지 못했습니다. 목록을 다시 불러와 주세요.' }
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) { this.stop(); this.auth.rejectSession(error) }
    else this.set({ ...this.state, busy: false, error })
  }
  load = async (page = 0) => {
    if (!this.auth.getSnapshot().member || this.state.busy) return
    const generation = this.begin()
    const result = await this.request('/api/admin/accounts/stores?page=' + page)
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !object(result.data) || result.data.page !== page || typeof result.data.hasMore !== 'boolean'
      || !Array.isArray(result.data.items) || result.data.items.length > 20 || !result.data.items.every(managed)) { this.fail(result); return }
    this.set({ ...empty, items: result.data.items, page, hasMore: result.data.hasMore })
  }
  change = async (store: ManagedStore, action: 'suspend' | 'restore', reason: string) => {
    if (!this.auth.getSnapshot().member || this.state.busy) return
    const generation = this.begin()
    const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string' || !csrf.data.token) { this.fail(csrf); return }
    const result = await this.request('/api/admin/accounts/stores/' + store.id + '/' + action, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.data.token }, body: JSON.stringify({ version: store.version, reason }),
    })
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !managed(result.data)) { this.fail(result); return }
    this.set({ ...this.state, busy: false, notice: '매대 운영 상태를 변경했습니다.', items: this.state.items?.map(item => item.id === store.id ? result.data as ManagedStore : item) ?? null })
  }
}
