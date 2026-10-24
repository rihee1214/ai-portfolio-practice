import { requestJson, type JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'

export type Member = { id: string; roles: string[] }
export type AuthState = { member: Member | null; busy: boolean; checked: boolean; error: ApiErrorView | null; notice: string }
type Action = 'signup' | 'verify-email' | 'login' | 'logout' | 'password/request' | 'password/reset' | 'withdraw'
const initial: AuthState = { member: null, busy: false, checked: false, error: null, notice: '' }
const failure = { message: '응답을 확인하지 못했습니다. 상태를 다시 확인해 주세요.' }
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function member(value: unknown): Member | null {
  if (!object(value) || typeof value.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(value.id)
    || !Array.isArray(value.roles) || !value.roles.every(role => ['BUYER', 'SELLER', 'ADMIN'].includes(role))) return null
  return { id: value.id, roles: value.roles as string[] }
}

// One owner for async generation, cancellation and private state; never persists credentials.
export class AuthStore {
  readonly source = crypto.randomUUID()
  private state: AuthState = initial
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private set(state: AuthState) { this.state = state; this.listeners.forEach(listener => listener()) }
  private begin() {
    this.controller.abort()
    this.controller = new AbortController()
    this.set({ ...initial, busy: true })
    return ++this.generation
  }
  stop = () => { this.generation++; this.controller.abort(); this.set(initial) }
  rejectSession = (error: ApiErrorView) => {
    this.stop()
    this.set({ ...initial, checked: true, error })
  }
  private request(path: string, init: RequestInit = {}) {
    return requestJson(path === 'withdraw' ? '/api/account/withdraw' : '/api/auth/' + path, { ...init, cache: 'no-store', credentials: 'same-origin',
      signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]) })
  }
  private error(result: JsonResult) { return apiErrorView(result) ?? failure }
  private async csrf(): Promise<string | JsonResult> {
    const result = await this.request('csrf')
    if (result.kind === 'success' && object(result.data) && result.data.headerName === 'X-CSRF-TOKEN'
      && typeof result.data.token === 'string' && result.data.token.length > 0) return result.data.token
    return result
  }
  refresh = async () => {
    if (this.state.busy) return
    const generation = this.begin()
    const result = await this.request('me')
    if (generation !== this.generation) return
    const user = result.kind === 'success' ? member(result.data) : null
    const anonymous = result.kind === 'http' && result.status === 401
    this.set({ ...initial, checked: true, member: user, error: user || anonymous ? null : this.error(result) })
  }
  invalidate = () => {
    this.stop()
    this.set(initial)
    void this.refresh()
  }
  mutate = async (action: Action, fields: Record<string, string> = {}) => {
    if (this.state.busy) return false
    const generation = this.begin()
    const token = await this.csrf()
    if (generation !== this.generation) return false
    if (typeof token !== 'string') {
      this.set({ ...initial, checked: true, error: this.error(token) })
      return false
    }
    const form = action === 'login'
    const result = await this.request(action, { method: 'POST',
      headers: { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', 'X-CSRF-TOKEN': token },
      body: form ? new URLSearchParams(fields).toString() : JSON.stringify(fields) })
    if (generation !== this.generation) return false
    const expected = { signup: 'EMAIL_VERIFICATION_REQUIRED', 'verify-email': 'EMAIL_VERIFIED',
      login: 'AUTHENTICATED', logout: 'LOGGED_OUT', 'password/request': 'PASSWORD_RESET_REQUESTED', 'password/reset': 'PASSWORD_RESET', withdraw: 'WITHDRAWN' }[action]
    if (result.kind !== 'success' || !object(result.data) || result.data.status !== expected
      || (action === 'password/request' && result.status !== 202) || ((action === 'password/reset' || action === 'withdraw') && result.status !== 200)) {
      const error = this.error(result)
      if (action === 'login' && result.kind === 'http' && result.status === 401) error.message = '이메일·비밀번호와 이메일 확인 여부를 확인해 주세요.'
      this.set({ ...initial, checked: true, error })
      return false
    }
    if (action === 'login' || action === 'logout') {
      // Notify other tabs without putting session IDs, tokens or user data in storage.
      if (typeof BroadcastChannel !== 'undefined') {
        const channel = new BroadcastChannel('marketplace-auth')
        channel.postMessage({ source: this.source })
        channel.close()
      }
      const renewed = await this.csrf()
      if (generation !== this.generation) return false
      if (typeof renewed !== 'string') {
        this.set({ ...initial, checked: true, error: this.error(renewed) })
        return false
      }
      const me = await this.request('me')
      if (generation !== this.generation) return false
      const user = me.kind === 'success' ? member(me.data) : null
      const anonymous = me.kind === 'http' && me.status === 401
      const valid = action === 'login' ? user !== null : anonymous
      this.set({ ...initial, checked: true, member: valid ? user : null,
        error: valid ? null : this.error(me), notice: valid && action === 'logout' ? '로그아웃했습니다.' : '' })
      return valid
    }
    if ((action === 'password/reset' || action === 'withdraw') && typeof BroadcastChannel !== 'undefined') {
      const channel = new BroadcastChannel('marketplace-auth')
      channel.postMessage({ source: this.source })
      channel.close()
    }
    this.set({ ...initial, checked: true, notice: action === 'password/request'
      ? '복구 요청을 접수했습니다. 재설정 가능한 계정이면 로컬 테스트 메일함에서 복구 코드를 확인할 수 있습니다.'
      : action === 'withdraw' ? '회원 탈퇴를 완료했습니다.'
      : action === 'password/reset' ? '비밀번호를 변경했습니다. 새 비밀번호로 로그인해 주세요.'
      : action === 'signup'
      ? '가입 신청이 완료되었습니다. 로컬 테스트 메일함의 확인 코드를 입력해 주세요.'
      : '이메일 확인을 완료했습니다. 로그인할 수 있습니다.' })
    return true
  }
}
