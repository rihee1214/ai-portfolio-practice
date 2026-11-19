import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import AuthLayout from '../../src/auth/AuthLayout'
import { AuthStore } from '../../src/auth/AuthStore'
import { AccountDetailsPage } from '../../src/pages/AccountPages'
import { PasswordPage } from '../../src/pages/PasswordPages'
import { AccountPage } from '../../src/pages/AuthPages'

const id = '00000000-0000-4000-8000-000000000001'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const profile = { email: 'buyer@example.com', displayName: '구매자', phone: null }

function mountAccount() {
  return mount(<Routes><Route element={<AuthLayout />}>
    <Route path="/account" element={<AccountPage />} />
  </Route></Routes>, '/account')
}

function expectAccountControlsHidden() {
  expect(screen.queryByRole('navigation', { name: '회원 메뉴' })).not.toBeInTheDocument()
  expect(screen.queryAllByRole('link', { name: /프로필|배송지|찜한 상품|쿠폰|주문·결제 이력|신고|알림|판매 신청/ })).toHaveLength(0)
  expect(screen.queryByText('회원 ID')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText('탈퇴 확인 비밀번호')).not.toBeInTheDocument()
}

test('내 계정은 세션 확인 중에 안내만 표시한다', () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
  mountAccount()

  expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있습니다.')
  expect(screen.queryAllByRole('link')).toHaveLength(0)
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expectAccountControlsHidden()
})

test('내 계정은 비회원에게 로그인과 회원가입을 안내하고 회원 기능을 숨긴다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ code: 'AUTHENTICATION_REQUIRED', message: 'Authentication required' }, 401)))
  mountAccount()

  await screen.findByText('로그인이 필요합니다.')
  expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
  expect(screen.getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/signup')
  expect(screen.queryAllByRole('link')).toHaveLength(2)
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expectAccountControlsHidden()
})

test('내 계정은 세션 조회 실패 때만 재시도를 제공한다', async () => {
  const requestId = '00000000-0000-4000-8000-000000000123'
  const fetcher = vi.fn().mockResolvedValueOnce(json({ code: 'INTERNAL_ERROR', message: 'Internal error', requestId }, 500))
    .mockResolvedValueOnce(json({}, 401))
  vi.stubGlobal('fetch', fetcher)
  mountAccount()

  expect(await screen.findByRole('alert')).toHaveTextContent(requestId)
  expect(screen.queryAllByRole('link')).toHaveLength(0)
  expectAccountControlsHidden()
  await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
  await screen.findByText('로그인이 필요합니다.')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/auth/me', '/api/auth/me'])
})

test('내 계정은 인증된 운영자에게 기존 메뉴와 로그아웃을 제공한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id, roles: ['BUYER', 'ADMIN'] })))
  mountAccount()

  expect(await screen.findByText('로그인되었습니다.')).toBeVisible()
  expect(screen.getByRole('navigation', { name: '회원 메뉴' })).toBeVisible()
  expect(screen.getByRole('link', { name: '프로필 변경' })).toHaveAttribute('href', '/account/profile')
  expect(screen.getByRole('link', { name: '내 알림' })).toHaveAttribute('href', '/account/notifications')
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
  expect(screen.queryByLabelText('탈퇴 확인 비밀번호')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
})

test('내 계정은 로그아웃 실패와 이후 세션 조회 실패를 구분한다', async () => {
  const logoutRequestId = '00000000-0000-4000-8000-000000000124'
  const sessionRequestId = '00000000-0000-4000-8000-000000000125'
  let releaseSession!: (response: Response) => void
  const pendingSession = new Promise<Response>(resolve => { releaseSession = resolve })
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ id, roles: ['BUYER'] }))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'INTERNAL_ERROR', message: 'Logout failed', requestId: logoutRequestId }, 500))
    .mockReturnValueOnce(pendingSession)
    .mockResolvedValueOnce(json({}, 401))
  vi.stubGlobal('fetch', fetcher)
  mountAccount()
  const user = userEvent.setup()

  await user.click(await screen.findByRole('button', { name: '로그아웃' }))
  expect(await screen.findByText(logoutRequestId)).toBeVisible()
  expect(await screen.findByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있습니다.')
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expectAccountControlsHidden()

  await act(async () => { releaseSession(json({ code: 'INTERNAL_ERROR', message: 'Session lookup failed', requestId: sessionRequestId }, 500)) })
  expect(await screen.findByText(sessionRequestId)).toBeVisible()
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await screen.findByText('로그인이 필요합니다.')
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/auth/me', '/api/auth/csrf', '/api/auth/logout', '/api/auth/me', '/api/auth/me'])
})

