import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { requestJson, type JsonResult } from '../api/http'
import type { AuthStore } from './AuthStore'

export type Profile = { email: string; displayName: string; phone: string | null }
export type Address = { id: string; label: string; recipient: string; phone: string; postalCode: string; addressLine1: string; addressLine2: string; isDefault: boolean }
export type AddressFields = Omit<Address, 'id'>
type State = { profile: Profile | null; addresses: Address[] | null; busy: boolean; error: ApiErrorView | null; notice: string }
const empty: State = { profile: null, addresses: null, busy: false, error: null, notice: '' }
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const string = (value: unknown, min: number, max: number): value is string => typeof value === 'string' && [...value].length >= min && [...value].length <= max
export function isProfile(value: unknown): value is Profile {
  return object(value) && string(value.email, 1, 254) && string(value.displayName, 0, 80)
    && (value.phone === null || string(value.phone, 7, 25))
}
export function isAddress(value: unknown): value is Address {
  return object(value) && typeof value.id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.id)
    && string(value.label, 1, 40) && string(value.recipient, 1, 80) && string(value.phone, 7, 25)
    && typeof value.postalCode === 'string' && /^[0-9]{5}$/.test(value.postalCode)
    && string(value.addressLine1, 1, 200) && string(value.addressLine2, 0, 200) && typeof value.isDefault === 'boolean'
}

// Account requests have their own generation but are synchronously cancelled on every auth transition.
export class AccountStore {
  private state: State = empty
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  constructor(private auth: AuthStore, private resource: 'profile' | 'addresses') {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private set(state: State) { this.state = state; this.listeners.forEach(listener => listener()) }
  stop = () => { this.generation++; this.controller.abort(); this.set(empty) }
  connect = () => {
    const sync = () => {
      this.stop()
      const state = this.auth.getSnapshot()
      if (state.member && state.checked && !state.busy) void this.load()
    }
    const unsubscribe = this.auth.subscribe(sync)
    sync()
    return () => { unsubscribe(); this.stop() }
  }
  private request(path: string, init: RequestInit = {}) {
    return requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store',
      signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) })
  }
  private fail(result: JsonResult) {
    if (result.kind === 'http' && (result.status === 401 || result.status === 403)) {
      this.stop()
      this.auth.rejectSession(apiErrorView(result) ?? { message: '로그인 상태를 다시 확인해 주세요.' })
    } else this.set({ ...this.state, busy: false, error: apiErrorView(result) ?? { message: '응답을 확인하지 못했습니다. 상태를 다시 확인해 주세요.' } })
  }
  private begin() {
    this.controller.abort()
    this.controller = new AbortController()
    this.set({ ...this.state, busy: true, error: null, notice: '' })
    return ++this.generation
  }
  load = async () => {
    if (!this.auth.getSnapshot().member || this.state.busy) return false
    const generation = this.begin()
    const result = await this.request('/api/account/' + this.resource)
    if (generation !== this.generation) return false
    if (result.kind === 'success' && result.status === 200) {
      if (this.resource === 'profile' && isProfile(result.data)) {
        this.set({ ...empty, profile: result.data }); return true
      }
      if (this.resource === 'addresses' && object(result.data) && Array.isArray(result.data.addresses)
        && result.data.addresses.length <= 20 && result.data.addresses.every(isAddress)
        && new Set(result.data.addresses.map(address => address.id)).size === result.data.addresses.length
        && (result.data.addresses.length === 0 || result.data.addresses.filter(address => address.isDefault).length === 1)) {
        this.set({ ...empty, addresses: result.data.addresses }); return true
      }
    }
    this.fail(result); return false
  }
  save = async (method: 'POST' | 'PUT' | 'DELETE', fields?: Pick<Profile, 'displayName' | 'phone'> | AddressFields, id?: string) => {
    if (!this.auth.getSnapshot().member || this.state.busy) return false
    const generation = this.begin()
    const csrf = await this.request('/api/auth/csrf')
    if (generation !== this.generation) return false
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || !string(csrf.data.token, 1, 4096)) {
      this.fail(csrf); return false
    }
    const result = await this.request('/api/account/' + this.resource + (id ? '/' + encodeURIComponent(id) : ''), {
      method, headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.data.token },
      ...(fields ? { body: JSON.stringify(fields) } : {}) })
    if (generation !== this.generation) return false
    const valid = result.kind === 'success' && result.status === (method === 'POST' ? 201 : 200)
      && (method === 'DELETE' ? object(result.data) && result.data.status === 'ADDRESS_DELETED'
        : this.resource === 'profile' ? isProfile(result.data) : isAddress(result.data))
    if (!valid) { this.fail(result); return false }
    // Read canonical defaults again; never retry a mutation automatically.
    this.set({ ...empty })
    const loaded = await this.load()
    if (loaded) this.set({ ...this.state, notice: method === 'DELETE' ? '배송지를 삭제했습니다.' : '저장했습니다.' })
    return loaded
  }
}
