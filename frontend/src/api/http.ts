export type JsonResult =
  | { kind: 'success'; status: number; data: unknown }
  | { kind: 'http'; status: number; data?: unknown }
  | { kind: 'invalid-response'; status: number }
  | { kind: 'network' }
  | { kind: 'cancelled' }

type JsonOptions = RequestInit & { fetcher?: typeof fetch }

// Transport only: callers validate their own response schema.
export async function requestJson(path: string, options: JsonOptions = {}): Promise<JsonResult> {
  const { fetcher = fetch, ...init } = options
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.signal?.aborted) return { kind: 'cancelled' }
  try {
    const response = await fetcher(path, { ...init, headers })
    const mediaType = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase()
    const isJson = mediaType === 'application/json' || /^application\/[\w.+-]+\+json$/.test(mediaType ?? '')
    let data: unknown
    if (isJson) {
      try { data = await response.json() }
      catch (error) {
        if (init.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) return { kind: 'cancelled' }
        if (response.ok && !(error instanceof SyntaxError)) return { kind: 'network' }
        return response.ok ? { kind: 'invalid-response', status: response.status } : { kind: 'http', status: response.status }
      }
    }
    if (init.signal?.aborted) return { kind: 'cancelled' }
    if (!response.ok) return { kind: 'http', status: response.status, ...(isJson ? { data } : {}) }
    if (!isJson) return { kind: 'invalid-response', status: response.status }
    return { kind: 'success', status: response.status, data }
  } catch (error) {
    return { kind: init.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError') ? 'cancelled' : 'network' }
  }
}
