import { createHash } from 'node:crypto'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import { currentDeadline, expect, type BrowserContext, type Page, type APIResponse, type Deadline } from './business-deadline'
import type { Request, Response } from '@playwright/test'

type Contract = { runId: string; runKey: string; database: string; apiPort: number; endpoint: string; clockMeaning: string }
export type PublishedNotice = { id: string; version: number; kind: string; state: string; title: string; body: string;
  startsAt: string; endsAt: string | null; [key: string]: unknown }
type CapturedResponse = { response: Response; observedHostWall: string; observedHostMonotonicMs: number }
type SaveCapture = { post: Promise<CapturedResponse>; adminGet: Promise<CapturedResponse>; notice: string; literal: string }
const clockMeaning = 'separate-readonly-select-not-public-query-clock-or-snapshot'
const postFields = new Set(['id', 'version', 'kind', 'state', 'title', 'body', 'alt', 'destination', 'priority', 'startsAt', 'endsAt', 'asset'])
const hash = (body: string) => createHash('sha256').update(body).digest('hex')
function safePublicJson(value: unknown): boolean {
  if (Array.isArray(value)) return value.every(safePublicJson)
  return typeof value === 'object' && value !== null && Object.keys(value).every(key => postFields.has(key))
}
function path(response: Response) { return new URL(response.url()) }

type NaturalRequest = { id: string; request: Request; terminal?: 'finished' | 'failed'; failure?: string; finished: boolean;
  captured?: CapturedResponse; raw?: string; bodyFailure?: string; bodySettled: boolean; readyOrder?: number;
  terminalPromise: Promise<void>; resolveTerminal: () => void; bodyPromise?: Promise<void>; missingStart: boolean }
type NaturalEvent = { event: string; data: Record<string, unknown> }