test('내 계정은 탈퇴 실패 후 유효한 세션을 확인해 비워진 입력과 회원 메뉴를 제공한다', async () => {
  const requestId = '00000000-0000-4000-8000-000000000126'
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ id, roles: ['BUYER'] }))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'INVALID_REQUEST', message: 'Wrong password', requestId }, 400))
    .mockResolvedValueOnce(json({ id, roles: ['BUYER'] }))
  vi.stubGlobal('fetch', fetcher)
  mountAccount()
  const user = userEvent.setup()

  await user.type(await screen.findByLabelText('탈퇴 확인 비밀번호'), 'wrong-password')
  await user.click(screen.getByLabelText('탈퇴 후 계정을 복구할 수 없음을 확인했습니다.'))
  await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(requestId)
  expect(await screen.findByLabelText('탈퇴 확인 비밀번호')).toHaveValue('')
  expect(screen.getByLabelText('탈퇴 후 계정을 복구할 수 없음을 확인했습니다.')).not.toBeChecked()
  expect(screen.getByRole('navigation', { name: '회원 메뉴' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/auth/me', '/api/auth/csrf', '/api/account/withdraw', '/api/auth/me'])
})

test('탈퇴는 비밀번호와 확인 체크 후 한 번 제출하고 입력을 화면에서 제거한다', async () => {
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    return json({ status: 'WITHDRAWN' })
  })
  vi.stubGlobal('fetch', fetcher)
  mount(<AccountPage />)
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('탈퇴 확인 비밀번호'), 'private-password')
  await user.click(screen.getByLabelText('탈퇴 후 계정을 복구할 수 없음을 확인했습니다.'))
  await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
  await screen.findByText('회원 탈퇴를 완료했습니다.')
  expect(screen.queryByLabelText('탈퇴 확인 비밀번호')).not.toBeInTheDocument()
  expect(fetcher.mock.calls.filter(call => call[0] === '/api/account/withdraw')).toHaveLength(1)
})
function mount(page: React.ReactNode, path = '/') {
  const auth = new AuthStore()
  const view = render(<MemoryRouter initialEntries={[path]}><AuthContext value={auth}>{page}</AuthContext></MemoryRouter>)
  return { auth, ...view }
}

test('프로필을 불러와 편집하고 저장하며 세션 재확인 시 입력 내용을 즉시 숨긴다', async () => {
  let saved = profile
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (init.method === 'PUT') saved = { ...saved, ...JSON.parse(String(init.body)) }
    return json(saved)
  })
  vi.stubGlobal('fetch', fetcher)
  const { auth } = mount(<AccountDetailsPage resource="profile" />)
  const user = userEvent.setup()
  const name = await screen.findByLabelText('표시 이름')
  expect(screen.getByLabelText('이메일')).toHaveAttribute('readonly')
  await user.clear(name)
  await user.type(name, '새 이름')
  await user.click(screen.getByRole('button', { name: '프로필 저장' }))
  await screen.findByText('저장했습니다.')
  expect(screen.getByLabelText('표시 이름')).toHaveValue('새 이름')
  act(() => { auth.stop() })
  expect(screen.queryByLabelText('표시 이름')).not.toBeInTheDocument()
  expect(screen.queryByDisplayValue('buyer@example.com')).not.toBeInTheDocument()
})

test('배송지 폼은 전체 필드를 제출하고 수정 폼으로 기존 값을 불러온다', async () => {
  let addresses: unknown[] = []
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (init.method === 'POST') {
      const address = { ...JSON.parse(String(init.body)), id, isDefault: true }
      addresses = [address]; return json(address, 201)
    }
    return json({ addresses })
  })
  vi.stubGlobal('fetch', fetcher)
  mount(<AccountDetailsPage resource="addresses" />)
  const user = userEvent.setup()
  await screen.findByLabelText('이름표')
  for (const [label, value] of Object.entries({ 이름표: '집', 수령인: '구매자', 전화번호: '01012345678', 우편번호: '12345', 주소: '서울시', '상세 주소': '101호' })) {
    await user.type(screen.getByLabelText(label, { exact: true }), value)
  }
  await user.click(screen.getByRole('button', { name: '배송지 추가' }))
  await screen.findByRole('article', { name: '집' })
  await user.click(screen.getByRole('button', { name: '수정' }))
  expect(screen.getByLabelText('상세 주소')).toHaveValue('101호')
  expect(screen.getByLabelText('기본 배송지')).toBeChecked()
})

test('복구 요청은 이메일만 보내고 같은 접수 안내를 보여 준다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({}, 401))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ status: 'PASSWORD_RESET_REQUESTED' }, 202))
  vi.stubGlobal('fetch', fetcher)
  mount(<PasswordPage mode="request" />)
  const user = userEvent.setup()
  await waitFor(() => { expect(screen.getByLabelText('이메일')).toBeEnabled() })
  await user.type(screen.getByLabelText('이메일'), 'absent@example.com')
  await user.click(screen.getByRole('button', { name: '복구 코드 요청' }))
  await screen.findByText(/복구 요청을 접수했습니다/)
  expect(fetcher.mock.calls[2][0]).toBe('/api/auth/password/request')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({ email: 'absent@example.com' })
})

test('비밀번호 길이를 검증하고 실패 응답의 요청 ID를 표시하며 민감한 입력을 지운다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({}, 401))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'INVALID_REQUEST', message: '잘못된 코드', requestId: '00000000-0000-4000-8000-000000000123' }, 400))
  vi.stubGlobal('fetch', fetcher)
  mount(<PasswordPage mode="reset" />)
  const user = userEvent.setup()
  await waitFor(() => { expect(screen.getByLabelText('복구 코드')).toBeEnabled() })
  await user.type(screen.getByLabelText('복구 코드'), 'test-token')
  await user.type(screen.getByLabelText('새 비밀번호'), 'short')
  await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('alert')).toHaveTextContent('12~128')
  await user.clear(screen.getByLabelText('새 비밀번호'))
  await user.type(screen.getByLabelText('새 비밀번호'), 'new-password-123')
  await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))
  await screen.findByText('00000000-0000-4000-8000-000000000123')
  expect(screen.getByLabelText('복구 코드')).toHaveValue('')
  expect(screen.getByLabelText('새 비밀번호')).toHaveValue('')
  expect(fetcher).toHaveBeenCalledTimes(3)
})
