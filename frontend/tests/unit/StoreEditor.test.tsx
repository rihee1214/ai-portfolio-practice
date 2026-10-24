import { expect, test, vi } from 'vitest'
import { waitFor, render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Link, Routes, Route } from 'react-router'
import { PublicStorePage } from '../../src/pages/StorePages'
import { AuthStore } from '../../src/auth/AuthStore'
import { StoreEditorStore } from '../../src/seller/StoreEditorStore'

const id = '00000000-0000-4000-8000-000000000003'
const fields = { slug: 'test-store', name: '가상 매대', introduction: '소개', address: '가상 주소', notice: '', imageKey: '', imageAlt: '', visible: false }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
test('공개 매대 경로 변경 후 이전의 늦은 성공 응답은 404 화면을 덮지 않는다', async () => {
  let release!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.endsWith('/old-store') ? new Promise<Response>(resolve => { release = resolve })
    : json({ code: 'NOT_FOUND', message: 'not found', requestId: id }, 404)))
  render(<MemoryRouter initialEntries={['/stores/old-store']}><Link to="/stores/new-store">다른 매대</Link><Routes><Route path="/stores/:slug" element={<PublicStorePage />} /></Routes></MemoryRouter>)
  await waitFor(() => expect(release).toBeDefined())
  await userEvent.setup().click(screen.getByRole('link', { name: '다른 매대' }))
  await screen.findByText('요청한 대상을 찾을 수 없습니다.')
  await act(async () => release(json({ ...fields, slug: 'old-store', name: '이전 매대 비공개 내용' })))
  expect(screen.queryByText('이전 매대 비공개 내용')).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toBeVisible()
})
test('매대 생성은 CSRF와 버전0, 수정은 읽은 버전을 보내고 충돌을 자동 재시도하지 않는다', async () => {
  const fetcher = vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['BUYER', 'SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'POST') return json({ ...fields, id, version: 0 }, 201)
    if (init.method === 'PUT') return json({ code: 'CONFLICT', message: 'conflict', requestId: id }, 409)
    return json({ store: null })
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthStore(); await auth.refresh()
  const store = new StoreEditorStore(auth); const disconnect = store.connect()
  await waitFor(() => expect(store.getSnapshot().store).toBeNull())
  await store.save(fields)
  expect(store.getSnapshot().store?.id).toBe(id)
  const create = fetcher.mock.calls.find(call => call[1].method === 'POST')!
  expect(new Headers(create[1].headers).get('X-CSRF-TOKEN')).toBe('csrf')
  expect(JSON.parse(String(create[1].body))).toEqual({ ...fields, version: 0 })
  await store.save({ ...fields, visible: true })
  expect(store.getSnapshot().error?.requestId).toBe(id)
  expect(fetcher.mock.calls.filter(call => call[1].method === 'PUT')).toHaveLength(1)
  expect(store.getSnapshot().store?.visible).toBe(false)
  disconnect()
})
test('세션 변경 후 늦게 도착한 유효한 매대 정보는 복원되지 않는다', async () => {
  let release!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/auth/me' ? json({ id, roles: ['SELLER'] }) : new Promise<Response>(resolve => { release = resolve })))
  const auth = new AuthStore(); await auth.refresh()
  const store = new StoreEditorStore(auth); const disconnect = store.connect()
  await waitFor(() => expect(release).toBeDefined())
  auth.stop()
  release(json({ store: { ...fields, id, version: 0 } }))
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(store.getSnapshot().store).toBeUndefined()
  disconnect()
})
