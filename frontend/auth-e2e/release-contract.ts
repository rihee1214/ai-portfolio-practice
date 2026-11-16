import { readFileSync } from 'node:fs'

export type PendingCredentials = { pendingEmail: string; emailVerificationToken: string; passwordResetEmail: string; passwordResetToken: string }
export type Stack = { baseURL: string; observerURL: string; observerKey: string; gateUser?: string; gatePassword?: string; guestBaseURL?: string; credentials?: PendingCredentials }
export type Actors = { buyer: string; seller: string; other: string; admin: string }
export type Reconciliation = {
  expectationSource: 'approved-p09-p11-independent-calculation'; producerEvidencePath: string
  scenarios: string[]
  orders: { orderNumber: string; buyerEmail: string; state: string; total: string; paymentState: string
    units: { itemId: string; ordinal: number; gross: string; promotionDiscount: string; couponDiscount: string; net: string }[]
    refunds: { attemptId: string; state: string; amount: string }[] }[]
  settlements: { sellerEmail: string; sellerOrderId: string; storeId: string; batchId: string; weekStart: string
    net: string; payout: string; opening: string; closing: string }[]
  failedJobs: { id: string; type: string; status: 'FAILED'; attempts: number; error: string }[]
}
export type ReleaseContract = {
  runId: string; mode: 'L' | 'P'; source: Stack; restore: Stack
  dist: { R0: string; R1: string }
  accounts: Record<'desktop' | 'mobile', Actors>; passwords: Record<string, string>
  emulator?: { host: string; project: string }
  reconciliation?: Reconciliation
  credentials?: PendingCredentials
}
export function contract(): ReleaseContract {
  if (!process.env.P12_D_BROWSER_CONTRACT) throw new Error('Isolated release-recovery browser contract required')
  const value = JSON.parse(readFileSync(process.env.P12_D_BROWSER_CONTRACT, 'utf8')) as ReleaseContract
  if (!/^[0-9a-f-]{36}$/i.test(value.runId) || !['L', 'P'].includes(value.mode)) throw new Error('Invalid release run identity/mode')
  for (const stack of [value.source, value.restore]) {
    if (!/^https:\/\/localhost:\d+$/.test(stack.baseURL)
      || !/^http:\/\/127\.0\.0\.1:\d+$/.test(stack.observerURL) || stack.observerKey.length < 24)
      throw new Error('Explicit loopback TLS stack and private observer required')
  }
  if (value.source.baseURL === value.restore.baseURL || !value.dist.R0 || !value.dist.R1)
    throw new Error('Source, separate restore and both immutable production distributions required')
  for (const actors of Object.values(value.accounts)) for (const email of Object.values(actors)) {
    if (typeof value.passwords[email] !== 'string' || value.passwords[email].length < 12)
      throw new Error('Individual actor credential missing')
  }
  if (value.mode === 'L' && (!/^127\.0\.0\.1:\d+$/.test(value.emulator?.host ?? '') || !value.emulator?.project.startsWith('demo-')))
    throw new Error('Local-full recovery requires an isolated local-test emulator')
  if (value.mode === 'P' && (value.emulator || process.env.FIREBASE_AUTH_EMULATOR_HOST || process.env.GCLOUD_PROJECT))
    throw new Error('Public-demo must not inherit local emulator configuration')
  return value
}
