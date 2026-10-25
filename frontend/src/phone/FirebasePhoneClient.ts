import { deleteApp, initializeApp } from 'firebase/app'
import { connectAuthEmulator, initializeAuth, inMemoryPersistence, signInWithCustomToken } from 'firebase/auth'

// A provider adapter only. The caller must submit the returned ID proof to Java;
// this result never establishes a purchase, account or lookup session.
export async function confirmPhoneCode(host: string, project: string, customToken: string): Promise<string> {
  if (!import.meta.env.DEV || !/^127\.0\.0\.1:[0-9]{1,5}$/.test(host)
      || !/^demo-[a-z0-9-]+$/.test(project) || !customToken) {
    throw new Error('로컬 전화 인증 설정을 확인해 주세요.')
  }
  const port = Number(host.split(':')[1])
  if (port < 1 || port > 65535) throw new Error('로컬 전화 인증 설정을 확인해 주세요.')
  const app = initializeApp({ apiKey: 'local-test-only', projectId: project }, 'phone-' + crypto.randomUUID())
  try {
    const auth = initializeAuth(app, { persistence: inMemoryPersistence })
    connectAuthEmulator(auth, 'http://' + host, { disableWarnings: true })
    const result = await signInWithCustomToken(auth, customToken)
    return await result.user.getIdToken()
  } catch {
    throw new Error('인증번호를 확인하거나 새 인증을 요청해 주세요.')
  } finally {
    await deleteApp(app)
  }
}
