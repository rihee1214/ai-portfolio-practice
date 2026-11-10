import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { SettlementPage } from '../../src/settlement/SettlementPage'

const base = '/api/admin/settlements'
const id = '2d77d12a-6952-4fb6-b96f-0578ec059bdb'
const storeId = 'ab44648b-c945-4122-b994-b4ef7691a373'
const policy = { version: 'SETTLEMENT_V1', rateBasisPoints: 500, zone: 'Asia/Seoul',
  currentWeekStart: '2026-11-09', effectiveAt: '2026-10-20T00:00:00Z' }
const batch = { id, weekStart: '2026-11-02', periodStart: '2026-11-01T15:00:00Z',
  periodEnd: '2026-11-08T15:00:00Z', state: 'DRAFT', version: 0, closed: true,
  gross: '23038', fee: '1001', net: '22037', items: [], held: [] }
const row = { id: storeId, storeId, orderId: storeId, sellerOrderId: storeId, itemId: id,
  ordinal: 0, causeId: id, kind: 'PRODUCT', amount: '10019', fee: '0', net: '10019',
  occurredAt: policy.effectiveAt, eligibleAt: policy.effectiveAt, eligibilityHolds: [] }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data),
  { status, headers: { 'Content-Type': 'application/json' } })

it('shows the server week and posts only the batch identity and version through mock payout', async () => {
  const calls: { path: string; command: Record<string, unknown> }[] = []
  let current = { ...batch, items: [row] }
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/csrf')) return json({ token: 'csrf' })
    if (path === base + '/policy') return json(policy)
    if (init.method !== 'POST') return json(path === base+'/batches' ? [] : current)
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('csrf')
    calls.push({ path, command: JSON.parse(String(init.body)) })
    if (path.endsWith('/review')) current = { ...current, state: 'REVIEWED', version: 1 }
    if (path.endsWith('/simulate-pay')) current = { ...current, state: 'PAID_MOCK', version: 2 }
    return json(current)
  }))
  render(<SettlementPage />)
  const week = await screen.findByLabelText('정산 주 시작일')
  expect(week).toHaveValue(policy.currentWeekStart)
  fireEvent.change(week, { target: { value: batch.weekStart } })
  fireEvent.click(screen.getByRole('button', { name: '초안 생성' }))
  expect(await screen.findByText('10,019원')).toBeVisible()
  expect(screen.getAllByText('22,037원').length).toBeGreaterThan(0)
  fireEvent.click(screen.getByRole('button', { name: '검토 확정' }))
  fireEvent.click(await screen.findByRole('button', { name: '모의 지급' }))
  await screen.findByRole('heading', { name: /모의 지급 완료/ })
  expect(calls.map(c => c.path)).toEqual([base+'/batches', base+'/batches/'+id+'/review', base+'/batches/'+id+'/simulate-pay'])
  expect(calls.map(c => c.command)).toEqual([
    { key: expect.any(String), weekStart: batch.weekStart },
    { key: expect.any(String), expectedVersion: 0 }, { key: expect.any(String), expectedVersion: 1 },
  ])
  expect(screen.queryByRole('button', { name: '모의 지급' })).toBeNull()
})

