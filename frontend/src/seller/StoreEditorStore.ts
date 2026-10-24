import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'

export type StoreFields = { slug: string; name: string; introduction: string; address: string; notice: string; imageKey: string; imageAlt: string }
export type ManagedStore = StoreFields & { id: string; visible: boolean; version: number }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
export function publicStore(v: unknown): v is StoreFields {
  return object(v) && typeof v.slug === 'string' && /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v.slug)
    && Object.entries({ name: 80, introduction: 1000, address: 200, notice: 1000, imageKey: 100, imageAlt: 120 }).every(([key, max]) => typeof v[key] === 'string' && [...v[key]].length <= max)
    && Boolean(v.name && v.introduction && v.address)
}
function managed(v: unknown): v is ManagedStore {
  return object(v) && typeof v.id === 'string' && /^[0-9a-f-]{36}$/i.test(v.id)
    && typeof v.visible === 'boolean' && Number.isSafeInteger(v.version) && Number(v.version) >= 0
    && publicStore(v)
}
type State = { store: ManagedStore | null | undefined; busy: boolean; error: ApiErrorView | null; notice: string }
const empty: State = { store: undefined, busy: false, error: null, notice: '' }
export class StoreEditorStore {
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
    const unsubscribe = this.auth.subscribe(sync); sync()
    return () => { unsubscribe(); this.stop() }
  }
  private begin() { this.controller.abort(); this.controller = new AbortController(); this.set({ ...this.state, busy: true, error: null, notice: '' }); return ++this.generation }
  private request(path: string, init: RequestInit = {}) {
    return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) })
  }
  private fail(result: JsonResult) {
    const error = apiErrorView(result) ?? { message: '응답을 확인하지 못했습니다. 매대 정보를 다시 불러와 주세요.' }
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) { this.stop(); this.auth.rejectSession(error) }
    else this.set({ ...this.state, busy: false, error })
  }
  load = async () => {
    if (!this.auth.getSnapshot().member || this.state.busy) return
    const generation = this.begin()
    const result = await this.request('/api/seller/stores')
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !object(result.data) || (result.data.store !== null && !managed(result.data.store))) { this.fail(result); return }
    this.set({ ...empty, store: result.data.store })
  }
  save = async (fields: StoreFields & { visible: boolean }) => {
    if (!this.auth.getSnapshot().member || this.state.busy || this.state.store === undefined) return
    const existing = this.state.store
    const generation = this.begin()
    const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string' || !csrf.data.token) { this.fail(csrf); return }
    const result = await this.request('/api/seller/stores' + (existing ? '/' + existing.id : ''), {
      method: existing ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.data.token },
      body: JSON.stringify({ ...fields, version: existing?.version ?? 0 }),
    })
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== (existing ? 200 : 201) || !managed(result.data)) { this.fail(result); return }
    this.set({ ...empty, store: result.data, notice: '매대 정보를 저장했습니다.' })
  }
}
