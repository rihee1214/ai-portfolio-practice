import {policyFixture} from './returnPolicyFixture'
import { test, expect, vi } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { InventoryPanel } from '../../src/pages/InventoryPanel'
import { ProductPage } from '../../src/pages/CatalogPages'
const id = '00000000-0000-4000-8000-000000000003'
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const row = { id, code: 'sku-test', onHand: 3, reserved: 1, available: 2, version: 7 }
test('재고 응답 유실 후 같은 의도는 같은 키로 재전송하고 변경된 의도는 새 키를 쓴다', async () => {
  const bodies: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'PUT') { expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('csrf'); bodies.push(JSON.parse(String(init.body))); throw new TypeError('response lost') }
    return json({ items: path.includes('/history') ? [] : [row], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><InventoryPanel product={id} /></AuthContext>)
  const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: /sku-test · 보유/ }))
  await user.clear(screen.getByLabelText('보유 수량')); await user.type(screen.getByLabelText('보유 수량'), '8')
  await user.type(screen.getByLabelText('재고 조정 사유'), '입고')
  await user.click(screen.getByRole('button', { name: '재고 조정 저장' })); await screen.findByRole('alert')
  expect(bodies).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: '재고 조정 저장' })); await waitFor(() => expect(bodies).toHaveLength(2))
  expect(bodies[1]).toEqual(bodies[0]); expect(bodies[0].version).toBe(7)
  await user.clear(screen.getByLabelText('보유 수량')); await user.type(screen.getByLabelText('보유 수량'), '9')
  await user.click(screen.getByRole('button', { name: '재고 조정 저장' })); await waitFor(() => expect(bodies).toHaveLength(3))
  expect(bodies[2].requestId).not.toBe(bodies[0].requestId)
})
test.each([401, 403])('재고 저장 %s면 기존 재고 폼과 회원 상태를 비운다', async status => {
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'PUT') return json({ code: 'FORBIDDEN', requestId: id }, status)
    return json({ items: path.includes('/history') ? [] : [row], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><InventoryPanel product={id} /></AuthContext>)
  const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: /sku-test · 보유/ }))
  await user.clear(screen.getByLabelText('보유 수량')); await user.type(screen.getByLabelText('보유 수량'), '8'); await user.type(screen.getByLabelText('재고 조정 사유'), '입고')
  await user.click(screen.getByRole('button', { name: '재고 조정 저장' }))
  await waitFor(() => expect(auth.getSnapshot().member).toBeNull())
  expect(screen.queryByLabelText('보유 수량')).not.toBeInTheDocument()
})

test('재고 저장 성공 후 갱신된 입력 폼이 하나만 남는다', async () => {
  let current = { ...row }
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'PUT') { current = { ...row, onHand: 8, available: 7, version: 8 }; return json(current) }
    return json({ items: path.includes('/history') ? [] : [current], page: 0, hasMore: false })
  }))
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><InventoryPanel product={id} /></AuthContext>)
  const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: /sku-test · 보유/ }))
  await user.clear(screen.getByLabelText('보유 수량')); await user.type(screen.getByLabelText('보유 수량'), '8'); await user.type(screen.getByLabelText('재고 조정 사유'), '입고')
  await user.click(screen.getByRole('button', { name: '재고 조정 저장' }))
  await screen.findByRole('button', { name: /sku-test · 보유 8/ })
  expect(screen.getAllByLabelText('보유 수량')).toHaveLength(1)
  expect(screen.getByLabelText('보유 수량')).toHaveValue(8)
})

test('다른 상품을 선택한 뒤 도착한 이전 재고 응답을 표시하지 않는다', async () => {
  const second = '00000000-0000-4000-8000-000000000004'
  let release!: (response: Response) => void
  const old = new Promise<Response>(resolve => { release = resolve })
  const products = [id, second].map((key, index) => ({ id: key, slug: 'stock-' + index, title: '상품' + index, description: '', categoryId: id, state: 'DRAFT', version: 0, hiddenByAdmin: false,returnPolicy:policyFixture(key) }))
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/seller/products?page=0') return json({ items: products, page: 0, hasMore: false })
    if (path.includes(id + '/inventory')) return old
    if (path.includes(second + '/inventory')) return json({ items: [{ ...row, id: second, code: 'sku-new' }], page: 0, hasMore: false })
    return json({ items: [], page: 0, hasMore: false })
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  render(<AuthContext value={auth}><ProductPage /></AuthContext>)
  const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '상품0 · 초안' }))
  await waitFor(() => expect(fetcher.mock.calls.some(([path]) => path.includes(id + '/inventory'))).toBe(true))
  await user.click(screen.getByRole('button', { name: '상품1 · 초안' }))
  await screen.findByRole('button', { name: /sku-new · 보유/ })
  await act(async () => release(json({ items: [row], page: 0, hasMore: false })))
  await waitFor(() => expect(screen.queryByRole('button', { name: /sku-test · 보유/ })).not.toBeInTheDocument())
})