it('clears the ledger on permission loss and prevents review of an open week', async () => {
  const current = { ...batch, weekStart: policy.currentWeekStart, closed: false, items: [row] }
  let allowed = true
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path.endsWith('/policy')) return json(policy)
    if (path.endsWith('/batches')) return json([current])
    return allowed ? json(current) : json({}, 403)
  }))
  render(<SettlementPage />)
  fireEvent.click(await screen.findByRole('button', { name: /2026-11-09.*상세/ }))
  await screen.findByText('10,019원')
  expect(screen.getByRole('button', { name: '검토 확정' })).toBeDisabled()
  allowed = false
  fireEvent.click(screen.getByRole('button', { name: '상세 다시 조회' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('권한')
  expect(screen.queryByText('10,019원')).toBeNull()
  expect(screen.queryByRole('button', { name: /2026-11-09.*상세/ })).toBeNull()
})

it('reuses the creation key after response loss and separates signed amounts by store', async () => {
  const secondStore = '75344c35-571d-420f-b30b-d04070f2d71d'
  const keys: string[] = []
  const delayed = { ...row, occurredAt: '2026-11-08T14:59:00Z', eligibleAt: '2026-11-08T15:01:00Z',
    eligibilityHolds: [{ id: storeId, acquiredAt: '2026-11-08T14:59:30Z', releasedAt: '2026-11-08T15:01:00Z' }] }
  const reservationRelease = '2026-11-08T15:07:00Z'
  const entries = [delayed, { ...row, id: id, kind: 'COMMISSION', amount: '0', fee: '501', net: '-501' },
    { ...row, storeId: secondStore, id: secondStore, amount: '2000', net: '2000', eligibleAt: reservationRelease,
      eligibilityReservations: [{ id, kind: 'CLAIM_FEE', acquiredAt: delayed.occurredAt, releasedAt: reservationRelease }] }]
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/policy')) return json(policy)
    if (path.endsWith('/csrf')) return json({ token: 'csrf' })
    if (init.method !== 'POST') return json([])
    keys.push(JSON.parse(String(init.body)).key)
    if (keys.length === 1) throw new Error('lost after commit')
    return json({ ...batch, weekStart: policy.currentWeekStart, gross: '12019', fee: '501', net: '11518', items: entries })
  }))
  render(<SettlementPage />)
  fireEvent.click(await screen.findByRole('button', { name: '초안 생성' }))
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: '초안 생성' }))
  expect(await screen.findByRole('region', { name: '매대 ' + storeId })).toHaveTextContent('9,518원')
  expect(screen.getByRole('region', { name: '매대 ' + secondStore })).toHaveTextContent('2,000원')
  expect(screen.getByText(/보류 해제 후 지급 가능/).querySelector('time')).toHaveAttribute('datetime', delayed.eligibleAt)
  expect(screen.getByText(/예약 해제 후 지급 가능/).querySelector('time')).toHaveAttribute('datetime', reservationRelease)
  expect(keys).toEqual([expect.any(String), keys[0]])
})

it('reopens the selected reviewed week after a stale payout instead of paying the changed amount', async () => {
  let reopened = false
  const paidKeys: string[] = []
  const reviewed = { ...batch, state: 'REVIEWED', items: [row] }
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/policy')) return json(policy)
    if (path.endsWith('/csrf')) return json({ token: 'csrf' })
    if (init.method !== 'POST') return json(path.endsWith('/batches') ? [reviewed] : reviewed)
    const body = JSON.parse(String(init.body))
    if (path.endsWith('/simulate-pay')) { paidKeys.push(body.key); return json({}, 409) }
    expect(path).toBe(base + '/batches')
    expect(body.weekStart).toBe(reviewed.weekStart)
    reopened = true
    return json({ ...batch, version: 2, gross: '40000', fee: '1001', net: '38999', items: [row] })
  }))
  render(<SettlementPage />)
  fireEvent.click(await screen.findByRole('button', { name: /2026-11-02.*상세/ }))
  fireEvent.click(await screen.findByRole('button', { name: '모의 지급' }))
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: '상세 다시 조회' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택한 주 초안 다시 작성' }))
  await screen.findByRole('button', { name: '검토 확정' })
  expect(screen.getAllByText('38,999원').length).toBeGreaterThan(0)
  expect(screen.queryByRole('button', { name: '모의 지급' })).toBeNull()
  expect(reopened).toBe(true)
  expect(paidKeys).toHaveLength(1)
})

it('identifies the unpaid week to rebuild before another week can pay', async () => {
  const oldWeek = '2026-10-26'
  const oldDraft = { ...batch, id: storeId, weekStart: oldWeek, items: [row] }
  const reviewed = { ...batch, state: 'REVIEWED', items: [row] }
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/policy')) return json(policy)
    if (path.endsWith('/csrf')) return json({ token: 'csrf' })
    if (init.method !== 'POST') return json(path.endsWith('/batches') ? [reviewed, oldDraft] : path.endsWith(storeId) ? oldDraft : reviewed)
    if (path.endsWith('/simulate-pay')) return json({ code: 'SETTLEMENT_UNPAID_BATCH_CONFLICT', conflictingWeeks: [oldWeek] }, 409)
    expect(JSON.parse(String(init.body)).weekStart).toBe(oldWeek)
    return json({ ...oldDraft, items: [], gross: '0', fee: '0', net: '0' })
  }))
  render(<SettlementPage />)
  fireEvent.click(await screen.findByRole('button', { name: reviewed.weekStart + ' 정산 상세' }))
  fireEvent.click(await screen.findByRole('button', { name: '모의 지급' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(oldWeek)
  fireEvent.click(screen.getByRole('button', { name: oldWeek + ' 정산 상세' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택한 주 초안 다시 작성' }))
  const rebuilt = await screen.findByRole('region', { name: '배치 상세' })
  expect(within(rebuilt).getAllByText('0원')).toHaveLength(3)
})
