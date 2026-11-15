import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { readRuntimePolicy } from '../../src/api/runtimePolicy'
import { RuntimePolicyProvider, RuntimeFeature } from '../../src/runtime/RuntimePolicy'
import { useRuntimePolicy } from '../../src/runtime/RuntimePolicyContext'
import { apiErrorView } from '../../src/api/apiError'
import { RuntimePolicyContext } from '../../src/runtime/RuntimePolicyContext'
import { PaymentPage } from '../../src/payment/PaymentPage'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { Route, Routes } from 'react-router'
import App from '../../src/App'
import { PaymentStore } from '../../src/payment/PaymentStore'

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function Probe() { const policy = useRuntimePolicy(); return <p data-testid="mode">{policy.mode}:{String(policy.restricted)}</p> }
test('실행 정책은 정확한 모드와 제한 조합만 수용하고 자격·캐시를 동일 origin으로 한정한다', async () => {
  const fetcher = vi.fn(async () => json({ mode: 'local-full', restricted: false }))
  expect(await readRuntimePolicy(undefined, fetcher)).toEqual({ mode: 'local-full', restricted: false })
  expect(fetcher).toHaveBeenCalledWith('/api/runtime-policy', expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }))
  expect(await readRuntimePolicy(undefined, async () => json({ mode: 'public-demo', restricted: true }))).toEqual({ mode: 'public-demo', restricted: true })
  for (const data of [{ mode: 'production', restricted: false }, { mode: 'public-demo', restricted: false }, { mode: 'local-full', restricted: true }, {}, null]) {
    expect(await readRuntimePolicy(undefined, async () => json(data))).toBeNull()
  }
})
test('정책 HTTP·부분응답·네트워크 실패는 로컬 전체 기능으로 해석하지 않는다', async () => {
  for (const fetcher of [async () => json({}, 503), async () => new Response('{', { headers: { 'Content-Type': 'application/json' } }), async () => { throw new Error('offline') }]) {
    expect(await readRuntimePolicy(undefined, fetcher)).toBeNull()
  }
})
test('공개 데모는 발급 화면을 마운트하지 않고 비활성 제출과 개별 로그인 안내를 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ mode: 'public-demo', restricted: true })))
  const child = vi.fn(() => <input aria-label="발급 비밀" />)
  const Child = child
  render(<MemoryRouter><RuntimePolicyProvider><Probe /><RuntimeFeature title="회원가입"><Child /></RuntimeFeature></RuntimePolicyProvider></MemoryRouter>)
  await waitFor(() => expect(screen.getByTestId('mode')).toHaveTextContent('public-demo:true'))
  expect(screen.getByRole('button', { name: '회원가입' })).toBeDisabled()
  expect(screen.getByText(/초대받은 검토자의 기존 개별 계정/)).toBeVisible()
  expect(child).not.toHaveBeenCalled()
})
test('정책을 읽기 전과 실패 후에도 발급 기능은 닫힌 채로 유지된다', async () => {
  let release!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { release = resolve })))
  render(<MemoryRouter><RuntimePolicyProvider><RuntimeFeature title="전화 인증"><input aria-label="전화번호" /></RuntimeFeature></RuntimePolicyProvider></MemoryRouter>)
  expect(screen.getByRole('button', { name: '전화 인증' })).toBeDisabled()
  expect(screen.queryByLabelText('전화번호')).toBeNull()
  release(json({}, 502))
  expect(await screen.findByText(/실행 정책을 확인하지 못해/)).toBeVisible()
  expect(screen.getByRole('button', { name: '전화 인증' })).toBeDisabled()
})
test('확인된 local-full만 기존 발급 화면을 활성화한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ mode: 'local-full', restricted: false })))
  render(<MemoryRouter><RuntimePolicyProvider><RuntimeFeature title="회원가입"><input aria-label="이메일" /></RuntimeFeature></RuntimePolicyProvider></MemoryRouter>)
  expect(await screen.findByLabelText('이메일')).toBeEnabled()
})
test('공개 데모 서버 503은 일반 내부 오류와 구별하고 서버 입력을 그대로 표시하지 않는다', () => {
  expect(apiErrorView({ kind: 'http', status: 503, data: { code: 'DEMO_FEATURE_UNAVAILABLE', message: '<secret>' } })?.message).toMatch(/공개 데모에서 지원하지 않는 기능/)
  expect(apiErrorView({ kind: 'http', status: 502, data: { code: 'DEMO_FEATURE_UNAVAILABLE', message: '<secret>' } })?.message).not.toContain('공개 데모')
})
test('실행 정책 조회 실패에도 기존 개별 로그인 입력은 사용할 수 있다', async () => {
  window.history.replaceState(null, '', '/login')
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/runtime-policy' ? json({}, 502) : json({}, 401)))
  render(<App />)
  expect(await screen.findByLabelText('이메일', { exact: true })).toBeEnabled()
  expect(screen.getByLabelText('비밀번호', { exact: true })).toBeEnabled()
  expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
})
test.each(['DEMO', 'SANDBOX'])('결제 저장소는 %s 제한 503을 안내하고 자동 재승인하지 않는다', async mode => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ id: '00000000-0000-4000-8000-000000000001', roles: ['BUYER'] })))
  const auth = new AuthStore(); await auth.refresh()
  let writes = 0
  const store = new PaymentStore(auth, 'one', async (path, init) => {
    if (String(path).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    if (init?.method === 'POST') { writes++; return json({ code: 'DEMO_FEATURE_UNAVAILABLE', message: 'unsupported' }, 503) }
    return json({ orderNumber: 'one', orderState: 'PENDING_PAYMENT', paymentState: 'NONE', compensation: 'NONE', reviewRequired: false, amount: '1000', mode })
  })
  await store.poll()
  if (mode === 'DEMO') await store.approve(); else await store.sandbox()
  expect(store.getSnapshot().error).toContain('공개 데모에서 지원하지 않는 기능')
  expect(store.getSnapshot().busy).toBe(false)
  expect(store.getSnapshot().view?.orderState).toBe('PENDING_PAYMENT')
  expect(writes).toBe(1)
  store.dispose()
})
test.each(['SANDBOX', 'DEMO'])('공개 데모 결제 화면은 %s의 명령 허용을 서버 정책과 일치시킨다', async mode => {
  const fetcher = vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id: '00000000-0000-4000-8000-000000000001', roles: ['BUYER'] })
    : json({ orderNumber: 'one', orderState: 'PENDING_PAYMENT', paymentState: 'NONE', compensation: 'NONE', reviewRequired: false, amount: '1000', mode }))
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  render(<MemoryRouter initialEntries={['/payments/one']}><RuntimePolicyContext value={{ mode: 'public-demo', restricted: true }}><AuthContext value={auth}><Routes><Route path="/payments/:number" element={<PaymentPage />} /></Routes></AuthContext></RuntimePolicyContext></MemoryRouter>)
  const button = await screen.findByRole('button', { name: mode === 'DEMO' ? '모의 결제 승인' : '테스트 결제창 열기' })
  if (mode === 'DEMO') expect(button).toBeEnabled()
  else { expect(button).toBeDisabled(); expect(screen.getByText(/실제 Toss 및 테스트 결제/)).toBeVisible() }
  expect(fetcher.mock.calls.some(([path]) => path.endsWith('/sandbox') || path.endsWith('/confirm') || path.endsWith('/approve'))).toBe(false)
})
test.each([['/signup', '회원가입'], ['/verify-email', '이메일 확인'], ['/password/request', '복구 코드 요청'], ['/password/reset', '비밀번호 재설정'], ['/checkout/guest', '방문자 구매 인증'], ['/guest/recovery', '비회원 조회 비밀번호 복구'], ['/guest/security', '비회원 조회 자격 관리'], ['/guest/orders/login', '비회원 주문 조회 인증']])('공개 모드 직접 경로 %s는 발급 form을 열지 않는다', async (path, title) => {
  window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/runtime-policy' ? json({ mode: 'public-demo', restricted: true }) : json({}, 401)))
  render(<App />)
  expect(await screen.findByRole('button', { name: title })).toBeDisabled()
  expect(screen.queryByRole('textbox')).toBeNull()
  await waitFor(() => expect(screen.getByText(/초대받은 검토자의 기존 개별 계정/)).toBeVisible())
})
