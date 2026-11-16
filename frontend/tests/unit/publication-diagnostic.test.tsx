// @vitest-environment node
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { EventEmitter } from 'node:events'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { operation, type BrowserContext, type Page } from '../../auth-e2e/business-deadline'
import { PublicationDiagnostic } from '../../auth-e2e/publication-diagnostic'
import type { Request, Response } from '@playwright/test'

const unitClock = vi.hoisted(() => ({ now: 0 }))

vi.mock('../../auth-e2e/business-deadline', async () => {
  const { Deadlines } = await import('../../auth-e2e/deadline-core')
  const deadlines = new Deadlines(() => {}, { now: () => unitClock.now, wall: () => 1_794_787_200_000 + unitClock.now,
    schedule: (fn, ms) => { const timer = setTimeout(fn, ms); return () => clearTimeout(timer) } })
  return { expect: (await import('vitest')).expect,
    operation: (name: string, work: () => Promise<unknown>) => deadlines.scope('operation', name, work),
    currentDeadline: () => { const scope = deadlines.current(); if (!scope) throw new Error('Unit command scope required'); scope.remaining(); return scope } }
})

const id = '11111111-1111-4111-8111-111111111111', key = '22222222-2222-4222-8222-222222222222'
const runId = '33333333-3333-4333-8333-333333333333', runKey = 'c'.repeat(64)
const database = 'd2_it_' + 'a'.repeat(32), literal = '<script>public literal</script>'
const published = { id, version: 1, kind: 'NOTICE', state: 'PUBLISHED', title: 'synthetic notice', body: literal,
  alt: '', priority: 0, destination: null, startsAt: '2026-11-16T00:00:00Z', endsAt: null, asset: null }
const command = { key, expectedVersion: 0, kind: 'NOTICE', state: 'PUBLISHED', title: published.title, body: literal, startsAt: null, endsAt: null }
let folder: string
beforeEach(() => {
  unitClock.now = 0
  folder = mkdtempSync(resolve('../.local-tools/publication-diagnostic-unit-'))
  vi.stubEnv('A3_EVIDENCE', folder); vi.stubEnv('A3_API_PORT', '25001')
  writeFileSync(resolve(folder, 'publication-observer-contract.private.json'), JSON.stringify({ runId, runKey, database, apiPort: 25001,
    endpoint: '/api/__test/publication-observation', clockMeaning: 'separate-readonly-select-not-public-query-clock-or-snapshot' }))
})
afterEach(() => vi.unstubAllEnvs())
function response(url: string, method: string, body: unknown, input?: unknown, completed?: () => void,
  options: { request?: Request; status?: number; bodyFailure?: string; raw?: string } = {}): Response {
  return { url: () => 'http://127.0.0.1:24176' + url, status: () => 200,
    headers: () => ({ 'content-type': 'application/json', 'cache-control': 'no-store', date: 'Mon, 16 Nov 2026 00:00:00 GMT', 'x-request-id': 'synthetic-request' }),
    request: () => options.request ?? ({ method: () => method, url: () => 'http://127.0.0.1:24176' + url, postDataJSON: () => input, timing: () => ({ startTime: 1, responseEnd: 2 }) }),
    ...(options.status ? { status: () => options.status } : {}),
    text: async () => { completed?.(); if (options.bodyFailure) throw new Error(options.bodyFailure); return options.raw ?? JSON.stringify(body) } } as unknown as Response
}
function page() {
  const events = new EventEmitter()
  const waits: { predicate: (response: Response) => boolean; accept: (response: Response) => void }[] = []
  let registered = 0
  const result = { page: { on: events.on.bind(events), off: events.off.bind(events), url: () => 'http://127.0.0.1:24176/notices',
    waitForResponse: (predicate: (response: Response) => boolean) => {
    registered++; return new Promise<Response>(accept => { waits.push({ predicate, accept }) })
  } } as unknown as Page,
  emit: (reply: Response) => { events.emit('response', reply); const index = waits.findIndex(wait => wait.predicate(reply)); if (index >= 0) waits.splice(index, 1)[0].accept(reply) },
  registered: () => registered,
  listeners: () => events.eventNames().reduce((count, name) => count + events.listenerCount(name), 0),
  natural: (body: unknown, options: { status?: number; bodyFailure?: string; raw?: string; url?: string; pending?: boolean; completed?: () => void } = {}) => {
    let failure: string | null = null
    const url = options.url ?? '/api/site/posts?kind=NOTICE&page=0'
    const request = { url: () => 'http://127.0.0.1:24176' + url, method: () => 'GET',
      timing: () => ({ startTime: 1, responseEnd: 2 }), failure: () => failure ? { errorText: failure } : null } as unknown as Request
    events.emit('request', request)
    const reply = response(url, 'GET', body, undefined, options.completed, { ...options, request })
    result.emit(reply)
    if (!options.pending) events.emit('requestfinished', request)
    return { request, cancel: (reason = 'net::ERR_ABORTED') => { failure = reason; events.emit('requestfailed', request) } }
  } }
  return result
}
function observer(visible: boolean, postFails: boolean, firstPublicCompleted: () => boolean) {
  const phases: string[] = []
  return { phases, context: { request: { get: async (_path: string, options: { params: { phase: string } }) => {
    expect(firstPublicCompleted(), 'No observer request may precede completion of the first actual public GET').toBe(true)
    const phase = options.params.phase; phases.push(phase)
    return { status: () => phase === 'post-public-get' && postFails ? 503 : 200, text: async () => JSON.stringify({ runId, database, apiPort: 25001, phase,
      clockMeaning: 'separate-readonly-select-not-public-query-clock-or-snapshot', queryLimitSeconds: 3,
      observation: { database, transactionReadOnly: 'on', transactionIsolation: 'repeatable read', observerStatementClock: '2026-11-16T00:00:01Z', observerDatabaseClock: '2026-11-16T00:00:01.001Z',
        saved: { id, requestKey: key, version: 1, bodySha256: createHash('sha256').update(literal).digest('hex') }, head: { id, visibleAtObserverStatement: visible } } }) }
  } } } as unknown as BrowserContext }
}
function rows(): { event: string; operationId: string; operationDeadlineAt: number; sequence: number; data: { rawSafeJson?: unknown; [key: string]: unknown } }[] {
  return readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
}
type Visitor = ReturnType<typeof page>
async function journey(publicBody: unknown, visible = true, postFails = false,
  scenario: { navigate?: (visitor: Visitor, completed: () => void) => void; ui?: (visitor: Visitor) => void } = {}) {
  let firstPublicCompleted = false
  const admin = page(), visitor = page(), staff = observer(visible, postFails, () => firstPublicCompleted)
  let failure: unknown
  try { await operation('unit actual publication command', async () => {
    const diagnostic = new PublicationDiagnostic('desktop')
    const saved = diagnostic.captureSave(admin.page, published.title, literal)
    admin.emit(response('/api/admin/content/site-posts/' + id, 'POST', published, command))
    admin.emit(response('/api/admin/content/site-posts?kind=NOTICE&page=0', 'GET', [published]))
    expect(rows().map(row => row.event)).toEqual(['diagnostic-start'])
    await diagnostic.publicList(visitor.page, staff.context, saved, async () => {
      const completed = () => { firstPublicCompleted = true }
      if (scenario.navigate) scenario.navigate(visitor, completed)
      else visitor.natural(publicBody, { completed })
    }, async (post, mark) => {
      mark('expected-list-link', { expectedPostId: post.id, actualHref: '/notices/' + post.id, visible: true })
      scenario.ui?.(visitor)
      mark('exact-detail-body', { expectedPostId: post.id, actualUrl: '/notices/' + post.id, literalBodyMatched: true })
      return '/notices/' + post.id
    })
  }) } catch (caught) { failure = caught }
  return { failure, staff, admin, visitor }
}

