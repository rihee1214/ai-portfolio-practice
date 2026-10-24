import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { AccountDetailsPage } from '../../src/pages/AccountPages'
import { PasswordPage } from '../../src/pages/PasswordPages'

const id = '00000000-0000-4000-8000-000000000001'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const profile = { email: 'buyer@example.com', displayName: '구매자', phone: null }
function mount(page: React.ReactNode) {
  const auth = new AuthStore()
  const view = render(<MemoryRouter><AuthContext value={auth}>{page}</AuthContext></MemoryRouter>)
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
