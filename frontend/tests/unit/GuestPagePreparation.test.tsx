import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { GuestPage } from '../../src/pages/GuestPage'

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const token = { headerName: 'X-CSRF-TOKEN', token: 'prepared-session-token' }
const hint = '로컬 모의 인증입니다. 실제 문자나 실명 확인은 하지 않습니다. 가상의 이름과 전화번호만 입력해 주세요.'
test('guest session preparation waits for checkout response then persisted CSRF before exposing interactive readiness', async () => {
  let checkout!: (response: Response) => void, csrf!: (response: Response) => void
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    calls.push(path)
    return new Promise<Response>(resolve => { if (path === '/api/guest/checkout') checkout = resolve; else if (path === '/api/auth/csrf') csrf = resolve })
  }))
  render(<MemoryRouter><GuestPage /></MemoryRouter>)
  await waitFor(() => expect(calls).toEqual(['/api/guest/checkout']))
  expect(screen.queryByText(hint)).toBeNull(); expect(screen.queryByLabelText('구매자 이름')).toBeNull()
  await act(async () => { checkout(json({}, 409)) })
  await waitFor(() => expect(calls).toEqual(['/api/guest/checkout', '/api/auth/csrf']))
  expect(screen.queryByText(hint)).toBeNull(); expect(screen.queryByLabelText('구매자 이름')).toBeNull()
  await act(async () => { csrf(json(token)) })
  expect(await screen.findByText(hint)).toBeVisible()
  expect(screen.getByLabelText('구매자 이름')).toBeEnabled()
  expect(screen.getByRole('button', { name: '모의 인증번호 요청' })).toBeEnabled()
  expect(calls).toEqual(['/api/guest/checkout', '/api/auth/csrf'])
})
test.each([500, 401, 201])('unexpected guest checkout %s keeps inputs closed and retries a real read without issuing commands', async status => {
  let failed = true
  const calls: { path: string; method: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
    calls.push({ path, method: options?.method ?? 'GET' })
    return path === '/api/guest/checkout' ? json(failed && status !== 201 ? {} : { state: 'OPEN' }, failed ? status : 200) : json(token)
  }))
  render(<MemoryRouter><GuestPage /></MemoryRouter>)
  const retry = await screen.findByRole('button', { name: '서버 준비 상태 다시 확인' })
  expect(screen.queryByText(hint)).toBeNull(); expect(screen.queryByLabelText('구매자 이름')).toBeNull()
  expect(calls).toEqual([{ path: '/api/guest/checkout', method: 'GET' }])
  failed = false; await userEvent.click(retry)
  expect(await screen.findByText(hint)).toBeVisible()
  expect(calls).toEqual([{ path: '/api/guest/checkout', method: 'GET' }, { path: '/api/guest/checkout', method: 'GET' }, { path: '/api/auth/csrf', method: 'GET' }])
})
test.each([['HTTP503', json({}, 503)], ['empty token', json({ headerName: 'X-CSRF-TOKEN', token: '' })], ['wrong header', json({ headerName: 'OTHER', token: 'untrusted' })]] as const)('unusable CSRF %s preparation never exposes issuance inputs', async (_label, response) => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/guest/checkout' ? json({ state: 'OPEN' }) : response))
  render(<MemoryRouter><GuestPage /></MemoryRouter>)
  expect(await screen.findByRole('button', { name: '서버 준비 상태 다시 확인' })).toBeEnabled()
  expect(screen.queryByLabelText('구매자 이름')).toBeNull()
  expect(screen.queryByRole('button', { name: '모의 인증번호 요청' })).toBeNull()
})
test('leaving during checkout preparation aborts its read and cannot start a late CSRF request', async () => {
  let release!: (response: Response) => void, signal!: AbortSignal
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn((path: string, options?: RequestInit) => { calls.push(path); signal = options!.signal!; return new Promise<Response>(resolve => { release = resolve }) }))
  const mounted = render(<MemoryRouter><GuestPage /></MemoryRouter>)
  await waitFor(() => expect(release).toBeTypeOf('function'))
  mounted.unmount(); expect(signal.aborted).toBe(true)
  await act(async () => { release(json({ state: 'OPEN' })) })
  expect(calls).toEqual(['/api/guest/checkout'])
})
test('leaving during CSRF preparation aborts and discards even a late valid response', async () => {
  let release!: (response: Response) => void, signal!: AbortSignal
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
    calls.push(path)
    if (path === '/api/guest/checkout') return json({ state: 'OPEN' })
    signal = options!.signal!; return new Promise<Response>(resolve => { release = resolve })
  }))
  const mounted = render(<MemoryRouter><GuestPage /></MemoryRouter>)
  await waitFor(() => expect(release).toBeTypeOf('function'))
  mounted.unmount(); expect(signal.aborted).toBe(true)
  await act(async () => { release(json(token)) })
  expect(calls).toEqual(['/api/guest/checkout', '/api/auth/csrf'])
  expect(screen.queryByText(hint)).toBeNull()
})
