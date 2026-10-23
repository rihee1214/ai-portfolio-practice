import { useEffect, useRef, useState } from 'react'
import { requestJson } from '../api/http'
import { apiErrorView } from '../api/apiError'
import type { ApiErrorView } from '../api/apiError'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

type State = { kind: 'idle' | 'loading' | 'success' | 'cancelled' } | { kind: 'error'; error: ApiErrorView }

export default function ApiErrorPreview() {
  const [state, setState] = useState<State>({ kind: 'idle' })
  const sequence = useRef(0)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => { sequence.current++; controller.current?.abort() }, [])

  async function start() {
    const current = ++sequence.current
    controller.current?.abort()
    controller.current = new AbortController()
    setState({ kind: 'loading' })
    const result = await requestJson('/api/error-preview', { signal: controller.current.signal, cache: 'no-store' })
    if (current !== sequence.current) return
    controller.current = null
    const error = apiErrorView(result)
    setState(error ? { kind: 'error', error } : { kind: result.kind === 'cancelled' ? 'cancelled' : 'success' })
  }
  function cancel() {
    sequence.current++
    controller.current?.abort()
    controller.current = null
    setState({ kind: 'cancelled' })
  }

  return (
    <section className="workspace-page">
      <h1>오류 안내 확인</h1>
      <p className="page-description">개발용 확인 화면입니다. 없는 API를 요청하여 오류 안내와 요청 ID를 확인합니다. 구매·회원 기능을 실행하지 않습니다.</p>
      <p role="status" aria-live="polite">
        {state.kind === 'idle' ? '버튼을 눌러 오류 안내를 확인하세요.'
          : state.kind === 'loading' ? '응답을 기다리고 있습니다.'
            : state.kind === 'cancelled' ? '요청을 취소했습니다.'
              : state.kind === 'success' ? '요청이 완료되었습니다.' : ''}
      </p>
      {state.kind === 'error' && <ApiErrorNotice error={state.error} />}
      <div className="health-actions">
        <button type="button" onClick={() => void start()}>
          {state.kind === 'idle' ? '오류 응답 확인' : state.kind === 'loading' ? '다시 확인' : '다시 시도'}
        </button>
        <button type="button" disabled={state.kind !== 'loading'} onClick={cancel}>요청 취소</button>
      </div>
    </section>
  )
}
