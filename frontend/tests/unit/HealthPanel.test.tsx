import { StrictMode } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import HealthPanel from '../../src/components/HealthPanel'

function deferred() {
  let resolve!: (value: Response) => void
  let reject!: (error: Error) => void
  const promise = new Promise<Response>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}
function setup() {
  const pending: ReturnType<typeof deferred>[] = []
  const signals: AbortSignal[] = []
  const fetcher = vi.fn((_path: RequestInfo | URL, init?: RequestInit) => {
    const request = deferred()
    pending.push(request)
    signals.push(init?.signal as AbortSignal)
    // Intentionally ignores abort to exercise stale completion protection.
    return request.promise
  })
  vi.stubGlobal('fetch', fetcher)
  const view = render(<StrictMode><HealthPanel /></StrictMode>)
  return { ...view, user: userEvent.setup(), pending, signals, fetcher }
}
async function respond(request: ReturnType<typeof deferred>, response: Response) {
  await act(async () => { request.resolve(response); await request.promise })
}

test('초기 상태는 요청하지 않고 키보드로 시작하면 로딩 후 정상 상태를 표시한다', async () => {
  const { user, fetcher, pending } = setup()
  expect(screen.getByRole('status')).toHaveTextContent('아직 서버 연결을 확인하지 않았습니다.')
  expect(screen.getByRole('button', { name: '확인 취소' })).toBeDisabled()
  expect(fetcher).not.toHaveBeenCalled()
  await user.tab()
  expect(screen.getByRole('button', { name: '연결 확인' })).toHaveFocus()
  await user.keyboard('{Enter}')
  expect(screen.getByRole('status')).toHaveTextContent('서버 연결을 확인하고 있습니다.')
  expect(screen.getByRole('button', { name: '확인 취소' })).toBeEnabled()
  expect(fetcher).toHaveBeenCalledWith('/actuator/health', expect.objectContaining({ cache: 'no-store' }))
  await respond(pending[0], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.')
  expect(screen.getByRole('button', { name: '확인 취소' })).toBeDisabled()
})

test.each([
  ['503 DOWN', () => json({ status: 'DOWN' }, 503), '서버가 비정상 상태'],
  ['500', () => json({ message: 'private detail' }, 500), '서버에 연결할 수 없습니다'],
  ['잘못된 스키마', () => json({ hello: 'world' }), '서버 응답 형식을 확인할 수 없습니다'],
  ['HTML', () => new Response('<html>private detail</html>', { headers: { 'Content-Type': 'text/html' } }), '서버 응답 형식을 확인할 수 없습니다'],
  ['깨진 JSON', () => new Response('{', { headers: { 'Content-Type': 'application/json' } }), '서버 응답 형식을 확인할 수 없습니다'],
])('%s 오류를 안내하고 재시도하면 회복한다', async (_name, response, message) => {
  const { user, pending } = setup()
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  await respond(pending[0], response())
  expect(screen.getByRole('status')).toHaveTextContent(message)
  expect(screen.queryByText(/private detail/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버 연결을 확인하고 있습니다.')
  await respond(pending[1], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.')
})

test('통신 거절을 안내하고 재시도할 수 있다', async () => {
  const { user, pending } = setup()
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  await act(async () => { pending[0].reject(new TypeError('offline')) })
  expect(screen.getByRole('status')).toHaveTextContent('서버에 연결할 수 없습니다')
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await respond(pending[1], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.')
})

test.each(['로딩 중', '최신 성공 후'])('이전 응답이 %s 도착해도 최신 상태를 덮어쓰지 않는다', async (timing) => {
  const { user, pending, signals } = setup()
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  await user.click(screen.getByRole('button', { name: '다시 확인' }))
  expect(signals[0].aborted).toBe(true)
  expect(signals[1].aborted).toBe(false)
  if (timing === '최신 성공 후') await respond(pending[1], json({ status: 'UP' }))
  await respond(pending[0], json({ status: 'DOWN' }, 503))
  expect(screen.getByRole('status')).toHaveTextContent(timing === '로딩 중' ? '서버 연결을 확인하고 있습니다.' : '서버와 정상적으로 연결되었습니다.')
  if (timing === '로딩 중') {
    await respond(pending[1], json({ status: 'UP' }))
    expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.')
  }
})

test('취소 후 늦은 응답을 무시하고 새 요청은 성공한다', async () => {
  const { user, pending, signals } = setup()
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  await user.click(screen.getByRole('button', { name: '확인 취소' }))
  expect(signals[0].aborted).toBe(true)
  await respond(pending[0], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('연결 확인을 취소했습니다.')
  expect(screen.getByRole('button', { name: '확인 취소' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await respond(pending[1], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.')
})

test('화면 해제 시 요청을 중단하고 이전 응답이 새 화면에 반영되지 않는다', async () => {
  const first = setup()
  await first.user.click(screen.getByRole('button', { name: '연결 확인' }))
  first.unmount()
  expect(first.signals[0].aborted).toBe(true)
  const second = setup()
  await second.user.click(screen.getByRole('button', { name: '연결 확인' }))
  await respond(first.pending[0], json({ status: 'UP' }))
  expect(screen.getByRole('status')).toHaveTextContent('서버 연결을 확인하고 있습니다.')
  await respond(second.pending[0], json({ status: 'UP' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('서버와 정상적으로 연결되었습니다.'))
})
