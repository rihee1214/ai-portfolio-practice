import { requestJson } from './http'

export type RuntimePolicy = { mode: 'local-full' | 'public-demo'; restricted: boolean }
export async function readRuntimePolicy(signal?: AbortSignal, fetcher: typeof fetch = fetch): Promise<RuntimePolicy | null> {
  const timeout = AbortSignal.timeout(10_000)
  const result = await requestJson('/api/runtime-policy', { fetcher, signal: signal ? AbortSignal.any([signal, timeout]) : timeout, credentials: 'same-origin', cache: 'no-store' })
  if (result.kind !== 'success' || !result.data || typeof result.data !== 'object') return null
  const { mode, restricted } = result.data as Record<string, unknown>
  if (mode === 'local-full' && restricted === false || mode === 'public-demo' && restricted === true) return { mode, restricted }
  return null
}
