import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'

export type MediaSlot = { slot: number; assetId: string | null; alt: string; version: number; width: number; height: number }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
export const mediaSlotValid = (v: unknown): v is MediaSlot => object(v) && Number.isInteger(v.slot) && Number(v.slot) >= 0 && Number(v.slot) < 5
  && Number.isSafeInteger(v.version) && Number(v.version) >= 0 && typeof v.alt === 'string' && [...v.alt].length <= 120
  && (v.assetId === null ? v.alt === '' && v.width === 0 && v.height === 0 : typeof v.assetId === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v.assetId)
    && !!v.alt.trim() && Number.isInteger(v.width) && Number(v.width) > 0 && Number(v.width) <= 4096 && Number.isInteger(v.height) && Number(v.height) > 0 && Number(v.height) <= 4096)
type State = { items: MediaSlot[] | undefined; busy: boolean; error: ApiErrorView | null; notice: string }
export class MediaStore {
  private state: State = { items: undefined, busy: false, error: null, notice: '' }
  private generation = 0
  private controller = new AbortController()
  private listeners = new Set<() => void>()
  constructor(private auth: AuthStore, private path: string, private count: number) {}
  getSnapshot = () => this.state
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  private set(state: State) { this.state = state; this.listeners.forEach(fn => fn()) }
  stop = () => { this.generation++; this.controller.abort(); this.set({ items: undefined, busy: false, error: null, notice: '' }) }
  connect = () => { const sync = () => { this.stop(); if (this.auth.getSnapshot().member && !this.auth.getSnapshot().busy) void this.load() }; const unsubscribe = this.auth.subscribe(sync); sync(); return () => { unsubscribe(); this.stop() } }
  private begin() { this.controller.abort(); this.controller = new AbortController(); this.set({ ...this.state, busy: true, error: null, notice: '' }); return ++this.generation }
  private request(path: string, init: RequestInit = {}) { return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(30000)]) }) }
  private fail(result: JsonResult) {
    const error = apiErrorView(result) ?? { message: '이미지 정보를 확인하지 못했습니다.' }
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) { this.stop(); this.auth.rejectSession(error) }
    else this.set({ ...this.state, busy: false, error })
  }
  load = async () => {
    const generation = this.begin(); const result = await this.request(this.path)
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !Array.isArray(result.data) || result.data.length !== this.count || !result.data.every((row, index) => mediaSlotValid(row) && row.slot === index)) { this.fail(result); return }
    this.set({ items: result.data, busy: false, error: null, notice: '' })
  }
  save = async (slot: MediaSlot, file: File | null, alt: string) => {
    if (!this.auth.getSnapshot().member || this.state.busy || !this.state.items) return
    const generation = this.begin(); const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string' || !csrf.data.token) { this.fail(csrf); return }
    const form = new FormData()
    if (file) { form.append('file', file); form.append('alt', alt); form.append('version', String(slot.version)) }
    const result = await this.request(this.path + '/' + slot.slot + (file ? '' : '?version=' + slot.version), { method: file ? 'PUT' : 'DELETE', headers: { 'X-CSRF-TOKEN': csrf.data.token }, ...(file ? { body: form } : {}) })
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !mediaSlotValid(result.data) || result.data.slot !== slot.slot) { this.fail(result); return }
    this.set({ ...this.state, busy: false }); await this.load()
    if (this.generation === generation + 1 && !this.state.error) this.set({ ...this.state, notice: file ? '이미지를 저장했습니다.' : '이미지를 삭제했습니다.' })
  }
}
