import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { expect, it, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { NotificationPage } from '../../src/notification/NotificationPage'
import { OwnerDecisionPage } from '../../src/notification/OwnerDecisionPage'
import { RetryNotificationsPage } from '../../src/notification/RetryNotificationsPage'
import { MemoryRouter, Route, Routes } from 'react-router'

const member = { id: '00000000-0000-4000-8000-000000000010', roles: ['BUYER'] }
const row = { id: crypto.randomUUID(), kind: 'PRODUCT_ANSWER', destination: '/products/target', readAt: null }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data),
  { status, headers: { 'Content-Type': 'application/json' } })

it('shows the public decision or legacy fallback with an appeal link and drops a late response', async () => {
  const auth = new AuthStore()
  let late: ((value: Response) => void) | undefined
  let pending = false
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(json(member))
    return pending ? new Promise<Response>(resolve => { late = resolve })
      : Promise.resolve(json({ hidden: true, reason: null }))
  }))
  await auth.refresh()
  render(<AuthContext value={auth}><MemoryRouter initialEntries={['/moderation/decision']}><Routes>
    <Route path="/moderation/:id" element={<OwnerDecisionPage />} /></Routes></MemoryRouter></AuthContext>)
  expect(await screen.findByText('별도 공개 안내가 없습니다.')).toBeVisible()
  expect(screen.getByRole('link', { name: '고객지원에 이의 제기' })).toHaveAttribute('href', '/support')
  pending = true
  fireEvent.focus(window)
  await waitFor(() => expect(late).toBeDefined())
  act(() => auth.stop())
  await act(async () => late!(json({ hidden: true, reason: '이전 사용자 전용 안내' })))
  expect(screen.queryByText('이전 사용자 전용 안내')).toBeNull()
  expect(screen.queryByRole('link')).toBeNull()
})

it('clears the SYSTEM failure list after logout even when the request resolves later', async () => {
  const auth = new AuthStore()
  let late: ((value: Response) => void) | undefined
  const fetcher = vi.fn((path: string) => path === '/api/auth/me'
    ? Promise.resolve(json({ ...member, roles: ['ADMIN'] }))
    : new Promise<Response>(resolve => { late = resolve }))
  vi.stubGlobal('fetch', fetcher)
  await auth.refresh()
  render(<AuthContext value={auth}><RetryNotificationsPage /></AuthContext>)
  await waitFor(() => expect(late).toBeDefined())
  act(() => auth.stop())
  await act(async () => late!(json([{ event: 'private-event', job: 'private-job' }])))
  expect(screen.getByText('운영 권한이 필요합니다.')).toBeVisible()
  expect(screen.queryByText(/private-job/)).toBeNull()
  expect(fetcher.mock.calls.every(([path]) => !path.endsWith('/retry'))).toBe(true)
})

it('clears the private notification destination on current-access denial and drops the response after logout', async () => {
  const auth = new AuthStore()
  let late: ((r: Response) => void) | undefined
  let mode = 'allowed'
  vi.stubGlobal('fetch', vi.fn((path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(json(member))
    if (mode === 'denied') return Promise.resolve(json({}, 403))
    if (mode === 'late') return new Promise<Response>(resolve => { late = resolve })
    return Promise.resolve(json({ items: [row], next: null }))
  }))
  await auth.refresh()
  render(<StrictMode><AuthContext value={auth}><NotificationPage /></AuthContext></StrictMode>)
  expect(await screen.findByRole('link', { name: '상품 문의에 답변이 등록되었습니다.' })).toHaveAttribute('href',row.destination)
  mode = 'denied'
  fireEvent.focus(window)
  await screen.findByRole('alert')
  expect(screen.queryByRole('link')).toBeNull()
  mode = 'late'
  fireEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  await waitFor(() => expect(late).toBeDefined())
  act(() => auth.stop())
  await act(async () => late!(json({ items: [row], next: null })))
  expect(screen.queryByRole('link')).toBeNull()
  expect(screen.getByText('로그인 후 알림을 확인할 수 있습니다.')).toBeVisible()
})