test('publication diagnostics retain exact causal responses under one original operation and never expose the observer key', async () => {
  const result = await journey([published]); expect(result.failure).toBeUndefined()
  const evidence = rows(); expect(new Set(evidence.map(row => row.operationId)).size).toBe(1); expect(new Set(evidence.map(row => row.operationDeadlineAt)).size).toBe(1)
  expect(evidence.map(row => row.sequence)).toEqual(evidence.map((_row, index) => index + 1))
  expect(evidence.find(row => row.event === 'actual-first-public-get-body')?.data.rawSafeJson).toEqual([published])
  expect(evidence.find(row => row.event === 'ui-expected-list-link')?.data).toMatchObject({ expectedPostId: id, actualHref: '/notices/' + id })
  expect(evidence.find(row => row.event === 'ui-exact-detail-body')?.data).toMatchObject({ expectedPostId: id, literalBodyMatched: true })
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).not.toContain(runKey)
})
test('an empty first public response is preserved and fails after its post sample without another public request', async () => {
  const result = await journey([]); expect(result.failure).toBeInstanceOf(Error)
  expect(result.staff.phases).toEqual(['post-public-get']); expect(result.admin.registered()).toBe(2)
  expect(rows().find(row => row.event === 'natural-public-coverage')?.data.requests).toBe(1)
  expect(rows().find(row => row.event === 'actual-first-public-get-body')?.data.rawSafeJson).toEqual([])
})
test('a false post observer visibility sample cannot replace the actual first public GET', async () => {
  const result = await journey([published], false); expect(result.failure).toBeUndefined()
  expect(rows().find(row => row.event === 'natural-public-coverage')?.data.requests).toBe(1)
  expect(result.staff.phases).toEqual(['post-public-get'])
})
test('a failed post observer preserves the original empty public response failure and its own cause', async () => {
  const result = await journey([], true, true); expect(result.failure).toBeInstanceOf(AggregateError)
  const failure = result.failure as AggregateError
  expect(failure.errors).toHaveLength(2); expect(failure.cause).toBe(failure.errors[1])
  expect(result.staff.phases).toEqual(['post-public-get'])
  expect(rows().find(row => row.event === 'actual-first-public-get-body')?.data.rawSafeJson).toEqual([])
})

