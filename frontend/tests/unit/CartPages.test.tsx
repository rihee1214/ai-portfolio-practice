import {policyFixture} from './returnPolicyFixture'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import { CartPage } from '../../src/pages/CartPages'
import { CartContext } from '../../src/cart/CartContext'
import { CartStore } from '../../src/cart/CartStore'
import { GuestCart } from '../../src/cart/GuestCart'
import { AuthStore } from '../../src/auth/AuthStore'
const sku = '00000000-0000-4000-8000-000000000001'
beforeEach(() => localStorage.clear())
test('장바구니 화면은 최신 금액·재고 부족·비판매를 안내하고 수량과 삭제를 반영한다', async () => {
  const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/auth/me') return new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    const items = JSON.parse(String(init?.body)).items
    return json({ version: 0, items: items.map((i: { skuId: string; quantity: number }) => ({ ...i, detail: i.skuId === sku ? { product: { slug: 'cart-product', title: '실제 상품', description: '', categoryId: sku, categoryName: '생활', storeSlug: 'cart-store', storeName: '매대', minPrice: 1700, soldOut: false,returnPolicy:policyFixture() }, offers: [{ id: sku, options: [], price: 1700, available: 1 }] } : null })) })
  }))
  const guest = new GuestCart(localStorage); guest.set(sku, 2); guest.set('00000000-0000-4000-8000-000000000002', 1)
  const auth = new AuthStore(); await auth.refresh(); const store = new CartStore(auth, guest, work => work()); await store.refresh()
  render(<MemoryRouter><CartContext value={store}><CartPage /></CartContext></MemoryRouter>)
  expect(screen.getByText('재고 부족: 현재 1개')).toBeVisible(); expect(screen.getByText('판매할 수 없는 상품')).toBeVisible()
  expect(screen.getByText('상품 금액: 3,400원')).toBeVisible()
  const user = userEvent.setup(); const row = screen.getByRole('article', { name: '실제 상품' }); const input = row.querySelector('input')!
  await user.clear(input); await user.type(input, '3'); await user.click(screen.getByRole('button', { name: '실제 상품 수량 변경' }))
  expect(await screen.findByText('상품 금액: 5,100원')).toBeVisible(); expect(guest.read().find(i => i.skuId === sku)?.quantity).toBe(3)
  await user.click(screen.getByRole('button', { name: '실제 상품 삭제' })); await user.click(screen.getByRole('button', { name: '판매할 수 없는 상품 삭제' }))
  expect(await screen.findByText('장바구니가 비어 있습니다.')).toBeVisible()
})
