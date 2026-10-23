import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test, vi } from 'vitest'
import App from '../../src/App'

const requestId = '11111111-1111-4111-8111-111111111111'
const nextId = '22222222-2222-4222-8222-222222222222'
function reply(data: unknown, status = 404) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}
function error(id = requestId) {
  return { code: 'NOT_FOUND', message: '요청한 대상을 찾을 수 없습니다.', requestId: id }
}
beforeEach(() => {
  window.history.replaceState({}, '', '/dev/api-errors')
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

test('개발 확인 화면에서 수동 요청 후 공통 오류와 요청 ID를 표시한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(reply(error()))
  vi.stubGlobal('fetch', fetcher)
  render(<App />)
  expect(fetcher).not.toHaveBeenCalled()
  await userEvent.setup().click(screen.getByRole('button', { name: '오류 응답 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('요청한 대상을 찾을 수 없습니다.')
  expect(screen.getByRole('alert')).toHaveTextContent(requestId)
  expect(fetcher).toHaveBeenCalledWith('/api/error-preview', expect.objectContaining({ cache: 'no-store' }))
})

test.each([
  ['unknown code', { ...error(), code: 'SECRET' }],
  ['missing message', { code: 'NOT_FOUND', requestId }],
  ['wrong status', { ...error(), code: 'CONFLICT' }],
  ['array', [error()]],
])('%s은 원문이나 ID 없이 일반 안내로 표시한다', async (_name, body) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(body)))
  render(<App />)
  await userEvent.setup().click(screen.getByRole('button', { name: '오류 응답 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  expect(screen.getByRole('alert')).not.toHaveTextContent(requestId)
})

test('서버 임의 메시지와 잘못된 ID는 화면에 노출하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ ...error('private-id'), message: '<script>private SQL</script>' })))
  render(<App />)
  await userEvent.setup().click(screen.getByRole('button', { name: '오류 응답 확인' }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('요청한 대상을 찾을 수 없습니다.')
  expect(alert).not.toHaveTextContent('private')
  expect(alert).not.toHaveTextContent('요청 ID')
})

test.each(['HTML', 'broken JSON', 'network'])('%s 실패 후 재시도는 새 ID만 표시한다', async (kind) => {
  const fetcher = vi.fn()
  if (kind === 'network') fetcher.mockRejectedValueOnce(new TypeError('private network detail'))
  else fetcher.mockResolvedValueOnce(new Response(kind === 'HTML' ? '<html>private</html>' : '{', {
    status: 502, headers: { 'Content-Type': kind === 'HTML' ? 'text/html' : 'application/json' },
  }))
  fetcher.mockResolvedValueOnce(reply(error(nextId)))
  vi.stubGlobal('fetch', fetcher)
  render(<App />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: '오류 응답 확인' }))
  expect(await screen.findByRole('alert')).not.toHaveTextContent('private')
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(nextId)
})

test('재요청·취소·화면 해제 후 늦은 응답을 반영하지 않는다', async () => {
  const pending: { resolve: (response: Response) => void; signal: AbortSignal }[] = []
  vi.stubGlobal('fetch', vi.fn((_path: string, init: RequestInit) => new Promise<Response>(resolve => {
    pending.push({ resolve, signal: init.signal as AbortSignal })
  })))
  const view = render(<App />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: '오류 응답 확인' }))
  expect(screen.getByRole('status')).toHaveTextContent('응답을 기다리고 있습니다.')
  await user.click(screen.getByRole('button', { name: '다시 확인' }))
  expect(pending[0].signal.aborted).toBe(true)
  await act(async () => { pending[1].resolve(reply(error(nextId))) })
  await act(async () => { pending[0].resolve(reply(error())) })
  expect(screen.getByRole('alert')).toHaveTextContent(nextId)
  expect(screen.getByRole('alert')).not.toHaveTextContent(requestId)
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '요청 취소' }))
  await act(async () => { pending[2].resolve(reply(error())) })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('요청을 취소했습니다.')
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  view.unmount()
  expect(pending[3].signal.aborted).toBe(true)
  await act(async () => { pending[3].resolve(reply(error())) })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('성공 응답은 오류나 이전 ID를 표시하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(reply(error())).mockResolvedValueOnce(reply({ ok: true }, 200)))
  render(<App />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: '오류 응답 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(requestId)
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('요청이 완료되었습니다.')
})
