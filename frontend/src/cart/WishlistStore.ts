import type { AuthStore } from '../auth/AuthStore'
import { requestJson } from '../api/http'
import { apiErrorView } from '../api/apiError'
import { detailValid, slugValid, type Detail } from '../catalog/Browse'
import { uuid } from './GuestCart'
type Line = { productId: string; detail: Detail | null }
type State = { items: Line[]; busy: boolean; ready: boolean; error: string; notice: string }
const empty = (): State => ({ items: [], busy: false, ready: false, error: '', notice: '' })
function valid(value: unknown): value is { items: Line[] } {
  if (!value || typeof value !== 'object') return false
  const rows = (value as { items?: Line[] }).items
  return Array.isArray(rows) && rows.length <= 100 && new Set(rows.map(i => i?.productId)).size === rows.length
    && rows.every(i => i && uuid(i.productId) && (i.detail === null || detailValid(i.detail)))
}
export class WishlistStore {
  private state = empty()
  private generation = 0
  private controller = new AbortController()
  private listeners = new Set<() => void>()
  constructor(private auth: AuthStore) {}
  getSnapshot = () => this.state
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  private emit(state: State) { this.state = state; this.listeners.forEach(fn => fn()) }
  private stop = () => { ++this.generation; this.controller.abort(); this.emit(empty()) }
  connect = () => { const sync = () => { void this.refresh() }; const off = this.auth.subscribe(sync); sync(); return () => { off(); this.stop() } }
  private async run(suffix = '', method = 'GET') {
    const generation = ++this.generation; this.controller.abort(); this.controller = new AbortController()
    const owner = this.auth.getSnapshot().member?.id, signal = this.controller.signal
    this.emit(empty())
    const auth = this.auth.getSnapshot()
    if (!owner || !auth.checked || auth.busy || auth.error) return
    this.emit({ ...empty(), busy: true })
    const check = () => { if (generation !== this.generation || signal.aborted || this.auth.getSnapshot().member?.id !== owner || this.auth.getSnapshot().busy) throw new Error('cancelled') }
    const request = async (path: string, init?: RequestInit) => {
      check()
      const result = await requestJson(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(10000)]) })
      check()
      if (result.kind !== 'success') {
        if (result.kind === 'http' && [401,403].includes(result.status)) this.auth.rejectSession({ message: '로그인 상태와 이용 자격을 다시 확인해 주세요.' })
        throw new Error(apiErrorView(result)?.message ?? '찜 목록을 확인하지 못했습니다.')
      }
      return result
    }
    try {
      const headers: Record<string,string> = {}
      if (method !== 'GET') {
        const csrf = await request('/api/auth/csrf'), token = csrf.data as { headerName?: string; token?: string }
        if (csrf.status !== 200 || token?.headerName !== 'X-CSRF-TOKEN' || typeof token.token !== 'string' || !token.token) throw new Error('요청 확인 정보를 받지 못했습니다.')
        headers['X-CSRF-TOKEN'] = token.token
      }
      const result = await request('/api/wishlist'+suffix+'?owner='+owner, { method, headers })
      if (result.status !== 200 || !valid(result.data)) throw new Error('찜 응답 형식을 확인하지 못했습니다.')
      check(); this.emit({ ...empty(), ready: true, items: result.data.items, notice: method === 'PUT' ? '찜한 상품에 저장했습니다.' : method === 'DELETE' ? '찜을 해제했습니다.' : '' })
    } catch (e) {
      if (generation === this.generation && !signal.aborted) this.emit({ ...empty(), error: e instanceof Error ? e.message : '찜 요청을 처리하지 못했습니다.' })
    }
  }
  refresh = async () => this.run()
  add = async (slug: string) => { if (!this.state.busy && slugValid(slug)) await this.run('/'+encodeURIComponent(slug),'PUT') }
  remove = async (id: string) => { if (!this.state.busy && uuid(id)) await this.run('/'+id,'DELETE') }
}
