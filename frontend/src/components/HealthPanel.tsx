import { useEffect, useRef, useState } from 'react'
import { createHealthCheck } from '../api/health.ts'
import type { HealthState } from '../api/health.ts'

const messages: Record<HealthState, string> = {
  idle: '아직 서버 연결을 확인하지 않았습니다.',
  loading: '서버 연결을 확인하고 있습니다.',
  healthy: '서버와 정상적으로 연결되었습니다.',
  unhealthy: '서버가 비정상 상태를 보고했습니다. 잠시 후 다시 시도해 주세요.',
  'connection-error': '서버에 연결할 수 없습니다. 서버 실행 상태를 확인한 뒤 다시 시도해 주세요.',
  invalid: '서버 응답 형식을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  cancelled: '연결 확인을 취소했습니다.',
}

export default function HealthPanel() {
  const [state, setState] = useState<HealthState>('idle')
  const check = useRef<ReturnType<typeof createHealthCheck> | null>(null)
  useEffect(() => {
    const current = createHealthCheck(setState)
    check.current = current
    return () => { current.dispose(); check.current = null }
  }, [])

  return (
    <section className="health-panel" aria-labelledby="health-title">
      <h2 id="health-title">서버 연결 확인</h2>
      <p className="section-description">현재 서버 연결만 확인합니다. 상품 구매·결제나 데이터베이스의 정상 동작을 보장하지 않습니다.</p>
      <p role="status" aria-live="polite" aria-atomic="true">{messages[state]}</p>
      <div className="health-actions">
        <button type="button" onClick={() => void check.current?.start()}>
          {state === 'idle' ? '연결 확인' : state === 'loading' ? '다시 확인' : '다시 시도'}
        </button>
        <button type="button" disabled={state !== 'loading'} onClick={() => check.current?.cancel()}>확인 취소</button>
      </div>
    </section>
  )
}
