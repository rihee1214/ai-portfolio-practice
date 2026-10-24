import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { WorkspaceAccess } from '../../src/auth/WorkspaceAccess'
import App from '../../src/App'

const id = '00000000-0000-4000-8000-000000000001'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function mount(auth: AuthStore) {
  return render(<MemoryRouter><AuthContext value={auth}><WorkspaceAccess area="CONTENT" title="콘텐츠 조치"><p>보호 업무</p></WorkspaceAccess></AuthContext></MemoryRouter>)
}
test('권한 확인 중 숨김, 서버 허용 후 표시, 세션 재확인 시 즉시 숨김', async () => {
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => path === '/api/auth/me'
    ? Promise.resolve(json({ id, roles: ['ADMIN'] })) : new Promise<Response>(resolve => { release = resolve })))
  const auth = new AuthStore()
  mount(auth)
  await waitFor(() => expect(release).toBeDefined())
  expect(screen.queryByText('보호 업무')).not.toBeInTheDocument()
  await act(async () => { release(json({ access: 'CONTENT' })) })
  expect(await screen.findByText('보호 업무')).toBeVisible()
  act(() => auth.stop())
  expect(screen.queryByText('보호 업무')).not.toBeInTheDocument()
})
test('역할 ADMIN이어도 개별 권한403을 표시하고 업무를 숨긴다', async () => {
  vi.stubGlobal('fetch', vi.fn((path: string) => Promise.resolve(path === '/api/auth/me'
    ? json({ id, roles: ['ADMIN'] }) : json({ code: 'FORBIDDEN', message: 'Forbidden' }, 403))))
  mount(new AuthStore())
  expect(await screen.findByRole('alert')).toHaveTextContent('권한')
  expect(screen.queryByText('보호 업무')).not.toBeInTheDocument()
})
test('로그아웃 뒤 도착한 허용 응답은 보호 업무를 복원하지 않는다', async () => {
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn((path: string) => path === '/api/auth/me'
    ? Promise.resolve(json({ id, roles: ['ADMIN'] })) : new Promise<Response>(resolve => { release = resolve })))
  const auth = new AuthStore()
  mount(auth)
  await waitFor(() => expect(release).toBeDefined())
  act(() => auth.rejectSession({ message: '로그인이 필요합니다.' }))
  await act(async () => { release(json({ access: 'CONTENT' })) })
  expect(screen.queryByText('보호 업무')).not.toBeInTheDocument()
})

test.each(['/admin/users', '/admin/users/', '/ADMIN/users', '/admin/sellers/', '/admin/orders/', '/ADMIN/moderation/'])(
  '라우터가 허용한 변형 경로 %s도 개별 운영 권한으로 검사한다', async path => {
    window.history.replaceState(null, '', path)
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url === '/api/auth/me'
      ? json({ id, roles: ['ADMIN'] }) : url === '/api/admin/access'
        ? json({ access: 'ADMIN' }) : json({ code: 'FORBIDDEN', message: 'Forbidden' }, 403))))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('권한')
    expect(screen.queryByText(/공간 접근 권한을 확인했습니다/)).not.toBeInTheDocument()
  })
