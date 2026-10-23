import type { JsonResult } from './http'

export type ApiErrorView = { message: string; requestId?: string }
const fallback = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
const messages: Record<string, string> = {
  INVALID_REQUEST: '요청 내용을 확인해 주세요.',
  AUTHENTICATION_REQUIRED: '로그인이 필요합니다.',
  FORBIDDEN: '요청을 처리할 권한이 없습니다.',
  NOT_FOUND: '요청한 대상을 찾을 수 없습니다.',
  METHOD_NOT_ALLOWED: '지원하지 않는 요청 방식입니다.',
  NOT_ACCEPTABLE: '요청한 응답 형식을 지원하지 않습니다.',
  CONFLICT: '현재 상태에서는 요청을 처리할 수 없습니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 요청 형식입니다.',
  RATE_LIMITED: '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  REQUEST_FAILED: '요청을 처리할 수 없습니다.',
  INTERNAL_ERROR: fallback,
}
const codes: Record<number, string> = {
  400: 'INVALID_REQUEST', 401: 'AUTHENTICATION_REQUIRED', 403: 'FORBIDDEN', 404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED', 406: 'NOT_ACCEPTABLE', 409: 'CONFLICT', 415: 'UNSUPPORTED_MEDIA_TYPE', 429: 'RATE_LIMITED',
}
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

// Health has its own contract. Only business API callers use this presentation adapter.
export function apiErrorView(result: JsonResult): ApiErrorView | null {
  if (result.kind === 'success' || result.kind === 'cancelled') return null
  if (result.kind === 'network') return { message: '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.' }
  if (result.kind !== 'http') return { message: fallback }
  const data = result.data
  if (typeof data !== 'object' || data === null || Array.isArray(data)) return { message: fallback }
  const body = data as Record<string, unknown>
  const expected = codes[result.status] ?? (result.status >= 500 && result.status <= 599 ? 'INTERNAL_ERROR'
    : result.status >= 400 && result.status <= 499 ? 'REQUEST_FAILED' : undefined)
  if (body.code !== expected || !expected || typeof body.message !== 'string'
    || !body.message.trim() || body.message.length > 300) return { message: fallback }
  return {
    message: messages[expected],
    ...(typeof body.requestId === 'string' && uuidV4.test(body.requestId) ? { requestId: body.requestId } : {}),
  }
}
