import {returnPolicyValid,type ReturnPolicy} from './ReturnPolicy'
import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import type { AuthStore } from '../auth/AuthStore'
import { descriptionValid, type DescriptionBlock } from './ProductDescription'

export type Category = { id: string; name: string; active: boolean; version: number }
export type Product = { id: string; slug: string; title: string; description: string; descriptionBlocks?: DescriptionBlock[] | null; categoryId: string; state: 'DRAFT' | 'ACTIVE' | 'STOPPED' | 'ARCHIVED'; version: number; hiddenByAdmin: boolean; returnPolicy: ReturnPolicy }
export const productStates = { DRAFT: '초안', ACTIVE: '공개 등록', STOPPED: '판매 중지', ARCHIVED: '보관' }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v)
const text = (v: unknown, max: number, required = false) => typeof v === 'string' && [...v].length <= max && (!required || !!v.trim())
const base = (v: unknown): v is Record<string, unknown> => object(v) && uuid(v.id) && Number.isSafeInteger(v.version) && Number(v.version) >= 0
export const categoryValid = (v: unknown): v is Category => base(v) && text(v.name, 80, true) && typeof v.active === 'boolean'
export const productValid = (v: unknown): v is Product => base(v) && uuid(v.categoryId) && typeof v.slug === 'string' && /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v.slug)
  && text(v.title, 120, true) && text(v.description, 4000) && descriptionValid(v.descriptionBlocks)
  && Object.keys(productStates).includes(String(v.state)) && typeof v.hiddenByAdmin === 'boolean' && returnPolicyValid(v.returnPolicy,String(v.id))
type State<T> = { items: T[] | undefined; page: number; hasMore: boolean; busy: boolean; error: ApiErrorView | null; notice: string }
export class CatalogStore<T extends { id: string; version: number }> {
  private state: State<T> = this.empty()
  private generation = 0
  private listeners = new Set<() => void>()
  private controller = new AbortController()
  constructor(private auth: AuthStore, private path: string, private valid: (value: unknown) => value is T) {}
  private empty(): State<T> { return { items: undefined, page: 0, hasMore: false, busy: false, error: null, notice: '' } }
  getSnapshot = () => this.state
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  private set(state: State<T>) { this.state = state; this.listeners.forEach(fn => fn()) }
  stop = () => { this.generation++; this.controller.abort(); this.set(this.empty()) }
  connect = (initialPage = 0) => {
    const sync = () => { this.stop(); if (this.auth.getSnapshot().member && !this.auth.getSnapshot().busy) void this.load(initialPage) }
    const unsubscribe = this.auth.subscribe(sync); sync()
    return () => { unsubscribe(); this.stop() }
  }
  private begin() { this.controller.abort(); this.controller = new AbortController(); this.set({ ...this.state, busy: true, error: null, notice: '' }); return ++this.generation }
  private request(path: string, init: RequestInit = {}) { return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) }) }
  private fail(result: JsonResult) {
    const error = apiErrorView(result) ?? { message: '응답을 확인하지 못했습니다. 다시 불러와 주세요.' }
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) { this.stop(); this.auth.rejectSession(error) }
    else this.set({ ...this.state, busy: false, error })
  }
  load = async (page = this.state.page) => {
    if (!this.auth.getSnapshot().member || this.state.busy || page < 0 || page > 10000) return
    const generation = this.begin()
    const result = await this.request(this.path + '?page=' + page)
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== 200 || !object(result.data) || result.data.page !== page || typeof result.data.hasMore !== 'boolean'
      || !Array.isArray(result.data.items) || result.data.items.length > 20 || !result.data.items.every(this.valid)) { this.fail(result); return }
    this.set({ ...this.empty(), items: result.data.items, page, hasMore: result.data.hasMore })
  }
  save = async (item: T | null, fields: Record<string, unknown>, images: File[] = []) => {
    if (!this.auth.getSnapshot().member || this.state.busy || !this.state.items) return
    const generation = this.begin()
    const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string' || !csrf.data.token) { this.fail(csrf); return }
    const headers = new Headers({ 'X-CSRF-TOKEN': csrf.data.token })
    const json = JSON.stringify({ ...fields, version: item?.version ?? 0 })
    let body: string | FormData = json
    if (images.length) {
      const form = new FormData()
      form.append('product', new Blob([json], { type: 'application/json' }))
      for (const image of images) form.append('images', image)
      body = form
    } else headers.set('Content-Type', 'application/json')
    const result = await this.request(this.path + (item ? '/' + item.id : ''), { method: item ? 'PUT' : 'POST', headers, body })
    if (generation !== this.generation) return
    if (result.kind !== 'success' || result.status !== (item ? 200 : 201) || !this.valid(result.data)) { this.fail(result); return }
    this.set({ ...this.state, busy: false })
    await this.load(item ? this.state.page : 0)
    if (this.generation !== generation + 1) return
    if (!this.state.error) this.set({ ...this.state, notice: '저장했습니다.' })
    return result.data
  }
}
