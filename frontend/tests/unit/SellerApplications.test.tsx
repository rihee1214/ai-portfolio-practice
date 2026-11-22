import { render, screen, waitFor, act, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import App from '../../src/App'
import { AuthStore } from '../../src/auth/AuthStore'
import { SellerApplicationsStore } from '../../src/seller/SellerApplicationsStore'

const id = '00000000-0000-4000-8000-000000000001'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

test('심사 실패는 요청 ID와 함께 표시하며 POST를 자동 재전송하지 않는다', async () => {
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'ADMIN'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (init.method === 'POST') return new Response(JSON.stringify({ code: 'CONFLICT', message: 'conflict', requestId: '00000000-0000-4000-8000-000000000009' }), {
      status: 409, headers: { 'Content-Type': 'application/json', 'X-Request-ID': '00000000-0000-4000-8000-000000000009' },
    })
    return json({ items: [], page: 0, hasMore: false })
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  const store = new SellerApplicationsStore(auth, true)
  const disconnect = store.connect()
  await waitFor(() => expect(store.getSnapshot().items).toEqual([]))
  expect(await store.save({ decision: 'APPROVED', reason: '모의 승인' }, id)).toBe(false)
  expect(store.getSnapshot().error?.requestId).toBe('00000000-0000-4000-8000-000000000009')
  expect(fetcher.mock.calls.filter(call => call[1].method === 'POST')).toHaveLength(1)
  expect(fetcher.mock.calls.find(call => call[1].method === 'POST')![0]).toBe('/api/admin/accounts/seller-applications/' + id + '/decision')
  disconnect()
})
test('승인 전 회원의 모의 판매 신청은 CSRF와 함께 본인 신청 API로 전달된다', async () => {
  let items: unknown[] = []
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (init.method === 'POST') {
      const fields = JSON.parse(String(init.body))
      const item = { ...fields, id, applicantId: id, status: 'PENDING', createdAt: '2026-10-24T00:00:00Z',
        events: [{ actorId: id, status: 'PENDING', reason: '모의 판매 신청', occurredAt: '2026-10-24T00:00:00Z' }] }
      items = [item]; return json(item, 201)
    }
    return json({ items, page: 0, hasMore: false })
  })
  vi.stubGlobal('fetch', fetcher)
  window.history.replaceState({}, '', '/account')
  render(<App />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: '판매 신청·이력' }))
  await user.type(await screen.findByLabelText('모의 매대 이름'), '가상 매대')
  const nav = within(screen.getByRole('navigation', { name: '회원 서비스' }))
  expect(nav.queryByRole('link', { name: '판매 신청 상태' })).not.toBeInTheDocument()
  await user.type(screen.getByLabelText('판매 계획'), '가상 상품 판매')
  await user.click(screen.getByRole('button', { name: '판매 신청하기' }))
  await screen.findByText('처리했습니다.')
  const post = fetcher.mock.calls.find(call => call[1].method === 'POST')!
  expect(post[0]).toBe('/api/seller/applications')
  expect(new Headers(post[1].headers).get('X-CSRF-TOKEN')).toBe('csrf')
  expect(JSON.parse(String(post[1].body))).toEqual({ displayName: '가상 매대', description: '가상 상품 판매' })
  expect(screen.getByText('심사 대기', { selector: 'strong' })).toBeVisible()
  expect(nav.getByRole('link', { name: '판매 신청 상태' })).toHaveAttribute('href', '/account/seller-application')
})

test('로그인 상태 재확인 중 개인 신청을 숨기고 늦은 목록 응답을 무시한다', async () => {
  let release!: (response: Response) => void
  let signedIn = true
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return signedIn ? json({ id, roles: ['BUYER'] }) : json({}, 401)
    return new Promise<Response>(resolve => { release = resolve })
  }))
  window.history.replaceState({}, '', '/account/seller-application')
  render(<App />)
  await waitFor(() => expect(release).toBeDefined())
  signedIn = false
  await act(async () => { window.dispatchEvent(new Event('focus')) })
  await screen.findByText(/로그인이 필요합니다/)
  await act(async () => release(json({ items: [{ id, applicantId: id, displayName: '노출되면 안 됨', description: '비공개', status: 'PENDING', createdAt: '2026-10-24T00:00:00Z', events: [{ actorId: id, status: 'PENDING', reason: '모의 판매 신청', occurredAt: '2026-10-24T00:00:00Z' }] }], page: 0, hasMore: false })))
  expect(screen.queryByText('노출되면 안 됨')).not.toBeInTheDocument()
})