/** Observes only browser-issued work. URL equality never substitutes for Request identity. */
class NaturalPublicLists {
  private readonly entries = new Map<Request, NaturalRequest>()
  private readonly events: NaturalEvent[] = []
  private order = 0
  private changed = () => {}
  private closed = false
  private flushed = 0
  private readonly callbackFailures: unknown[] = []
  constructor(private readonly page: Page, private readonly scope: Deadline,
    private readonly record: (event: string, data: unknown) => void) {
    page.on('request', this.request)
    page.on('response', this.response)
    page.on('requestfinished', this.finished)
    page.on('requestfailed', this.failed)
    this.event('natural-public-interval-start', { url: page.url(), boundary: 'before-original-notices-navigation' })
  }
  private event(event: string, data: Record<string, unknown>) {
    this.events.push({ event, data: { ...data, naturalEventOrder: ++this.order,
      observedHostWall: new Date().toISOString(), observedHostMonotonicMs: performance.now() } })
  }
  private related(request: Request) { return request.method() === 'GET' && new URL(request.url()).pathname === '/api/site/posts' }
  private target(entry: NaturalRequest) {
    const query = new URL(entry.request.url()).searchParams
    return query.getAll('kind').length === 1 && query.get('kind') === 'NOTICE' && query.getAll('page').length <= 1
      && Number(query.get('page') ?? '0') === 0 && [...query.keys()].every(key => ['kind', 'page'].includes(key))
  }
  private get(request: Request, missingStart = false) {
    const old = this.entries.get(request)
    if (old) return old
    let resolveTerminal = () => {}
    const terminalPromise = new Promise<void>(resolve => { resolveTerminal = resolve })
    const entry: NaturalRequest = { id: 'natural-' + (this.entries.size + 1), request, terminalPromise,
      resolveTerminal, bodySettled: false, finished: false, missingStart }
    this.entries.set(request, entry)
    this.event('natural-public-request', { requestIdentity: entry.id, url: request.url(), method: request.method(),
      query: [...new URL(request.url()).searchParams], expectedScope: this.target(entry), missingStart })
    return entry
  }
  private signal(entry: NaturalRequest) {
    if (entry.terminal && (!entry.captured || entry.bodySettled) && entry.readyOrder === undefined) entry.readyOrder = this.order
    this.changed()
  }
  private guarded(work: () => void) {
    try { work() } catch (failure) { this.callbackFailures.push(failure); this.changed() }
  }
  private request = (request: Request) => this.guarded(() => { if (!this.closed && this.related(request)) this.get(request) })
  private response = (response: Response) => this.guarded(() => {
    const request = response.request()
    if (!this.related(request) || (this.closed && !this.entries.has(request))) return
    const entry = this.get(request, true)
    if (entry.captured) throw new Error('Duplicate response for one natural Request identity')
    entry.captured = { response, observedHostWall: new Date().toISOString(), observedHostMonotonicMs: performance.now() }
    const headers = response.headers()
    this.event('natural-public-response', { requestIdentity: entry.id, url: response.url(), status: response.status(),
      requestTiming: request.timing(), headers: Object.fromEntries(['content-type', 'cache-control', 'date', 'x-request-id']
        .map(name => [name, headers[name] ?? null])) })
    // Begin the natural response's body read, but never await it before the original navigation.
    entry.bodyPromise = response.text().then(raw => {
      entry.raw = raw; entry.bodySettled = true
      this.event('natural-public-body', { requestIdentity: entry.id, url: response.url(), status: response.status(), raw })
    }, failure => {
      entry.bodyFailure = String(failure); entry.bodySettled = true
      this.event('natural-public-body-read-failed', { requestIdentity: entry.id, failure: entry.bodyFailure })
    }).then(() => this.signal(entry)).catch(failure => { this.callbackFailures.push(failure); this.changed() })
  })
  private finished = (request: Request) => this.guarded(() => {
    if (!this.related(request) || (this.closed && !this.entries.has(request))) return
    const entry = this.get(request, true)
    entry.finished = true; entry.terminal = 'finished'
    this.event('natural-public-requestfinished', { requestIdentity: entry.id, url: request.url(), requestTiming: request.timing() })
    entry.resolveTerminal(); this.signal(entry)
  })
  private failed = (request: Request) => this.guarded(() => {
    if (!this.related(request) || (this.closed && !this.entries.has(request))) return
    const entry = this.get(request, true)
    entry.failure = request.failure()?.errorText ?? 'missing-browser-failure'
    // A later abort can never erase an already completed response.
    if (!entry.finished) entry.terminal = 'failed'
    this.event('natural-public-requestfailed', { requestIdentity: entry.id, url: request.url(), failure: entry.failure,
      alreadyFinished: entry.finished, readableBodyAlreadyAvailable: entry.raw !== undefined })
    entry.resolveTerminal(); this.signal(entry)
  })
  private cancelled(entry: NaturalRequest) {
    return !entry.finished && entry.terminal === 'failed' && entry.failure === 'net::ERR_ABORTED' && entry.raw === undefined
  }
  async first() {
    return this.scope.run(async () => {
      for (;;) {
        if (this.callbackFailures.length) throw new AggregateError(this.callbackFailures, 'Natural capture callback failed')
        const ready = [...this.entries.values()].filter(entry => this.target(entry) && entry.readyOrder !== undefined)
          .sort((a, b) => a.readyOrder! - b.readyOrder!)
        const first = ready.find(entry => !this.cancelled(entry))
        if (first) return first
        if (this.entries.size && [...this.entries.values()].every(entry => entry.readyOrder !== undefined))
          throw new Error('No eligible completed natural NOTICE/page0 response; cancellations are not success')
        await new Promise<void>(resolve => { this.changed = resolve })
        this.scope.remaining()
      }
    })
  }
  flush() {
    for (const { event, data } of this.events.slice(this.flushed)) {
      if (event === 'natural-public-body') {
        const { raw, ...metadata } = data
        let json: unknown, invalidJson = false
        try { json = JSON.parse(String(raw)) } catch { invalidJson = true }
        this.record(event, { ...metadata, rawBodySha256: hash(String(raw)),
          ...(safePublicJson(json) ? { rawSafeJson: json } : { unexpectedSchema: true, invalidJson }) })
      } else this.record(event, data)
    }
    this.flushed = this.events.length
  }
  stopNewRequests() {
    this.closed = true; this.page.off('request', this.request)
    this.event('natural-public-interval-end', { url: this.page.url(), boundary: 'original-ui-assertions-finished-or-failed' })
  }
  async drain() {
    await this.scope.run(async () => {
      await Promise.all([...this.entries.values()].map(async entry => {
        await entry.terminalPromise
        if (entry.bodyPromise) await entry.bodyPromise
      }))
    })
  }
  validate(post?: PublishedNotice) {
    const failures: unknown[] = [...this.callbackFailures]
    let completed = 0
    for (const entry of this.entries.values()) {
      let classification: string
      try {
        if (entry.missingStart) throw new Error('Natural request lacks its request-start identity event')
        if (!this.target(entry)) throw new Error('Unexpected related public-list URL/query: ' + entry.request.url())
        if (this.cancelled(entry)) classification = 'cancelled-before-readable-body'
        else {
          classification = 'completed-response'
          if (!entry.finished || entry.terminal !== 'finished') throw new Error('Natural request did not finish normally: ' + entry.id)
          if (entry.raw === undefined) throw new Error('Natural response body unreadable: ' + entry.id + ': ' + entry.bodyFailure)
          expect(entry.captured?.response.status()).toBe(200)
          const json: unknown = JSON.parse(entry.raw)
          expect(Array.isArray(json) && safePublicJson(json)).toBe(true)
          if (!post) throw new Error('Saved publication identity unavailable for completed-response assertion')
          expect(json).toEqual(expect.arrayContaining([expect.objectContaining({ id: post.id, version: post.version,
            kind: 'NOTICE', state: 'PUBLISHED', title: post.title, body: post.body, startsAt: post.startsAt, endsAt: post.endsAt })]))
          completed++
        }
      } catch (failure) { failures.push(failure); classification = 'evidence-failed' }
      this.record('natural-public-classification', { requestIdentity: entry.id, classification, finished: entry.finished,
        browserFailure: entry.failure ?? null, bodyReadFailure: entry.bodyFailure ?? null, readableBody: entry.raw !== undefined })
    }
    if (!completed) failures.push(new Error('At least one naturally completed matching list response is required'))
    this.record('natural-public-coverage', { requests: this.entries.size, matchingCompleted: completed, failures: failures.length,
      uiAdoptedResponseIdentity: null, meaning: 'network records correlate by expected post and actual UI outcomes; arrival order does not prove UI adoption' })
    if (failures.length) throw new AggregateError(failures, 'Natural public-list evidence failed', { cause: failures[0] })
  }
  detach() {
    this.page.off('request', this.request); this.page.off('response', this.response)
    this.page.off('requestfinished', this.finished); this.page.off('requestfailed', this.failed)
  }
}

