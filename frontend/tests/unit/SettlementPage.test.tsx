import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { SettlementPage } from '../../src/settlement/SettlementPage'
import { ConfirmedRefundPanel } from '../../src/support/ConfirmedRefundPanel'
import { SellerSettlementPage } from '../../src/settlement/SellerSettlementPage'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'

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

it('shows only the authenticated seller statement and clears private money on invalid responses and session loss', async () => {
  const store=new AuthStore()
  const record={weekStart:batch.weekStart,state:'PAID_MOCK',net:'-9518',opening:'0',payout:'0',closing:'9518',held:[],lines:[row]}
  let records:unknown[]=[record]
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path==='/api/auth/me')return json({id,roles:['SELLER']})
    expect(path).toBe('/api/seller/settlements')
    return json(records)
  }))
  render(<AuthContext.Provider value={store}><SellerSettlementPage/></AuthContext.Provider>)
  expect(screen.getByText('판매자 로그인이 필요합니다.')).toBeVisible()
  await act(()=>store.refresh())
  expect(await screen.findByText('-9,518원')).toBeVisible()
  expect(screen.getByText('미회수 조정 잔액 (다음 주 이월)').nextElementSibling).toHaveTextContent('9,518원')
  records=[{...record,payout:'invalid'}]
  fireEvent.click(screen.getByRole('button',{name:'다시 조회'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('정산 내역')
  expect(screen.queryByText('-9,518원')).toBeNull()
  records=[record]
  fireEvent.click(screen.getByRole('button',{name:'다시 조회'}))
  expect(await screen.findByText('-9,518원')).toBeVisible()
  act(()=>store.stop())
  expect(screen.queryByText('-9,518원')).toBeNull()
})

it('executes only the selected approved quantity and retries an uncertain response with identical command bytes', async () => {
  const approved = {item:id, ordinal:1, collection:'collection', inspection:'inspection', responsibility:'SELLER',
    approval:storeId, amount:'10019', execution:null, attempt:null, result:null, shippingState:null,shippingAttempt:null}
  const commands:string[]=[]
  let state='APPROVED'
  vi.stubGlobal('fetch',vi.fn(async(path:string, init:RequestInit)=>{
    if(path.endsWith('/csrf'))return json({token:'csrf'})
    if(init.method==='POST'){
      expect(path).toBe('/api/admin/support/cases/case/confirmed-refunds/EXECUTE')
      commands.push(String(init.body))
      if(commands.length===1)return json({},503)
      state='UNKNOWN';return json([])
    }
    return json([state==='APPROVED'?approved:{...approved,execution:'execution',attempt:'attempt',result:state}])
  }))
  render(<ConfirmedRefundPanel id="case" version={7} onAccessLost={vi.fn()}/>)
  fireEvent.click(screen.getByRole('button',{name:'회수·검수 기록 열기'}))
  fireEvent.click(await screen.findByRole('button',{name:id+' · 수량 번호 1'}))
  const execute=screen.getByRole('button',{name:/^환불 실행$/})
  expect(execute).toBeDisabled()
  expect(commands).toEqual([])
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(execute)
  fireEvent.click(await screen.findByRole('button',{name:'같은 요청으로 결과 확인'}))
  expect(await screen.findByRole('status')).toHaveTextContent('UNKNOWN')
  expect(commands).toHaveLength(2)
  expect(commands[1]).toBe(commands[0])
  expect(JSON.parse(commands[0])).toEqual({key:expect.any(String),caseVersion:7,item:id,ordinal:1,previous:storeId})
  expect(screen.queryByRole('button',{name:/재시도/})).toBeNull()
})

it('shows the server week and posts only the batch identity and version through mock payout', async () => {
  const calls: { path: string; command: Record<string, unknown> }[] = []
  let current = { ...batch, items: [row], balances: [{storeId,net:"22037",opening:"31555",closing:"9518",payout:"0"}], payout:"0" }
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
  expect(screen.getByText(/전주 미회수 조정 잔액 31,555원 · 이번 지급 0원 · 미회수 조정 잔액 \(다음 주 이월\) 9,518원/)).toBeVisible()
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
  for (const label of ['수수료 차감 전 금액', '수수료', '주간 순액', '모의 지급액']) {
    expect(within(rebuilt).getByText(label,{selector:'dt'}).nextElementSibling).toHaveTextContent(/^0원$/)
  }
})
