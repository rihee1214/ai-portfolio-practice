import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

function navigation() {
  return within(screen.getByRole('navigation', { name: '회원 서비스' }))
}

test('공통 헤더는 조회 중 권한 메뉴를 숨기고 확인된 비회원에게 가입과 로그인을 표시한다', async () => {
  window.history.replaceState(null, '', '/')
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me'
    ? new Promise<Response>(resolve => { release = resolve }) : json({}, 404)))
  render(<App />)
  await waitFor(() => expect(release).toBeDefined())
  expect(navigation().getAllByRole('link').map(link => link.textContent)).toEqual(['장바구니'])
  await act(async () => { release(json({}, 401)) })
  expect(await navigation().findByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
  expect(navigation().getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/signup')
  expect(navigation().queryByRole('link', { name: /내 계정|판매|운영/ })).not.toBeInTheDocument()
})

test('공통 헤더는 세션 오류를 비회원으로 표시하지 않는다', async () => {
  window.history.replaceState(null, '', '/account')
  vi.stubGlobal('fetch', vi.fn(async () => json({}, 503)))
  render(<App />)
  await screen.findByRole('alert')
  expect(navigation().getAllByRole('link').map(link => link.textContent)).toEqual(['장바구니'])
})

test('구매자는 내 계정을 사용하고 판매 신청자는 자신의 신청 상태로 이동할 수 있다', async () => {
  window.history.replaceState(null, '', '/')
  let release!: (response: Response) => void
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (path === '/api/seller/applications?page=0') return new Promise<Response>(resolve => { release = resolve })
    return json({}, 404)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<App />)
  expect(await navigation().findByRole('link', { name: '내 계정' })).toHaveAttribute('href', '/account')
  await waitFor(() => expect(release).toBeDefined())
  expect(navigation().queryByRole('link', { name: '판매 신청 상태' })).not.toBeInTheDocument()
  await act(async () => { release(json({
    page: 0, hasMore: false, items: [{
      id: '00000000-0000-4000-8000-000000000002', applicantId: id, displayName: '매대',
      description: '판매 계획', status: 'PENDING', createdAt: '2026-11-19T00:00:00Z',
      events: [{ actorId: id, status: 'PENDING', reason: '신청', occurredAt: '2026-11-19T00:00:00Z' }],
    }],
  })) })
  expect(await navigation().findByRole('link', { name: '판매 신청 상태' })).toHaveAttribute('href', '/account/seller-application')
  expect(navigation().queryByRole('link', { name: /로그인|회원가입|센터/ })).not.toBeInTheDocument()
  expect(fetcher.mock.calls.some(([path]) => path.endsWith('/access'))).toBe(false)
})

test('구매자 자격이 있는 승인 판매자는 내 계정과 판매자 센터를 함께 사용한다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me'
    ? json({ id, roles: ['BUYER', 'SELLER'] }) : path === '/api/seller/access'
      ? json({ access: 'SELLER' }) : json({}, 404)))
  render(<App />)
  expect(await navigation().findByRole('link', { name: '판매자 센터' })).toHaveAttribute('href', '/seller')
  expect(navigation().getByRole('link', { name: '내 계정' })).toHaveAttribute('href', '/account')
  expect(navigation().queryByRole('link', { name: /판매 신청 상태|운영자 센터|로그인|회원가입/ })).not.toBeInTheDocument()
})

test('부분 운영 권한은 공통 헤더와 운영자 업무 메뉴에서 실제 허용 범위만 표시한다', async () => {
  window.history.replaceState(null, '', '/admin')
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'SELLER', 'ADMIN'] })
    if (path === '/api/seller/access') return json({ access: 'SELLER' })
    if (path === '/api/admin/access') return json({ access: 'ADMIN' })
    if (path === '/api/admin/content/access') return json({ access: 'CONTENT' })
    if (path === '/api/admin/accounts/access') return json({ access: 'SYSTEM' })
    return json({}, 403)
  }))
  render(<App />)
  expect(await navigation().findByRole('link', { name: '운영자 센터' })).toHaveAttribute('href', '/admin')
  expect(navigation().getByRole('link', { name: '내 계정' })).toBeVisible()
  expect(await navigation().findByRole('link', { name: '판매자 센터' })).toBeVisible()
  const sidebar = within(await screen.findByRole('navigation', { name: '운영자 메뉴' }))
  expect(sidebar.getAllByRole('link').map(link => link.textContent)).toEqual([
    '한눈에 보기', '운영 대시보드', '콘텐츠 조치', '카테고리 운영', '신고 접수', '사이트 게시',
  ])
  const overview = within(screen.getByRole('heading', { name: '사이트 운영' }).closest('section')!)
  expect(overview.getAllByRole('link').map(link => new URL((link as HTMLAnchorElement).href).pathname)).toEqual([
    '/admin/dashboard', '/admin/moderation', '/admin/catalog', '/admin/reports', '/admin/publications',
  ])
})

test('운영자 권한이 없으면 센터 링크를 숨긴다', async () => {
  window.history.replaceState(null, '', '/admin')
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'ADMIN'] })
    if (path === '/api/admin/access') return json({ access: 'ADMIN' })
    return json({}, 403)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<App />)
  const sidebar = within(await screen.findByRole('navigation', { name: '운영자 메뉴' }))
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/admin/system/access', expect.anything()))
  expect(sidebar.getAllByRole('link').map(link => link.textContent)).toEqual(['한눈에 보기'])
  expect(navigation().getAllByRole('link').map(link => link.textContent)).toEqual(['장바구니', '내 계정'])
})

test('로그아웃 뒤 도착한 판매 권한 응답은 공통 헤더 메뉴를 복원하지 않는다', async () => {
  window.history.replaceState(null, '', '/account')
  let release!: (response: Response) => void
  let logout = false
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return logout ? json({}, 401) : json({ id, roles: ['BUYER', 'SELLER'] })
    if (path === '/api/seller/access') return new Promise<Response>(resolve => { release = resolve })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (path === '/api/auth/logout') { logout = true; return json({ status: 'LOGGED_OUT' }) }
    return json({}, 404)
  }))
  render(<App />)
  const button = await screen.findByRole('button', { name: '로그아웃' })
  await waitFor(() => expect(release).toBeDefined())
  await userEvent.setup().click(button)
  expect(await navigation().findByRole('link', { name: '로그인' })).toBeVisible()
  await act(async () => { release(json({ access: 'SELLER' })) })
  expect(navigation().queryByRole('link', { name: /내 계정|센터/ })).not.toBeInTheDocument()
})
