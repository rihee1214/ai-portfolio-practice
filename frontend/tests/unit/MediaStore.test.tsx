import { test, expect, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { AuthStore } from '../../src/auth/AuthStore'
import { MediaStore } from '../../src/catalog/MediaStore'

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
test.each([401, 403])('이미지 저장에서 %s면 회원과 기존 이미지 목록을 폐기한다', async status => {
  const id = '00000000-0000-4000-8000-000000000001'
  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
    if (path === '/api/auth/me') return json({ id, roles: ['SELLER'] })
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (init.method === 'PUT') return json({ code: status === 401 ? 'AUTHENTICATION_REQUIRED' : 'FORBIDDEN', message: 'rejected' }, status)
    return json([{ slot: 0, assetId: id, alt: '기존 이미지', version: 1, width: 4, height: 3 }])
  }))
  const auth = new AuthStore(); await auth.refresh()
  const store = new MediaStore(auth, '/api/media/stores/' + id, 1); const disconnect = store.connect()
  await waitFor(() => expect(store.getSnapshot().items).toHaveLength(1))
  await store.save(store.getSnapshot().items![0], new File(['image'], 'x.png', { type: 'image/png' }), '새 이미지')
  expect(store.getSnapshot().items).toBeUndefined()
  expect(auth.getSnapshot().member).toBeNull()
  disconnect()
})
