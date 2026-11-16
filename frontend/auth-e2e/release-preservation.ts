import { expect, operation } from './business-deadline'
import { evidence } from './release-helpers'
import type { ManagementResult, Snapshot } from './release-infrastructure'

const scannerTables = [
  'claim_exchange_refund_recovery_run', 'claim_exchange_refund_recovery_stage',
  'claim_exchange_refund_recovery_page', 'claim_exchange_refund_recovery_page_complete',
  'claim_return_recovery_run', 'claim_return_recovery_stage', 'claim_return_recovery_page',
  'claim_return_recovery_page_complete', 'claim_return_recovery_candidate', 'job_claim_fact',
]

export async function completedCheckpoint(result: ManagementResult) {
  return operation('release: bind completed checkpoint manifest to quiesced full snapshot', async () => {
    expect(result.checkpointRef).toMatchObject({ commandId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      sourceIdentity: expect.any(String), manifestSha256: expect.stringMatching(/^[0-9a-f]{64}$/), path: expect.any(String) })
    const reference = result.checkpointRef!
    expect(reference.sourceIdentity.length).toBeGreaterThan(0); expect(reference.path.length).toBeGreaterThan(0)
    expect(result.quiescedSnapshot).toMatchObject({ snapshotId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      full: { sha256: expect.stringMatching(/^[0-9a-f]{64}$/), tableInventory: expect.any(Array),
        sequences: expect.any(Array), mediaDigest: expect.stringMatching(/^[0-9a-f]{64}$/) } })
    const snapshot = result.quiescedSnapshot!
    expect(snapshot.full.tableInventory.length).toBeGreaterThan(0)
    expect(snapshot.full.tableInventory).toEqual([...new Set(snapshot.full.tableInventory)].sort())
    evidence('release-checkpoint-reference', { reference, snapshot })
    return { reference, snapshot }
  })
}

export async function preservation(result: ManagementResult, inputSnapshotId: string,
  originalCheckpoint?: Awaited<ReturnType<typeof completedCheckpoint>>, stoppedSource?: Snapshot) {
  return operation('release: consume exact quiescent boundary and typed live graph preservation proof', async () => {
    expect(result.preservation).toMatchObject({
      boundaryExact: { passed: true, beforeDigest: expect.stringMatching(/^[0-9a-f]{64}$/),
        afterDigest: expect.stringMatching(/^[0-9a-f]{64}$/), tableInventory: expect.any(Array), sequencesEqual: true, mediaEqual: true },
      live: { passed: true, beforeSnapshotId: expect.stringMatching(/^[0-9a-f-]{36}$/), afterSnapshotId: expect.stringMatching(/^[0-9a-f-]{36}$/),
        allOldRowsPreserved: true, coverageComplete: true, unregisteredChanges: [],
        typeRegistry: expect.any(Array), typedResults: expect.any(Object) },
    })
    const proof = result.preservation!
    expect(proof.boundaryExact.beforeDigest).toBe(proof.boundaryExact.afterDigest)
    expect(proof.boundaryExact.tableInventory.length).toBeGreaterThan(0)
    expect(proof.live.typeRegistry).toEqual(Object.keys(proof.live.typedResults).sort())
    expect(proof.live.typeRegistry).toEqual(expect.arrayContaining(scannerTables))
    for (const validator of Object.values(proof.live.typedResults)) {
      expect(validator).toMatchObject({ passed: true, validator: expect.any(String), addedIds: expect.any(Array), changedIds: expect.any(Array) })
      expect(validator.validator.length).toBeGreaterThan(0)
    }
    if (originalCheckpoint) {
      expect(proof.live.beforeSnapshotId).toBe(inputSnapshotId)
      expect(result.checkpointRef).toEqual(originalCheckpoint.reference)
      expect(proof.boundaryExact.beforeDigest).toBe(originalCheckpoint.snapshot.full.sha256)
      expect(proof.boundaryExact.tableInventory).toEqual(originalCheckpoint.snapshot.full.tableInventory)
      expect(stoppedSource).toBeDefined()
      expect(proof.sourceStopped).toMatchObject({ passed: true, beforeSnapshotId: stoppedSource!.snapshotId,
        afterSnapshotId: expect.stringMatching(/^[0-9a-f-]{36}$/), beforeDigest: expect.stringMatching(/^[0-9a-f]{64}$/),
        afterDigest: expect.stringMatching(/^[0-9a-f]{64}$/) })
      expect(proof.sourceStopped!.beforeDigest).toBe(proof.sourceStopped!.afterDigest)
    } else {
      // The browser's running snapshot is retained as input evidence; live progress starts at the operator's actual quiet boundary.
      expect(proof.live.inputSnapshotId).toBe(inputSnapshotId)
      expect(proof.boundaryExact.beforeSnapshotId).toBe(proof.live.beforeSnapshotId)
      expect(proof.live.beforeSnapshotId).not.toBe(inputSnapshotId)
      expect(proof.live.quietBaselineDigest).toBe(proof.boundaryExact.afterDigest)
    }
    evidence('release-preservation-proof', { proof, selectedCheckpoint: originalCheckpoint?.reference })
  })
}

export async function stoppedSourceUnchanged(before: Snapshot, after: Snapshot) {
  return operation('release: stopped original source preserves every table sequence and media inventory after restore', async () => {
    expect(before.full.tableInventory.length).toBeGreaterThan(0)
    expect(after.full).toEqual(before.full)
    evidence('release-stopped-source', { beforeSnapshotId: before.snapshotId, afterSnapshotId: after.snapshotId, full: after.full })
  })
}
