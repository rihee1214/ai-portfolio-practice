import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const sdk = vi.hoisted(() => ({
  initializeApp: vi.fn(() => ({ name: 'test' })),
  deleteApp: vi.fn(async () => {}),
  initializeAuth: vi.fn(() => ({ name: 'auth' })),
  connectAuthEmulator: vi.fn(),
  credential: vi.fn(() => ({ providerId: 'phone' })),
  signInWithCustomToken: vi.fn(),
}))
vi.mock('firebase/app', () => ({ initializeApp: sdk.initializeApp, deleteApp: sdk.deleteApp }))
vi.mock('firebase/auth', () => ({
  initializeAuth: sdk.initializeAuth, connectAuthEmulator: sdk.connectAuthEmulator,
  inMemoryPersistence: 'memory', PhoneAuthProvider: { credential: sdk.credential },
  signInWithCustomToken: sdk.signInWithCustomToken,
}))
import { confirmPhoneCode } from '../../src/phone/FirebasePhoneClient'

beforeEach(() => { vi.clearAllMocks(); vi.stubEnv('DEV', true) })
afterEach(() => vi.unstubAllEnvs())

it('keeps provider credentials in memory and disposes the SDK after proof retrieval', async () => {
  sdk.signInWithCustomToken.mockResolvedValue({ user: { getIdToken: async () => 'opaque-proof' } })
  await expect(confirmPhoneCode('127.0.0.1:9099', 'demo-unit', 'custom-token')).resolves.toBe('opaque-proof')
  expect(sdk.initializeAuth).toHaveBeenCalledWith({ name: 'test' }, { persistence: 'memory' })
  expect(sdk.connectAuthEmulator).toHaveBeenCalledWith({ name: 'auth' }, 'http://127.0.0.1:9099', { disableWarnings: true })
  expect(sdk.deleteApp).toHaveBeenCalledTimes(1)
})

it('rejects nonlocal production and malformed settings before SDK initialization', async () => {
  for (const [host, project, code] of [['example.com:9099', 'demo-unit', '123456'], ['127.0.0.1:9099', 'real-project', '123456'], ['127.0.0.1:65536', 'demo-unit', '123456'], ['127.0.0.1:9099', 'demo-unit', '']]) {
    await expect(confirmPhoneCode(host, project, code)).rejects.toThrow()
  }
  vi.stubEnv('DEV', false)
  await expect(confirmPhoneCode('127.0.0.1:9099', 'demo-unit', 'custom-token')).rejects.toThrow()
  expect(sdk.initializeApp).not.toHaveBeenCalled()
})

it('returns a safe retry message and disposes credentials after SDK failure', async () => {
  sdk.signInWithCustomToken.mockRejectedValue(new Error('private-provider-body'))
  await expect(confirmPhoneCode('127.0.0.1:9099', 'demo-unit', 'custom-token')).rejects.toThrow('인증번호를 확인하거나 새 인증을 요청해 주세요.')
  expect(sdk.deleteApp).toHaveBeenCalledTimes(1)
})
