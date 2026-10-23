import { requestJson } from './http.ts'
import type { JsonResult } from './http.ts'

export type HealthState = 'idle' | 'loading' | 'healthy' | 'unhealthy' | 'connection-error' | 'invalid' | 'cancelled'

export function interpretHealth(result: JsonResult): HealthState {
  if (result.kind === 'cancelled') return 'cancelled'
  if (result.kind === 'network') return 'connection-error'
  if (result.kind === 'invalid-response') return 'invalid'
  const status = typeof result.data === 'object' && result.data !== null && 'status' in result.data ? result.data.status : undefined
  if (result.kind === 'http') {
    return result.status === 503 && (status === 'DOWN' || status === 'OUT_OF_SERVICE') ? 'unhealthy' : 'connection-error'
  }
  if (result.status !== 200 || typeof status !== 'string' || !status) return 'invalid'
  return status === 'UP' ? 'healthy' : 'unhealthy'
}

// A sequence guard also protects against transports that ignore AbortSignal.
export function createHealthCheck(
  publish: (state: HealthState) => void,
  load: (signal: AbortSignal) => Promise<JsonResult> = signal => requestJson('/actuator/health', { signal, cache: 'no-store' }),
) {
  let sequence = 0
  let controller: AbortController | undefined
  let disposed = false
  function invalidate() { sequence++; controller?.abort(); controller = undefined }
  return {
    async start() {
      if (disposed) return
      invalidate()
      const current = sequence
      controller = new AbortController()
      publish('loading')
      const result = await load(controller.signal)
      if (!disposed && sequence === current) {
        controller = undefined
        publish(interpretHealth(result))
      }
    },
    cancel() { invalidate(); if (!disposed) publish('cancelled') },
    dispose() { disposed = true; invalidate() },
  }
}
