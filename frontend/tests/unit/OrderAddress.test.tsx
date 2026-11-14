import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { AuthStore } from '../../src/auth/AuthStore'
import { OrderCreationPanel } from '../../src/order/OrderCreationPanel'
import type { CartStore } from '../../src/cart/CartStore'
import type { Quote } from '../../src/promotion/QuoteStore'

const id = '00000000-0000-4000-8000-000000000001'
const address = { id, label: '집', recipient: '저장 수령인', phone: '01087654321', postalCode: '12345', addressLine1: '저장된 도로 주소', addressLine2: '203호', isDefault: true }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const quote = { fingerprint: 'a'.repeat(64), stores: [{ storeId: id, coupon: null, lines: [{ skuId: id, quantity: 1 }] }] } as unknown as Quote
async function mount(addressResponse: () => Promise<Response> = async () => json({ addresses: [address] })) {
  const orders: { contact: Record<string, string> }[] = []
  const fetcher = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
    if (String(path) === '/api/auth/me') return json({ id, roles: ['BUYER'] })
    if (String(path) === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (String(path) === '/api/account/addresses') return addressResponse()
    if (String(path) === '/api/orders') {
      orders.push(JSON.parse(String(init?.body)))
      return json({ id, orderNumber: 'O' + 'a'.repeat(32), total: '13000', state: 'PENDING_PAYMENT', stores: 1, expiresAt: '2030-01-01T00:00:00Z' })
    }
    return json({}, 401)
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore()
  await auth.refresh()
  const cart = { capturePurchase: () => ({ owner: id, items: [{ skuId: id, quantity: 1, revision: 0 }] }), reconcilePurchase: async () => {} } as unknown as CartStore
  render(<OrderCreationPanel auth={auth} cart={cart} quote={quote} refresh={async () => {}} />)
  return { auth, orders, fetcher }
}

it('saved delivery selection preserves buyer fields and requires new confirmation before the exact contact is submitted', async () => {
  const { orders } = await mount()
  for (const [label, value] of [['주문 구매자 이름', '원 구매자'], ['주문 구매자 전화번호', '01012345678']]) fireEvent.change(screen.getByLabelText(label), { target: { value } })
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: '저장 배송지 불러오기' }))
  await screen.findByRole('option', { name: '집 · 기본 배송지' })
  fireEvent.change(screen.getByLabelText('저장 배송지 선택'), { target: { value: id } })
  expect(screen.getByLabelText('수령인 이름')).toHaveValue(address.recipient)
  expect(screen.getByLabelText('주문 구매자 이름')).toHaveValue('원 구매자')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled()
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: '주문 생성' }))
  await waitFor(() => expect(orders).toHaveLength(1))
  expect(orders[0].contact).toEqual({ buyerName: '원 구매자', buyerPhone: '01012345678', recipientName: address.recipient, recipientPhone: address.phone, postalCode: address.postalCode, addressLine1: address.addressLine1, addressLine2: address.addressLine2 })
})

it('saved address lookup failure can retry and an empty list leaves manual delivery entry available', async () => {
  let fail = true
  await mount(async () => fail ? json({}, 503) : json({ addresses: [] }))
  fireEvent.click(screen.getByRole('button', { name: '저장 배송지 불러오기' }))
  await screen.findByRole('alert')
  fail = false
  fireEvent.click(screen.getByRole('button', { name: '배송지 다시 조회' }))
  await screen.findByText('저장된 배송지가 없습니다. 직접 입력해 주세요.')
  expect(screen.getByLabelText('수령인 이름')).toBeEnabled()
  expect(screen.getByLabelText('기본 주소')).toBeEnabled()
})

it('a lost order response locks saved-address selection to the original immutable request', async () => {
  const { fetcher } = await mount()
  for (const [label, value] of [['주문 구매자 이름', '구매자'], ['주문 구매자 전화번호', '01012345678']]) fireEvent.change(screen.getByLabelText(label), { target: { value } })
  fireEvent.click(screen.getByRole('button', { name: '저장 배송지 불러오기' }))
  await screen.findByRole('option', { name: '집 · 기본 배송지' })
  fireEvent.change(screen.getByLabelText('저장 배송지 선택'), { target: { value: id } })
  fetcher.mockImplementation(async path => {
    if (String(path) === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    throw new TypeError('lost order response')
  })
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: '주문 생성' }))
  await screen.findByRole('button', { name: '같은 요청으로 주문 결과 확인' })
  expect(screen.getByRole('button', { name: '저장 배송지 불러오기' })).toBeDisabled()
  expect(screen.getByLabelText('수령인 이름')).toBeDisabled()
  expect(screen.queryByLabelText('저장 배송지 선택')).not.toBeInTheDocument()
})

it('auth transitions clear copied delivery data and discard a delayed address response', async () => {
  let delayed = false, release: ((r: Response) => void) | undefined
  const { auth } = await mount(() => delayed ? new Promise<Response>(r => { release = r }) : Promise.resolve(json({ addresses: [address] })))
  fireEvent.click(screen.getByRole('button', { name: '저장 배송지 불러오기' }))
  await screen.findByRole('option', { name: '집 · 기본 배송지' })
  fireEvent.change(screen.getByLabelText('저장 배송지 선택'), { target: { value: id } })
  expect(screen.getByLabelText('기본 주소')).toHaveValue(address.addressLine1)
  delayed = true
  fireEvent.click(screen.getByRole('button', { name: '저장 배송지 불러오기' }))
  await waitFor(() => expect(release).toBeDefined())
  act(() => auth.stop())
  expect(screen.getByLabelText('기본 주소')).toHaveValue('')
  await act(async () => release!(json({ addresses: [address] })))
  expect(screen.queryByText('집 · 기본 배송지')).not.toBeInTheDocument()
  expect(screen.queryByDisplayValue(address.addressLine1)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '저장 배송지 불러오기' })).not.toBeInTheDocument()
})
