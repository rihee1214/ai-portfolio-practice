// @vitest-environment node
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mkdtempSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { infrastructure } from '../../auth-e2e/release-infrastructure'

const stack = { baseURL: 'https://localhost:24443', observerURL: 'http://127.0.0.1:24001', observerKey: 'isolated-observer-key-for-tests' }
let folder: string
beforeEach(() => {
  folder = mkdtempSync(resolve('../.local-tools/p12-d-browser-unit-'))
  vi.stubEnv('A3_EVIDENCE', folder)
})
afterEach(() => vi.unstubAllEnvs())
function stream(events: unknown[]) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(events.map(event => JSON.stringify(event)).join('\n') + '\n', {
    status: 200, headers: { 'Content-Type': 'application/x-ndjson' },
  })))
}
const start = (id = 'dump', limitMs = 60_000) => ({ event: 'start', id, name: id, limitMs })
const end = (id = 'dump', status = 'passed') => ({ event: 'end', id, name: id, status })
const result = { event: 'result', result: { ready: true, releaseId: 'R1' } }

test('release infrastructure records separately bounded observed phases without an aggregate command budget', async () => {
  stream([start(), { event: 'progress', id: 'dump', name: 'dump' }, end(), start('media'), end('media'), result])
  expect(await infrastructure(stack, 'Activate')).toEqual(result.result)
  const events = readFileSync(resolve(folder, 'browser-progress.ndjson'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
  expect(events.map(event => [event.event, event.kind])).toEqual([
    ['start', 'lifecycle'], ['end', 'lifecycle'], ['start', 'lifecycle'], ['end', 'lifecycle'],
  ])
  expect(new Set(events.map(event => event.id)).size).toBe(2)
  expect(events.every(event => /^[0-9a-f-]{36}$/.test(event.id) && event.deadlineAt - event.startedAt === 60000)).toBe(true)
  expect(events.filter(event => event.event === 'end').every(event => event.status === 'passed')).toBe(true)
})

test('restore forwards the selected original checkpoint and snapshot identity without replacing them', async () => {
  const checkpointRef = { commandId: 'original-checkpoint-command', sourceIdentity: 'original-source',
    manifestSha256: 'a'.repeat(64), path: 'isolated/original-checkpoint.json' }
  const options = { checkpointRef, beforeSnapshotId: 'stopped-source-snapshot' }
  stream([start(), end(), result])
  expect(await infrastructure(stack, 'Restore', options)).toEqual(result.result)
  const request = JSON.parse(vi.mocked(fetch).mock.calls[0][1]!.body as string)
  expect(request).toEqual({ action: 'Restore', commandId: expect.stringMatching(/^[0-9a-f-]{36}$/), ...options })
  const log = readFileSync(resolve(folder, 'release-infrastructure.ndjson'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
  expect(log[0]).toMatchObject({ event: 'command-start', action: 'Restore', ...options })
})
test.each([
  ['overlapping phases', [start(), start('restore')]],
  ['over-budget phase', [start('dump', 60001)]],
  ['duplicate phase identity', [start(), end(), start()]],
  ['unmatched phase end', [start(), end('restore')]],
  ['result before phase completion', [start(), result]],
  ['empty success without observed work', [result]],
  ['truncated stream', [start()]],
  ['explicit failed phase', [start(), end('dump', 'failed')]],
])('release infrastructure rejects %s and retains its failure evidence', async (_label, events) => {
  stream(events)
  await expect(infrastructure(stack, 'Restore')).rejects.toThrow()
  const log = readFileSync(resolve(folder, 'release-infrastructure.ndjson'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
  expect(log.at(-1)).toMatchObject({ event: 'command-end', action: 'Restore', status: 'failed' })
})
test('failed observed phase emits exactly one failed terminal event', async () => {
  stream([start(), end('dump', 'failed')])
  await expect(infrastructure(stack, 'Checkpoint')).rejects.toThrow('Release infrastructure failed')
  const events = readFileSync(resolve(folder, 'browser-progress.ndjson'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
  expect(events).toHaveLength(2)
  expect(events[1]).toMatchObject({ event: 'end', status: 'failed', id: events[0].id })
})
