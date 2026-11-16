import { appendFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import type { Stack } from './release-contract'

type Phase = { event: 'start' | 'progress' | 'end'; id: string; name: string; limitMs?: number; status?: 'passed' | 'failed' }
export type CheckpointReference = { commandId: string; sourceIdentity: string; manifestSha256: string; path: string }
export type FullSnapshot = { sha256: string; tableInventory: string[]; sequences: unknown; mediaDigest: string }
export type Snapshot = { snapshotId: string; full: FullSnapshot; business?: unknown }
export type PreservationProof = {
  boundaryExact: { passed: boolean; beforeSnapshotId?: string; beforeDigest: string; afterDigest: string; tableInventory: string[]; sequencesEqual: boolean; mediaEqual: boolean }
  live: { passed: boolean; beforeSnapshotId: string; afterSnapshotId: string; allOldRowsPreserved: boolean; coverageComplete: boolean; unregisteredChanges: unknown[];
    inputSnapshotId?: string; quietBaselineDigest?: string;
    typeRegistry: string[]; typedResults: Record<string, { passed: boolean; validator: string; addedIds: string[]; changedIds: string[] }> }
  sourceStopped?: { passed: boolean; beforeSnapshotId: string; afterSnapshotId: string; beforeDigest: string; afterDigest: string }
}
export type ManagementResult = { ready: boolean; releaseId: string; checkpointRef?: CheckpointReference;
  quiescedSnapshot?: Snapshot; preservation?: PreservationProof; [key: string]: unknown }
type ManagementOptions = { checkpointRef?: CheckpointReference; beforeSnapshotId?: string }

/** Administrative work has observed finite phases, without a suite or command-total timeout.
 * No commerce request or readiness assertion is performed inside these phases. */
export async function infrastructure(stack: Stack, action: 'Checkpoint' | 'Activate' | 'Rollback' | 'Restore', options: ManagementOptions = {}) {
  const evidence = process.env.A3_EVIDENCE!
  const commandId = randomUUID()
  let phase: { id: string; name: string; startedAt: number; deadlineAt: number } | undefined
  let remoteId: string | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  const abort = new AbortController()
  const record = (value: unknown) => appendFileSync(resolve(evidence, 'release-infrastructure.ndjson'), JSON.stringify(value) + '\n')
  const emit = (event: 'start' | 'end', status?: 'passed' | 'failed') => {
    appendFileSync(resolve(evidence, 'browser-progress.ndjson'), JSON.stringify({ ...phase, event, kind: 'lifecycle', pid: process.pid,
      ...(event === 'end' ? { endedAt: Date.now(), status } : {}) }) + '\n')
  }
  const arm = (remaining: number) => { clearTimeout(timer); timer = setTimeout(() => abort.abort(new Error('Release infrastructure phase/transition deadline exceeded: ' + action)), remaining) }
  record({ event: 'command-start', commandId, action, ...options, at: Date.now() })
  // Connecting and moving between phases retain a finite ten-second ceiling.
  arm(10_000)
  try {
    const response = await fetch(stack.observerURL + '/operation', { method: 'POST', headers: {
      'Content-Type': 'application/json', 'X-Observer-Key': stack.observerKey,
    }, body: JSON.stringify({ action, commandId, ...options }), signal: abort.signal })
    if (response.status !== 200 || !response.headers.get('content-type')?.includes('ndjson') || !response.body)
      throw new Error('Observed release operation stream required: ' + response.status)
    const reader = response.body.getReader(), decoder = new TextDecoder()
    let buffer = '', result: ManagementResult | undefined
    const seen = new Set<string>()
    while (true) {
      const chunk = await reader.read()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      let newline: number
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1)
        if (!line.trim()) continue
        const event = JSON.parse(line) as Phase | { event: 'result'; result: ManagementResult }
        if (result) throw new Error('Unexpected release stream data after final result')
        if (event.event === 'result') {
          if (phase || !seen.size) throw new Error('Result lacks completed finite infrastructure phases')
          result = event.result; arm(10_000); continue
        }
        if (!event.id || !event.name) throw new Error('Release phase identity/name required')
        if (event.event === 'start') {
          if (phase || seen.has(event.id) || !Number.isInteger(event.limitMs) || event.limitMs! < 1 || event.limitMs! > 60_000)
            throw new Error('Invalid overlapping or unbounded release phase')
          seen.add(event.id)
          const startedAt = Date.now()
          remoteId = event.id
          phase = { id: randomUUID(), name: 'release infrastructure: ' + action + ': ' + event.name,
            startedAt, deadlineAt: startedAt + event.limitMs! }
          emit('start'); arm(event.limitMs!)
        } else {
          if (!phase || remoteId !== event.id || Date.now() > phase.deadlineAt)
            throw new Error('Unmatched or late release phase')
          if (event.event === 'end') {
            emit('end', event.status === 'passed' ? 'passed' : 'failed')
            phase = undefined
            if (event.status !== 'passed') throw new Error('Release infrastructure failed: ' + event.name)
            arm(10_000)
          } else if (event.event !== 'progress') throw new Error('Unknown release infrastructure event')
        }
        record({ ...event, commandId, action, at: Date.now() })
      }
      if (chunk.done) break
    }
    if (buffer.trim() || phase || !result) throw new Error('Incomplete release infrastructure stream')
    record({ event: 'command-end', commandId, action, status: 'passed', at: Date.now(), result })
    return result
  } catch (error) {
    if (phase) emit('end', 'failed')
    abort.abort(error)
    record({ event: 'command-end', commandId, action, status: 'failed', at: Date.now(), error: String(error) })
    throw error
  } finally { clearTimeout(timer) }
}