test('a matching first list followed by a naturally completed empty list is rejected despite successful UI', async () => {
  const result = await journey([published], true, false, { ui: visitor => { visitor.natural([]) } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(rows().filter(row => row.event === 'natural-public-body').map(row => row.data.rawSafeJson)).toEqual([[published], []])
  expect(result.visitor.listeners()).toBe(0)
})

test('a completed empty first list cannot be replaced by a later matching natural response', async () => {
  const result = await journey([], true, false, { navigate: (visitor, completed) => {
    visitor.natural([], { completed }); visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(rows().find(row => row.event === 'actual-first-public-get-body')?.data.rawSafeJson).toEqual([])
})

test('first headers cancelled before body completion allow only same-request cancellation and another matching natural completion', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural(undefined, { pending: true, bodyFailure: 'Protocol body unavailable' }).cancel()
    visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeUndefined()
  const evidence = readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')
  expect(evidence).toContain('net::ERR_ABORTED'); expect(evidence).toContain('Protocol body unavailable')
  expect(evidence).toContain('cancelled-before-readable-body'); expect(evidence).toContain('firstEligibleRequestId')
  const requests = rows().filter(row => row.event === 'natural-public-request')
  expect(requests).toHaveLength(2)
  expect(new Set(requests.map(row => row.data.requestIdentity)).size).toBe(2)
  expect(rows().find(row => row.event === 'first-eligible-public-response')?.data.firstEligibleRequestId).toBe(requests[1].data.requestIdentity)
  expect(result.visitor.listeners()).toBe(0)
})

test('a body-read failure without same-request explicit cancellation blocks successful UI', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural(undefined, { bodyFailure: 'Unreadable finished body' }); visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).toContain('Unreadable finished body')
  expect(result.visitor.listeners()).toBe(0)
})

test('a different request cancellation cannot excuse the first request body-read failure', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural(undefined, { bodyFailure: 'First body failed' })
    visitor.natural(undefined, { pending: true, bodyFailure: 'Other body failed' }).cancel()
    visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
})

test('a completed empty response remains contradictory after a later cancellation of that same request', async () => {
  let old: ReturnType<Visitor['natural']>
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural([published], { completed }); old = visitor.natural([])
  }, ui: () => { old.cancel() } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(rows().filter(row => row.event === 'natural-public-body').map(row => row.data.rawSafeJson)).toContainEqual([])
})

test('only cancelled natural requests without a matching completion cannot satisfy publication evidence', async () => {
  const result = await journey([published], true, false, { navigate: visitor => {
    visitor.natural(undefined, { pending: true, bodyFailure: 'Cancelled body' }).cancel()
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(result.staff.phases).toEqual([])
  expect(result.visitor.listeners()).toBe(0)
})

test('an initiated pending request cannot outlive the original shared deadline or retain listeners', async () => {
  const result = await journey([published], true, false, { ui: visitor => {
    visitor.natural([published], { pending: true }); unitClock.now = 10_001
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(result.visitor.listeners()).toBe(0)
  const evidence = rows(); expect(new Set(evidence.map(row => row.operationId)).size).toBe(1)
  expect(new Set(evidence.map(row => row.operationDeadlineAt)).size).toBe(1)
})

test('only pending natural requests cannot become implicit success at the navigation boundary', async () => {
  const result = await journey([published], true, false, { navigate: visitor => {
    visitor.natural([published], { pending: true }); unitClock.now = 10_001
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(result.staff.phases).toEqual([])
  expect(result.visitor.listeners()).toBe(0)
})

test('a non-cancellation browser failure cannot excuse unreadable body evidence', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural(undefined, { pending: true, bodyFailure: 'Connection lost' }).cancel('net::ERR_CONNECTION_RESET')
    visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).toContain('net::ERR_CONNECTION_RESET')
})

test('a completed HTTP failure cannot be replaced by a later matching natural list', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural([], { status: 503, completed }); visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
})

test('a completed malformed JSON response cannot be replaced by a later matching natural list', async () => {
  const result = await journey([published], true, false, { navigate: (visitor, completed) => {
    visitor.natural(undefined, { raw: '{broken', completed }); visitor.natural([published], { completed })
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).toContain('invalidJson')
})

test('unexpected related list query is retained and blocks silent scope loss', async () => {
  const result = await journey([published], true, false, { ui: visitor => {
    visitor.natural([published], { url: '/api/site/posts?kind=NOTICE&page=1' })
  } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).toContain('page=1')
})

test('a real UI assertion failure remains recorded and removes every passive listener', async () => {
  const result = await journey([published], true, false, { ui: () => { throw new Error('Original exact detail body missing') } })
  expect(result.failure).toBeInstanceOf(Error)
  expect(String(result.failure)).toContain('Original exact detail body missing')
  expect(result.visitor.listeners()).toBe(0)
  expect(readFileSync(resolve(folder, 'publication-causal-desktop.ndjson'), 'utf8')).toContain('ui-failed')
})
