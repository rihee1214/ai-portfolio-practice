import { describe, it, expect, vi } from 'vitest'
import { promotionValid, koreanTime } from '../../src/promotion/PromotionStore'
const item = { id: '00000000-0000-0000-0000-000000000001', storeId: '00000000-0000-0000-0000-000000000002', title: '행사', scope: 'SHIPPING', productIds: [], startsAt: '2026-01-01T00:00:00Z', endsAt: '2026-01-02T00:00:00Z', kind: 'FIXED', value: 1000, cap: null, state: 'DRAFT', version: 0, storeSlug: 'store-one', storeName: '매대', targets: [] }
describe('PromotionStore', () => {
  it('checks server promotion fields and unsafe values', () => {
    expect(promotionValid(item)).toBe(true)
    for (const patch of [{ value: -1 }, { version: -1 }, { state: 'UNKNOWN' }, { endsAt: item.startsAt }, { scope: 'OTHER' }, { productIds: ['invalid'] }, { value: Number.MAX_SAFE_INTEGER + 1 }]) expect(promotionValid({ ...item, ...patch })).toBe(false)
  })
  it('shows explicit Korea time independently of browser timezone', () => {
    expect(koreanTime(item.startsAt)).toContain('09:00')
    expect(koreanTime(item.startsAt)).toContain('2026')
  })
})

import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AuthStore } from '../../src/auth/AuthStore'
import { AuthContext } from '../../src/auth/AuthContext'
import { PromotionPage, PublicPromotionPage } from '../../src/pages/PromotionPages'
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
it('keeps select labels stable without option text', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id: item.id, roles: ['SELLER'] }) : json({ items: [], page: 0, hasMore: false })))
  const auth = new AuthStore(); await auth.refresh()
  render(<MemoryRouter><AuthContext value={auth}><PromotionPage /></AuthContext></MemoryRouter>)
  expect(await screen.findByLabelText('행사 대상', { exact: true })).toBeEnabled()
})
it('exposes public pagination and detail links', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ items: [item], page: 0, hasMore: true })))
  render(<MemoryRouter><PublicPromotionPage /></MemoryRouter>)
  expect(await screen.findByRole('link', { name: '행사 상세' })).toHaveAttribute('href', '/events/' + item.id)
  expect(screen.getByRole('button', { name: '다음 행사' })).toBeEnabled()
})
