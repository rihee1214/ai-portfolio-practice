import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useAuthStore } from './AuthContext'
import { AuthEntryActions } from './AuthEntryActions'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { checkWorkspaceAccess, type AccessArea } from './workspacePolicy'

export function WorkspaceAccess({ area, title, children }: { area: AccessArea; title: string; children: ReactNode }) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const [result, setResult] = useState<{ session: typeof session; area: AccessArea; error: ApiErrorView | null } | null>(null)
  useEffect(() => { auth.invalidate() }, [auth, area])
  useEffect(() => {
    if (!session.member || session.busy || !session.checked) return
    const controller = new AbortController()
    let active = true
    void checkWorkspaceAccess(area, AbortSignal.any([controller.signal, AbortSignal.timeout(10000)])).then(({ response, allowed }) => {
      if (!active) return
      if (response.kind === 'http' && response.status === 401) {
        auth.rejectSession(apiErrorView(response) ?? { message: '로그인이 필요합니다.' }); return
      }
      setResult({ session, area, error: allowed ? null : apiErrorView(response) ?? { message: '접근 상태를 확인하지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [auth, session, area])
  const checked = result?.session === session && result.area === area
  if (session.member && !session.busy && checked && !result.error) return children
  const checking = !session.checked || session.busy || (session.member && !checked)
  const error = session.error ?? (checked ? result.error : null)
  const guest = !checking && !error && !session.member
  return <section className="auth-card"><h1>{title}</h1>
    {checking && <p role="status">접근 권한을 확인하고 있습니다.</p>}
    {error && <ApiErrorNotice error={error} />}
    {guest ? <><p>로그인이 필요합니다.</p><AuthEntryActions /></>
      : <button disabled={session.busy} onClick={() => auth.invalidate()}>권한 다시 확인</button>}
  </section>
}