/** Actual request observations remain in the initiating operation's existing shared deadline. */
export class PublicationDiagnostic {
  private readonly contract: Contract
  private sequence = 0
  private readonly file: string
  private readonly scope = currentDeadline()
  constructor(private readonly project: string) {
    currentDeadline().remaining()
    const folder = process.env.A3_EVIDENCE
    if (!folder) throw new Error('Publication diagnosis requires fresh browser evidence')
    const input = JSON.parse(readFileSync(resolve(folder, 'publication-observer-contract.private.json'), 'utf8')) as Contract
    if (!/^[0-9a-f-]{36}$/.test(input.runId) || !/^[0-9a-f]{64}$/.test(input.runKey)
      || !/^d2_it_[0-9a-f]{32}$/.test(input.database) || input.apiPort !== Number(process.env.A3_API_PORT)
      || input.endpoint !== '/api/__test/publication-observation' || input.clockMeaning !== clockMeaning)
      throw new Error('Explicit scoped publication observation contract required')
    this.contract = input
    this.file = resolve(folder, 'publication-causal-' + project + '.ndjson')
    this.record('diagnostic-start', { database: input.database, apiPort: input.apiPort, clockMeaning, publicQueryClockMeasured: false })
  }
  private record(event: string, data: unknown) {
    const scope = this.scope
    appendFileSync(this.file, JSON.stringify({ event, sequence: ++this.sequence, runId: this.contract.runId, project: this.project,
      operationId: scope.id, operationDeadlineAt: scope.deadlineAt, hostWall: new Date().toISOString(), hostMonotonicMs: performance.now(), data }) + '\n')
  }
  private watch(page: Page, predicate: (response: Response) => boolean) {
    const pending = page.waitForResponse(predicate, { timeout: currentDeadline().remaining(10_000) })
      .then(response => ({ response, observedHostWall: new Date().toISOString(), observedHostMonotonicMs: performance.now() }))
    void pending.catch(() => {})
    return pending
  }
  private async browserResponse(event: string, captured: CapturedResponse, completedBody?: string) {
    const { response, observedHostWall, observedHostMonotonicMs } = captured
    const request = response.request(), headers = response.headers()
    const metadata = { url: response.url(), method: request.method(), status: response.status(), observedHostWall, observedHostMonotonicMs, requestTiming: request.timing(),
      headers: Object.fromEntries(['content-type', 'cache-control', 'date', 'x-request-id'].map(name => [name, headers[name] ?? null])) }
    this.record(event + '-headers', metadata)
    const raw = completedBody ?? await response.text()
    let json: unknown
    try { json = JSON.parse(raw) } catch { json = undefined }
    this.record(event + '-body', { ...metadata, requestTiming: request.timing(), rawBodySha256: hash(raw),
      ...(safePublicJson(json) ? { rawSafeJson: json } : { unexpectedSchema: true }) })
    return { json, request }
  }
  captureSave(admin: Page, notice: string, literal: string): SaveCapture {
    const post = this.watch(admin, response => {
      return response.request().method() === 'POST' && /^\/api\/admin\/content\/site-posts\/[0-9a-f-]{36}$/.test(path(response).pathname)
    })
    const adminGet = this.watch(admin, response => response.request().method() === 'GET'
      && path(response).pathname === '/api/admin/content/site-posts' && path(response).searchParams.get('kind') === 'NOTICE'
      && Number(path(response).searchParams.get('page') ?? '0') === 0)
    // Arm only: no response analysis, evidence write or observer await gates the original public navigation.
    return { post, adminGet, notice, literal }
  }
  private async consumeSave(capture: SaveCapture) {
    const { notice, literal } = capture
    const captured = await capture.post, response = captured.response, actual = await this.browserResponse('actual-ui-post', captured)
    const command = actual.request.postDataJSON() as Record<string, unknown>
    this.record('actual-ui-command', { url: actual.request.url(), key: command.key, expectedVersion: command.expectedVersion,
      kind: command.kind, state: command.state, title: command.title, bodySha256: typeof command.body === 'string' ? hash(command.body) : null,
      startsAt: command.startsAt, endsAt: command.endsAt })
    const published = actual.json as PublishedNotice
    const listedCapture = await capture.adminGet, listedResponse = listedCapture.response, listed = await this.browserResponse('actual-admin-get', listedCapture)
    const assertSaved = () => {
      expect(response.status()).toBe(200)
      expect(command).toMatchObject({ key: expect.stringMatching(/^[0-9a-f-]{36}$/), expectedVersion: 0, kind: 'NOTICE', state: 'PUBLISHED',
        title: notice, body: literal, startsAt: null, endsAt: null })
      expect(published).toMatchObject({ id: new URL(actual.request.url()).pathname.split('/').at(-1), version: 1,
        kind: 'NOTICE', state: 'PUBLISHED', title: notice, body: literal, startsAt: expect.any(String), endsAt: null })
      expect(listedResponse.status()).toBe(200)
      expect(listed.json).toEqual(expect.arrayContaining([expect.objectContaining({ id: published.id, version: published.version, state: 'PUBLISHED' })]))
    }
    return { published, requestKey: String(command.key), assertSaved }
  }
  private async observe(staff: BrowserContext, post: PublishedNotice, key: string, phase: string) {
    this.record('observer-request', { phase, postId: post.id, requestKey: key, clockMeaning })
    const response: APIResponse = await staff.request.get(this.contract.endpoint, { params: { postId: post.id, requestKey: key, phase },
      headers: { 'X-Publication-Run': this.contract.runId, 'X-Publication-Key': this.contract.runKey } })
    const raw = await response.text()
    let body: unknown
    try { body = JSON.parse(raw) } catch { body = { invalidJson: true, sha256: hash(raw) } }
    this.record('observer-response', { phase, status: response.status(), body })
    expect(response.status()).toBe(200)
    expect(body).toMatchObject({ runId: this.contract.runId, database: this.contract.database, apiPort: this.contract.apiPort, phase,
      clockMeaning, queryLimitSeconds: 3, observation: { database: this.contract.database, transactionReadOnly: 'on', transactionIsolation: 'repeatable read',
        observerStatementClock: expect.any(String), observerDatabaseClock: expect.any(String),
        saved: { id: post.id, requestKey: key, version: post.version, bodySha256: hash(post.body) }, head: { id: post.id } } })
    // A later sample never replaces the completed first public response's own visibility result.
    return body
  }
  async publicList<T>(page: Page, staff: BrowserContext, capture: SaveCapture, navigate: () => Promise<unknown>,
    consumeUi: (post: PublishedNotice, mark: (event: string, data: unknown) => void) => Promise<T>): Promise<T> {
    const natural = new NaturalPublicLists(page, this.scope, (event, data) => this.record(event, data))
    const failures: unknown[] = []
    let observationFailure: unknown
    let uiPassed = false
    let saved: Awaited<ReturnType<PublicationDiagnostic['consumeSave']>> | undefined, result: T | undefined
    try {
      await navigate()
      const first = await natural.first()
      // No deferred POST/admin parsing, writes or DB query gates the original navigation or first body read.
      natural.flush()
      saved = await this.consumeSave(capture)
      if (!first.captured || first.raw === undefined) throw new Error('First non-cancelled natural response body is unreadable: ' + first.bodyFailure)
      this.record('first-eligible-public-response', { firstEligibleRequestId: first.id,
        meaning: 'first naturally completed readable response, not first passing response or first observed headers' })
      const actual = await this.browserResponse('actual-first-public-get', first.captured, first.raw)
      try {
        expect(first.captured.response.status()).toBe(200)
        expect(actual.json).toEqual(expect.arrayContaining([expect.objectContaining({ id: saved.published.id, version: saved.published.version,
          state: 'PUBLISHED', title: saved.published.title, body: saved.published.body })]))
        saved.assertSaved()
      } catch (failure) { failures.push(failure) }
      try { await this.observe(staff, saved.published, saved.requestKey, 'post-public-get') }
      catch (failure) { observationFailure = failure }
      if (!failures.length && !observationFailure) {
        this.record('ui-start', { expectedPostId: saved.published.id, expectedVersion: saved.published.version })
        try {
          result = await consumeUi(saved.published, (event, data) => this.record('ui-' + event, data))
          uiPassed = true
          this.record('ui-passed', { expectedPostId: saved.published.id, meaning: 'original exact link/detail body/geometry assertions passed' })
        } catch (failure) { this.record('ui-failed', { expectedPostId: saved.published.id, failure: String(failure) }); throw failure }
      }
    } catch (failure) { failures.push(failure) }
    finally {
      natural.stopNewRequests()
      // Even a wholly cancelled/failed public navigation retains its already-captured producer responses.
      if (!saved) try { saved = await this.scope.run(() => this.consumeSave(capture)) } catch (failure) { failures.push(failure) }
      try { await natural.drain() } catch (failure) { failures.push(failure) }
      finally { natural.detach(); natural.flush() }
      try { natural.validate(saved?.published) } catch (failure) { failures.push(failure) }
    }
    this.record('publication-diagnostic-result', { passed: !failures.length && !observationFailure && uiPassed,
      uiPassed, failureCount: failures.length + (observationFailure ? 1 : 0), expectedPostId: saved?.published.id ?? null })
    if (observationFailure) {
      if (!failures.length) throw observationFailure
      const publicationFailure = failures.length === 1 ? failures[0]
        : new AggregateError(failures, 'Publication assertions and natural-response evidence failed', { cause: failures[0] })
      throw new AggregateError([publicationFailure, observationFailure], 'First public response assertion and post observation failed', { cause: observationFailure })
    }
    if (failures.length === 1) throw failures[0]
    if (failures.length) throw new AggregateError(failures, 'Publication assertions and natural-response evidence failed', { cause: failures[0] })
    return result as T
  }
}
