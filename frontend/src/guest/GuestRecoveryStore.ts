import { requestJson } from '../api/http'
import { confirmPhoneCode } from '../phone/FirebasePhoneClient'
type View = { busy: boolean; step: 'INPUT' | 'SENT' | 'READY' | 'COMPLETE'; recoveryCode: string; error: string }
const initial = (): View => ({ busy: false, step: 'INPUT', recoveryCode: '', error: '' })
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object'
export class GuestRecoveryStore {
  private view = initial()
  private listeners = new Set<() => void>()
  private controller = new AbortController()
  private generation = 0
  private attempt = ''
  private grant = ''
  constructor(private fetcher: typeof fetch = fetch, private confirm = confirmPhoneCode) {}
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  getSnapshot = () => this.view
  private set(patch: Partial<View>) { this.view = { ...this.view, ...patch }; this.listeners.forEach(listener => listener()) }
  dispose() { ++this.generation; this.controller.abort(); this.attempt = ''; this.grant = ''; this.view = initial() }
  hide = () => this.set({ recoveryCode: '' })
  private async call(path: string, body: unknown) {
    const generation = this.generation, signal = this.controller.signal
    const csrf = await requestJson('/api/auth/csrf', { fetcher: this.fetcher, signal, credentials: 'same-origin', cache: 'no-store' })
    if (generation !== this.generation) throw new Error('cancelled')
    if (csrf.kind !== 'success' || !object(csrf.data) || csrf.data.headerName !== 'X-CSRF-TOKEN' || typeof csrf.data.token !== 'string') throw new Error('csrf')
    const result = await requestJson(path, { fetcher: this.fetcher, signal, credentials: 'same-origin', cache: 'no-store', method: 'POST', headers: { 'X-CSRF-TOKEN': csrf.data.token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (generation !== this.generation) throw new Error('cancelled')
    if (result.kind !== 'success' || !object(result.data)) throw new Error(result.kind === 'http' && result.status === 429 ? 'limit' : 'request')
    return result.data
  }
  private async run(action: () => Promise<void>) {
    if (this.view.busy) return
    if (this.controller.signal.aborted) this.controller = new AbortController()
    const generation = this.generation
    this.set({ busy: true, error: '', recoveryCode: '' })
    try { await action() } catch (error) { if (generation === this.generation) this.set({ error: error instanceof Error && error.message === 'limit' ? '재요청 제한입니다. 재발송은 최소 60초 뒤 가능하며 반복 제한에 걸리면 15분 뒤 다시 시도해 주세요.' : '처리를 확인하지 못했습니다. 입력과 유효시간을 확인해 주세요. 변경 응답을 잃었다면 새 비밀번호로 로그인한 뒤 복구 코드를 다시 발급받으세요.' }) }
    finally { if (generation === this.generation) this.set({ busy: false }) }
  }
  start = (orderNumber: string, phone: string, recoveryCode: string) => this.run(async () => {
    const result = await this.call('/api/guest/recovery/start', { orderNumber, phone, recoveryCode })
    if (typeof result.attemptId !== 'string' || typeof result.expiresAt !== 'string') throw new Error('schema')
    this.attempt = result.attemptId; this.grant = ''; this.set({ step: 'SENT' })
  })
  verify = (code: string) => this.run(async () => {
    if (this.view.step !== 'SENT' || !/^\d{6}$/.test(code)) throw new Error('input')
    const generation = this.generation, base = '/api/guest/recovery/' + this.attempt
    const result = await this.call(base + '/exchange', { code })
    if (typeof result.host !== 'string' || typeof result.project !== 'string' || typeof result.customToken !== 'string') throw new Error('schema')
    const proof = await this.confirm(result.host, result.project, result.customToken)
    if (generation !== this.generation) return
    const verified = await this.call(base + '/verify', { proof })
    if (typeof verified.grant !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(verified.grant)) throw new Error('schema')
    this.grant = verified.grant; this.set({ step: 'READY' })
  })
  private password(value: string, confirmation: string) { if (value !== confirmation || [...value].length < 12 || [...value].length > 128) throw new Error('password') }
  private completed(result: Record<string, unknown>, expected: string) {
    if (result.status !== expected || typeof result.recoveryCode !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.recoveryCode)) throw new Error('schema')
    this.grant = ''; this.attempt = ''; this.set({ step: 'COMPLETE', recoveryCode: result.recoveryCode })
  }
  reset = (password: string, confirmation: string) => this.run(async () => {
    this.password(password, confirmation); if (this.view.step !== 'READY' || !this.grant) throw new Error('grant')
    this.completed(await this.call('/api/guest/recovery/reset', { grant: this.grant, password, confirmation }), 'PASSWORD_RESET')
  })
  manage = (currentPassword: string, password: string, confirmation: string, change: boolean) => this.run(async () => {
    if (change) this.password(password, confirmation)
    this.completed(await this.call('/api/guest/access/' + (change ? 'password' : 'recovery-code'), { currentPassword, ...(change ? { password, confirmation } : {}) }), change ? 'PASSWORD_CHANGED' : 'CODE_REPLACED')
  })
}
