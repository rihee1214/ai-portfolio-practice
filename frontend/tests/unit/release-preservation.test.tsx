// @vitest-environment node
import { expect, test, vi } from 'vitest'
import type { ManagementResult, Snapshot } from '../../auth-e2e/release-infrastructure'
import { preservation } from '../../auth-e2e/release-preservation'

vi.mock('../../auth-e2e/business-deadline', async () => ({
  expect: (await import('vitest')).expect,
  operation: async (_name: string, work: () => Promise<unknown>) => work(),
}))
vi.mock('../../auth-e2e/release-helpers', () => ({ evidence: vi.fn() }))

const inputId = '10000000-0000-4000-8000-000000000001'
const quietId = '20000000-0000-4000-8000-000000000002'
const afterId = '30000000-0000-4000-8000-000000000003'
const digest = 'a'.repeat(64)
function result(): ManagementResult {
  const tables = ['claim_exchange_refund_recovery_run', 'claim_exchange_refund_recovery_stage',
    'claim_exchange_refund_recovery_page', 'claim_exchange_refund_recovery_page_complete',
    'claim_return_recovery_run', 'claim_return_recovery_stage', 'claim_return_recovery_page',
    'claim_return_recovery_page_complete', 'claim_return_recovery_candidate', 'job_claim_fact'].sort()
  return { ready: true, releaseId: 'R0', preservation: {
    boundaryExact: { passed: true, beforeSnapshotId: quietId, beforeDigest: digest, afterDigest: digest,
      tableInventory: tables, sequencesEqual: true, mediaEqual: true },
    live: { passed: true, inputSnapshotId: inputId, beforeSnapshotId: quietId, afterSnapshotId: afterId,
      quietBaselineDigest: digest, allOldRowsPreserved: true, coverageComplete: true, unregisteredChanges: [],
      typeRegistry: tables, typedResults: Object.fromEntries(tables.map(table => [table, {
        passed: true, validator: 'backend-typed-cause-graph', addedIds: [], changedIds: [],
      }])) },
  } }
}

test('rollback preservation binds the actual quiet baseline and retains the original running input identity', async () => {
  await expect(preservation(result(), inputId)).resolves.toBeUndefined()
})

test.each(['running snapshot alias', 'wrong input identity', 'unbound quiet digest'] as const)(
  'rollback preservation rejects %s despite otherwise passed backend flags', async fault => {
    const proof = result(), live = proof.preservation!.live
    if (fault === 'running snapshot alias') {
      live.beforeSnapshotId = inputId
      proof.preservation!.boundaryExact.beforeSnapshotId = inputId
    } else if (fault === 'wrong input identity') live.inputSnapshotId = afterId
    else live.quietBaselineDigest = 'b'.repeat(64)
    await expect(preservation(proof, inputId)).rejects.toThrow()
  })

test('restore preservation keeps the original checkpoint baseline and separate stopped source guard', async () => {
  const proof = result()
  const original = { reference: { commandId: inputId, sourceIdentity: 'source', manifestSha256: digest, path: 'original/checkpoint.json' },
    snapshot: { snapshotId: inputId, full: { sha256: digest,
      tableInventory: proof.preservation!.boundaryExact.tableInventory, sequences: [], mediaDigest: digest } } }
  const stoppedSource: Snapshot = { snapshotId: quietId, full: original.snapshot.full }
  proof.checkpointRef = original.reference
  proof.preservation!.live.beforeSnapshotId = inputId
  delete proof.preservation!.live.inputSnapshotId
  delete proof.preservation!.live.quietBaselineDigest
  delete proof.preservation!.boundaryExact.beforeSnapshotId
  proof.preservation!.sourceStopped = { passed: true, beforeSnapshotId: quietId, afterSnapshotId: afterId,
    beforeDigest: digest, afterDigest: digest }
  await expect(preservation(proof, inputId, original, stoppedSource)).resolves.toBeUndefined()
})
