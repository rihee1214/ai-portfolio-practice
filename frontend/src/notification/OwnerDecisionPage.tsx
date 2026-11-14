import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, useParams } from 'react-router'
import { requestJson } from '../api/http'
import { useAuthStore } from '../auth/AuthContext'

export function OwnerDecisionPage() {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const { id } = useParams()
  return session.member && id ? <Decision key={[session.member.id,auth.getGeneration(),id].join(':')} id={id} /> : <p>로그인이 필요합니다.</p>
}
function Decision({ id }: { id: string }) {
  const [result, setResult] = useState<{ reason: string; hidden: boolean } | null>(null)
  const [denied, setDenied] = useState(false)
  useEffect(() => {
    let controller = new AbortController()
    const load = () => {
      controller.abort()
      controller = new AbortController()
      const signal = controller.signal
      void requestJson('/api/account/moderation/' + encodeURIComponent(id), { signal, cache: 'no-store' }).then(r => {
        if (signal.aborted) return
        if (r.kind === 'success' && r.data && typeof r.data === 'object' && 'reason' in r.data
          && (r.data.reason === null || typeof r.data.reason === 'string') && 'hidden' in r.data && typeof r.data.hidden === 'boolean') {
          setResult({ reason: r.data.reason ?? '별도 공개 안내가 없습니다.', hidden: r.data.hidden })
        } else setDenied(true)
      })
    }
    load()
    const refresh = () => { setResult(null); setDenied(false); load() }
    window.addEventListener('focus', refresh)
    return () => { controller.abort(); window.removeEventListener('focus', refresh) }
  }, [id])
  return <section><h1>운영 조치 상세</h1>{result ? <>
    <p>{result.hidden ? '비공개 처리' : '공개 복원'}</p><p style={{ overflowWrap: 'anywhere' }}>{result.reason}</p>
    <Link to="/support">고객지원에 이의 제기</Link></> : <p role="status">{denied ? '현재 자격으로 조회할 수 없습니다.' : '조회 중'}</p>}</section>
}
