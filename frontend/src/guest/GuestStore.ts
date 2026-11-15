import { requestJson } from '../api/http'
import { confirmPhoneCode } from '../phone/FirebasePhoneClient'

type View = { busy: boolean; state: 'INPUT' | 'SENT' | 'VERIFIED'; attempt: string; maskedPhone: string; error: string; expiresAt: string; passwordReady: boolean; linkMode: 'NEW' | 'EXISTING'; linkReady: boolean; linkRevision: number; linkKnown: boolean }
type Fetcher = typeof fetch
const empty = (): View => ({ busy: false, state: 'INPUT', attempt: '', maskedPhone: '', error: '', expiresAt: '', passwordReady: false, linkMode: 'NEW', linkReady: false, linkRevision: 0, linkKnown: true })
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object'
class GuestRequestFailure extends Error {
  constructor(readonly status: number | undefined, message: string) { super(message) }
}
export class GuestStore {
  private view = empty()
  private listeners = new Set<() => void>()
  private generation = 0
  private controller = new AbortController()
  private phone = ''
  constructor(private fetcher: Fetcher = fetch, private confirm = confirmPhoneCode) {}
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  getSnapshot = () => this.view
  private set(patch: Partial<View>) { this.view = { ...this.view, ...patch }; this.listeners.forEach(fn => fn()) }
  dispose() { ++this.generation; this.controller.abort(); this.phone = ''; this.view = empty() }
  private async call(path: string, body?: unknown, expectedStatus?: number) {
    const signal = this.controller.signal
    const generation = this.generation
    const headers: Record<string, string> = {}
    if (body !== undefined) {
      const csrf = await requestJson('/api/auth/csrf', { fetcher: this.fetcher, credentials: 'same-origin', cache: 'no-store', signal })
      if (generation !== this.generation) throw new Error('cancelled')
      if (csrf.kind !== 'success' || !object(csrf.data) || typeof csrf.data.token !== 'string' || csrf.data.headerName !== 'X-CSRF-TOKEN') throw new Error('csrf')
      headers['X-CSRF-TOKEN'] = csrf.data.token; headers['Content-Type'] = 'application/json'
    }
    const result = await requestJson(path, { fetcher: this.fetcher, credentials: 'same-origin', cache: 'no-store', signal, headers, method: body === undefined ? 'GET' : 'POST', ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    if (generation !== this.generation) throw new Error('cancelled')
    if (result.kind !== 'success' || !object(result.data) || expectedStatus !== undefined && result.status !== expectedStatus) throw new GuestRequestFailure(result.kind === 'http' ? result.status : undefined, result.kind === 'http' && result.status === 429 ? 'limit' : 'request')
    return result.data
  }
  private async run(action: () => Promise<void>) {
    if (this.view.busy) return
    const generation = this.generation
    this.set({ busy: true, error: '' })
    try { await action() }
    catch (error) { if (generation === this.generation) this.set({ error: error instanceof Error && error.message === 'limit' ? '요청이 많습니다. 잠시 후 새 인증을 시작해 주세요.' : '인증을 완료하지 못했습니다. 기존 묶음은 조회 인증과 같은 번호의 구매 인증이 필요합니다. 상태를 다시 확인하거나 새 인증을 시작해 주세요.' }) }
    finally { if (generation === this.generation) this.set({ busy: false }) }
  }
  load = async () => {
    if (this.controller.signal.aborted) this.controller = new AbortController()
    const generation = this.generation
    let known = false
    await this.run(async () => {
    const loadGeneration = this.generation
    const previousMode = this.view.linkMode, hadState = this.view.state !== 'INPUT'
    let loadedVerified = false
    this.set({ linkReady: false, passwordReady: false, linkKnown: false })
    try {
      const result = await this.call('/api/guest/checkout', undefined, 200)
      if (result.state === 'VERIFIED' || result.state === 'SENT') {
        if (result.state === 'SENT') {
          if (typeof result.phone !== 'string' || !/^\+8210\d{8}$/.test(result.phone)) throw new Error('schema')
          this.phone = result.phone
        }
        this.accept(result)
        known = true
        if (result.state === 'VERIFIED') { loadedVerified = true; this.set({ linkMode: previousMode, linkKnown: false }); await this.refreshLink() }
      } else {
        known = ['OPEN', 'EXPIRED', 'EXCHANGED', 'CONSUMED'].includes(String(result.state))
        this.set({ ...empty(), error: result.state === 'OPEN' ? '' : '이전 인증을 이어갈 수 없습니다. 새 인증번호를 요청해 주세요.' })
      }
    } catch (error) {
      if (loadGeneration !== this.generation) return
      // An initial missing checkout is expected, but its response must finish saving the session first.
      known = error instanceof GuestRequestFailure && error.status === 409 && !hadState
      this.set({ linkMode: previousMode, linkReady: false, passwordReady: false, linkKnown: false, error: hadState || loadedVerified ? '서버 준비 상태를 확인하지 못했습니다. 상태를 다시 확인해 주세요.' : '' })
    }
    })
    return known && generation === this.generation && !this.controller.signal.aborted
  }
  expire = () => {
    if (!this.view.busy && this.view.expiresAt && Date.parse(this.view.expiresAt) <= Date.now()) {
      this.phone = ''; this.set({ ...empty(), error: '인증 유효시간이 지났습니다. 새 인증을 시작해 주세요.' })
    }
  }
  reset = () => this.run(async () => {
    await this.call('/api/guest/checkout', {})
    this.phone = ''; this.set(empty())
  })
  issue = (name: string, phone: string) => this.run(async () => {
    if (!name.trim() || name.trim().length > 80 || /[<>]/.test(name) || [...name].some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127) || !/^(010\d{8}|010-\d{4}-\d{4}|\+8210\d{8})$/.test(phone)) throw new Error('input')
    await this.call('/api/guest/checkout', {})
    const result = await this.call('/api/guest/verifications', { name: name.trim(), phone, purpose: 'PURCHASE' })
    this.accept(result); this.phone = phone
  })
  verify = (code: string) => this.run(async () => {
    if (!/^\d{6}$/.test(code) || this.view.state !== 'SENT') throw new Error('input')
    const generation = this.generation
    const base = '/api/guest/verifications/' + this.view.attempt
    const exchanged = await this.call(base + '/exchange', { phone: this.phone, purpose: 'PURCHASE', code })
    if (typeof exchanged.host !== 'string' || typeof exchanged.project !== 'string' || typeof exchanged.customToken !== 'string') throw new Error('schema')
    const proof = await this.confirm(exchanged.host, exchanged.project, exchanged.customToken)
    if (generation !== this.generation) return
    const result = await this.call(base + '/verify', { phone: this.phone, purpose: 'PURCHASE', proof })
    this.accept(result); this.phone = ''
  })
  private accept(result: Record<string, unknown>) {
    if (!['SENT', 'VERIFIED'].includes(String(result.state)) || typeof result.attemptId !== 'string' || !/^[0-9a-f-]{36}$/.test(result.attemptId)
      || typeof result.maskedPhone !== 'string' || !/^010-\*{4}-\d{4}$/.test(result.maskedPhone) || typeof result.expiresAt !== 'string' || !Number.isFinite(Date.parse(result.expiresAt))) throw new Error('schema')
    this.set({ state: result.state as 'SENT' | 'VERIFIED', attempt: result.attemptId, maskedPhone: result.maskedPhone, expiresAt: result.expiresAt, passwordReady: result.passwordReady === true, linkMode: 'NEW', linkReady: false, linkRevision: 0, linkKnown: true })
  }
  private acceptLink(result: Record<string, unknown>) {
    if (!['NEW', 'EXISTING'].includes(String(result.mode)) || typeof result.ready !== 'boolean' || !Number.isInteger(result.revision) || Number(result.revision) < 0) throw new Error('schema')
    this.set({ linkMode: result.mode as 'NEW' | 'EXISTING', linkReady: result.mode === 'EXISTING' && result.ready, passwordReady: result.mode === 'NEW' && result.ready, linkRevision: Number(result.revision), linkKnown: true })
  }
  private async refreshLink() { this.acceptLink(await this.call('/api/guest/checkout/link/' + this.view.attempt)) }
  selectLink = (mode: 'NEW' | 'EXISTING') => this.run(async () => {
    if (this.view.state !== 'VERIFIED') throw new Error('state')
    this.set({ linkReady: false, passwordReady: false, linkKnown: false })
    const result = await this.call('/api/guest/checkout/link', { attemptId: this.view.attempt, mode, revision: this.view.linkRevision })
    this.acceptLink(result)
  })
  preparePassword = (password: string, confirmation: string) => this.run(async () => {
    if (this.view.state !== 'VERIFIED' || this.view.linkMode !== 'NEW' || !this.view.linkKnown || password !== confirmation || [...password].length < 12 || [...password].length > 128) throw new Error('password')
    const result = await this.call('/api/guest/checkout/password', { attemptId: this.view.attempt, password, confirmation, revision: this.view.linkRevision })
    if (result.status !== 'PASSWORD_READY') throw new Error('schema')
    this.set({ passwordReady: true })
  })
}

