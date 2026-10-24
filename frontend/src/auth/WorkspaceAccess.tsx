import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useAuthStore } from './AuthContext'
import { requestJson } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

export type AccessArea = 'SELLER' | 'ADMIN' | 'ACCOUNTS' | 'CONTENT' | 'SUPPORT' | 'SETTLEMENTS' | 'SYSTEM'
const endpoint = (area: AccessArea) => area === 'SELLER' ? '/api/seller/access'
  : area === 'ADMIN' ? '/api/admin/access' : '/api/admin/' + area.toLowerCase() + '/access'

export function WorkspaceAccess({ area, title, children }: { area: AccessArea; title: string; children: ReactNode }) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const [result, setResult] = useState<{ session: typeof session; area: AccessArea; error: ApiErrorView | null } | null>(null)
  useEffect(() => { auth.invalidate() }, [auth, area])
  useEffect(() => {
    if (!session.member || session.busy || !session.checked) return
    const controller = new AbortController()
    let active = true
    void requestJson(endpoint(area), { credentials: 'same-origin', cache: 'no-store',
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) }).then(response => {
      if (!active) return
      if (response.kind === 'http' && response.status === 401) {
        auth.rejectSession(apiErrorView(response) ?? { message: '로그인이 필요합니다.' }); return
      }
      const valid = response.kind === 'success' && response.status === 200 && typeof response.data === 'object'
        && response.data !== null && 'access' in response.data && response.data.access === area
      setResult({ session, area, error: valid ? null : apiErrorView(response) ?? { message: '접근 상태를 확인하지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [auth, session, area])
  const checked = result?.session === session && result.area === area
  if (session.member && !session.busy && checked && !result.error) return children
  return <section className="auth-card"><h1>{title}</h1>
    {!session.checked || session.busy || (session.member && !checked) ? <p role="status">접근 권한을 확인하고 있습니다.</p>
      : !session.member ? <p>로그인이 필요합니다. <Link to="/login">로그인하러 가기</Link></p>
        : result?.error && <ApiErrorNotice error={result.error} />}
    {session.error && <ApiErrorNotice error={session.error} />}
    <button disabled={session.busy} onClick={() => auth.invalidate()}>권한 다시 확인</button>
  </section>
}
