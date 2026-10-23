import { expect, test } from 'vitest'
import { apiErrorView } from '../../src/api/apiError'

const id = '11111111-1111-4111-8111-111111111111'
test.each([
  [400, 'INVALID_REQUEST', '요청 내용을 확인해 주세요.'],
  [401, 'AUTHENTICATION_REQUIRED', '로그인이 필요합니다.'],
  [403, 'FORBIDDEN', '요청을 처리할 권한이 없습니다.'],
  [404, 'NOT_FOUND', '요청한 대상을 찾을 수 없습니다.'],
  [405, 'METHOD_NOT_ALLOWED', '지원하지 않는 요청 방식입니다.'],
  [406, 'NOT_ACCEPTABLE', '요청한 응답 형식을 지원하지 않습니다.'],
  [409, 'CONFLICT', '현재 상태에서는 요청을 처리할 수 없습니다.'],
  [415, 'UNSUPPORTED_MEDIA_TYPE', '지원하지 않는 요청 형식입니다.'],
  [429, 'RATE_LIMITED', '요청이 많습니다. 잠시 후 다시 시도해 주세요.'],
  [418, 'REQUEST_FAILED', '요청을 처리할 수 없습니다.'],
  [500, 'INTERNAL_ERROR', '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'],
  [503, 'INTERNAL_ERROR', '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'],
])('%s %s는 고정 안내와 요청 ID를 반환한다', (status, code, message) => {
  expect(apiErrorView({ kind: 'http', status, data: { code, message: 'server text', requestId: id } }))
    .toEqual({ message, requestId: id })
})

test.each([null, {}, { code: 'NOT_FOUND', message: '' }, { code: 'NOT_FOUND', message: 'x'.repeat(301) }])(
  '잘못된 body는 일반 오류이고 ID가 없다: %j', data => {
    expect(apiErrorView({ kind: 'http', status: 404, data })).toEqual({ message: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.' })
  },
)
test.each([undefined, 42, 'x'.repeat(1000), '11111111-1111-1111-8111-111111111111', '<b>id</b>'])('잘못된 ID %s는 생략한다', requestId => {
  expect(apiErrorView({ kind: 'http', status: 404, data: { code: 'NOT_FOUND', message: 'safe', requestId } }))
    .toEqual({ message: '요청한 대상을 찾을 수 없습니다.' })
})
test('성공·취소는 오류가 아니고 잘못된 성공 JSON은 일반 안내다', () => {
  expect(apiErrorView({ kind: 'success', status: 200, data: {} })).toBeNull()
  expect(apiErrorView({ kind: 'cancelled' })).toBeNull()
  expect(apiErrorView({ kind: 'invalid-response', status: 200 })).toEqual({ message: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.' })
})
