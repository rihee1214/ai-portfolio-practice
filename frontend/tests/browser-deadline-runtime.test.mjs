import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs'
import { randomUUID, createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { setTimeout, clearTimeout } from 'node:timers'
import process from 'node:process'

// Intentional failing Playwright cases run outside the product browser inventory.
// This Node test accepts them only when the raw report and side-effect evidence agree.
test('actual Playwright fixture enforces ten-second business deadlines and closes owned contexts', { timeout: 110_000 }, async () => {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  const frontend = resolve(root, 'frontend')
  const evidence = resolve(root, '.local-tools', 'browser-deadline-runtime-' + randomUUID())
  mkdirSync(evidence)
  const observed = []
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1')
    observed.push({ path: url.pathname, method: request.method, at: Date.now() })
    if (url.pathname === '/controlled-ready' || url.pathname === '/controlled-command-result') {
      // Deliberately controlled readiness/completion signals for this meta test only.
      // No production preparation helper contains this delay.
      const delay = url.pathname === '/controlled-ready' ? 10_500 : 5_300
      setTimeout(() => { response.writeHead(200); response.end('ready') }, delay)
    } else if (url.pathname === '/readiness-page') {
      response.writeHead(200, { 'content-type': 'text/html' })
      response.end(`<main>Waiting for the controlled readiness signal</main><script>fetch('/controlled-ready').then(() => { document.querySelector('main').innerHTML = '<button>Ready command</button>' })</script>`)
    } else if (url.pathname === '/late-page') {
      response.writeHead(200, { 'content-type': 'text/html' })
      response.end(`<button onclick="fetch('/late-click',{method:'POST'})">Forbidden late click</button>`)
    } else if (url.pathname === '/bound-route-page') {
      response.writeHead(200, { 'content-type': 'text/html' })
      response.end(`<button onclick="fetch('/bound-result').then(r=>r.json()).then(data=>document.querySelector('output').textContent=data.value)">Bound route command</button><output></output>`)
    } else if (url.pathname === '/deferred-route-page') {
      response.writeHead(200, { 'content-type': 'text/html' })
      response.end(`<button onclick="fetch('/deferred-route').catch(()=>document.querySelector('output').textContent='Callback blocked')">Trigger old route</button><output></output>`)
    } else if (url.pathname === '/route-upstream' || url.pathname === '/route-upstream-fetch') {
      response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ value: 'Bound callback succeeded' }))
    } else { response.writeHead(200); response.end('ok') }
  })
  await new Promise(resolveListening => server.listen(0, '127.0.0.1', resolveListening))
  const origin = `http://127.0.0.1:${server.address().port}`
  const quote = value => JSON.stringify(value.replaceAll('\\', '/'))
  const proof = resolve(evidence, 'fixture-proof.ndjson')
  const spec = `
import { test, expect, operation, prepare, bindDeadline, currentDeadline, boundedFetch } from ${quote(resolve(frontend, 'auth-e2e/business-deadline'))}
import { appendFileSync } from 'node:fs'
const origin = ${JSON.stringify(origin)}
const proof = ${quote(proof)}
const record = (row: Record<string, unknown>) => appendFileSync(proof, JSON.stringify({ ...row, at: Date.now() }) + '\\n')

test('controlledReadinessBeyondTenSecondsStillHasFreshBusinessBudget', async ({ page }) => {
  const started = Date.now()
  await prepare('runtime controlled readiness', async deadline => {
    await page.goto(origin + '/readiness-page')
    await page.getByRole('button', { name: 'Ready command' }).waitFor({ timeout: deadline.remaining() })
  })
  expect(Date.now() - started).toBeGreaterThanOrEqual(10_000)
  await operation('runtime command after readiness', async () => {
    await page.getByRole('button', { name: 'Ready command' }).click()
    await expect(page.getByRole('button', { name: 'Ready command' })).toBeVisible()
  })
  record({ event: 'readinessPassed', elapsedMs: Date.now() - started })
})

test('separateBusinessCommandsCanAccumulateBeyondTenSeconds', async ({ page, request }) => {
  await prepare('runtime command page', async () => { await page.setContent('<button>Independent command</button>') })
  const started = Date.now()
  await operation('runtime first independent command', async () => {
    await page.getByRole('button', { name: 'Independent command' }).click()
    expect((await request.get(origin + '/controlled-command-result')).ok()).toBe(true)
  })
  await operation('runtime second independent command', async () => {
    await page.getByRole('button', { name: 'Independent command' }).click()
    expect((await request.get(origin + '/controlled-command-result')).ok()).toBe(true)
  })
  expect(Date.now() - started).toBeGreaterThanOrEqual(10_000)
  record({ event: 'separateCommandsPassed', elapsedMs: Date.now() - started })
})

test('actualTenSecondDeadlineFailsAndRejectsLateSideEffects', async ({ browser, context }) => {
  const extra = await browser.newContext()
  context.on('close', () => record({ event: 'defaultContextClosed' }))
  extra.on('close', () => record({ event: 'extraContextClosed' }))
  const page = await extra.newPage()
  await prepare('runtime late callback page', async () => { await page.goto(origin + '/late-page') })
  const button = await prepare('runtime captured locator array', async () => (await page.getByRole('button', { name: 'Forbidden late click' }).all())[0])
  let release!: () => void
  let done!: () => void
  const released = new Promise<void>(resolve => { release = resolve })
  const lateDone = new Promise<void>(resolve => { done = resolve })
  const started = Date.now()
  try {
    await operation('runtime actual ten second timeout', async () => {
      await released
      let clickBlocked = false, requestBlocked = false
      try { await button.click() } catch (error) { clickBlocked = /deadline/i.test(String(error)) }
      try { await extra.request.post(origin + '/late-request') } catch (error) { requestBlocked = /deadline/i.test(String(error)) }
      record({ event: 'lateCallbacks', clickBlocked, requestBlocked })
      done()
    })
  } finally {
    release()
    await lateDone
    record({ event: 'actualTimeoutObserved', elapsedMs: Date.now() - started })
  }
})

test('freshFixtureAfterFailedCommandStillExecutes', async ({ page }) => {
  await prepare('runtime fresh page', async () => { await page.setContent('<button>Fresh command</button>') })
  await operation('runtime fresh command', async () => {
    await page.getByRole('button', { name: 'Fresh command' }).click()
    await expect(page.getByRole('button', { name: 'Fresh command' })).toBeVisible()
  })
  record({ event: 'freshFixturePassed' })
})

test('boundRouteRetainsInitiatingCommandIdAndDeadlineForGuardedApiRawAndResponse', async ({ page, request }) => {
  await prepare('runtime bound route page', async () => { await page.goto(origin + '/bound-route-page') })
  await operation('runtime bound route initiating command', async owner => {
    await page.route('**/bound-result', bindDeadline(async route => {
      const callback = currentDeadline()
      expect(callback.id).toBe(owner.id)
      expect(callback.deadlineAt).toBe(owner.deadlineAt)
      await operation('runtime nested bound route IO', async nested => {
        expect(nested.id).toBe(owner.id)
        expect(nested.deadlineAt).toBe(owner.deadlineAt)
        const response = await request.get(origin + '/route-upstream')
        const raw = await response.json()
        const fetchResponse = await boundedFetch(origin + '/route-upstream-fetch')
        const fetchRaw = await fetchResponse.json()
        expect(response.status()).toBe(200)
        expect(raw.value).toBe('Bound callback succeeded')
        expect(fetchResponse.status).toBe(200)
        expect(fetchRaw.value).toBe(raw.value)
        await route.fulfill({ status: response.status(), contentType: 'application/json', body: JSON.stringify(raw) })
        record({ event: 'boundRoutePassed', ownerId: owner.id, ownerDeadlineAt: owner.deadlineAt,
          callbackId: callback.id, callbackDeadlineAt: callback.deadlineAt, nestedId: nested.id,
          nestedDeadlineAt: nested.deadlineAt, responseStatus: response.status(), rawValue: raw.value,
          fetchStatus: fetchResponse.status, fetchRawValue: fetchRaw.value })
      })
    }))
    const response = page.waitForResponse(value => value.url() === origin + '/bound-result')
    await page.getByRole('button', { name: 'Bound route command' }).click()
    expect((await response).status()).toBe(200)
    await expect(page.locator('output')).toHaveText('Bound callback succeeded')
  })
})

test('closedBoundRouteCannotAdoptAnotherIndependentCommandBudget', async ({ page, request }) => {
  await prepare('runtime deferred route page', async () => { await page.goto(origin + '/deferred-route-page') })
  let ownerId = '', ownerDeadlineAt = 0, callbackWorkRuns = 0
  let rejectObserved!: (error: unknown) => void
  const rejected = new Promise<unknown>(resolve => { rejectObserved = resolve })
  let inactive!: () => Promise<void>
  await operation('runtime closed route owner', async owner => {
    ownerId = owner.id; ownerDeadlineAt = owner.deadlineAt
    inactive = bindDeadline(async () => {
      callbackWorkRuns++
      await request.post(origin + '/forbidden-bound-effect')
      await boundedFetch(origin + '/forbidden-bound-fetch')
    })
    const bound = bindDeadline(async route => {
      callbackWorkRuns++
      await request.post(origin + '/forbidden-bound-effect')
      await boundedFetch(origin + '/forbidden-bound-fetch')
      await route.fulfill({ status: 200, body: 'must never run' })
    })
    await page.route('**/deferred-route', async route => {
      try { await bound(route) }
      catch (error) {
        rejectObserved(error)
        // Meta-test cleanup only: abort the intercepted browser request after the
        // bound callback rejects, so the test never leaves a pending route.
        await route.abort('blockedbyclient')
      }
    })
  })
  let inactiveError: unknown
  try { await inactive() } catch (error) { inactiveError = error }
  expect(inactiveError).toMatchObject({ name: 'DeadlineExceeded', label: 'runtime closed route owner', deadlineAt: ownerDeadlineAt })
  await operation('runtime independent command after route owner closes', async independent => {
    expect(independent.id).not.toBe(ownerId)
    const capturedRequest = page.waitForRequest(value => value.url() === origin + '/deferred-route')
    await page.getByRole('button', { name: 'Trigger old route' }).click()
    await capturedRequest
    const error = await rejected
    expect(error).toMatchObject({ name: 'DeadlineExceeded', label: 'runtime closed route owner', deadlineAt: ownerDeadlineAt })
    expect(callbackWorkRuns).toBe(0)
    expect(currentDeadline().id).toBe(independent.id)
    await expect(page.locator('output')).toHaveText('Callback blocked')
    record({ event: 'closedBoundRouteRejected', ownerId, ownerDeadlineAt, independentId: independent.id,
      independentDeadlineAt: independent.deadlineAt, errorDeadlineAt: (error as { deadlineAt: number }).deadlineAt,
      callbackWorkRuns, inactiveRejected: true, independentStillActive: currentDeadline().id === independent.id })
  })
})
`
  const report = resolve(evidence, 'raw-playwright-report.json')
  const config = `import { defineConfig } from ${quote(resolve(frontend, 'node_modules/@playwright/test/index.mjs'))}
export default defineConfig({ testDir: ${quote(evidence)}, testMatch: 'runtime.spec.ts', workers: 1, retries: 0, timeout: 0,
  outputDir: ${quote(resolve(evidence, 'browser-results'))}, reporter: [['json', { outputFile: ${quote(report)} }]],
  use: { browserName: 'chromium', headless: true, trace: 'off' } })
`
  writeFileSync(resolve(evidence, 'runtime.spec.ts'), spec)
  writeFileSync(resolve(evidence, 'playwright.config.ts'), config)
  const env = { ...process.env, A3_EVIDENCE: evidence, PLAYWRIGHT_BROWSERS_PATH: resolve(root, '.local-tools/playwright') }
  // The product config sets its own mandatory inventory. This isolated meta config
  // contains intentional failures and imports the same fixture without product tests.
  delete env.A3_DEADLINE_INVENTORY
  const sourceFiles = ['frontend/auth-e2e/business-deadline.ts', 'frontend/auth-e2e/deadline-core.ts', 'frontend/auth-e2e/deadline-evidence.ts', 'frontend/tests/browser-deadline-runtime.test.mjs']
  const sourceHashes = () => sourceFiles.map(path => ({ path, sha256: createHash('sha256').update(readFileSync(resolve(root, path))).digest('hex') }))
  const inputs = sourceHashes()
  writeFileSync(resolve(evidence, 'inputs.json'), JSON.stringify(inputs, null, 2))
  try {
    const child = spawn(process.execPath, [resolve(frontend, 'node_modules/@playwright/test/cli.js'), 'test', '--config=' + resolve(evidence, 'playwright.config.ts')],
      { cwd: frontend, env, stdio: ['ignore', 'pipe', 'pipe'] })
    child.stdout.on('data', bytes => appendFileSync(resolve(evidence, 'child.txt'), bytes))
    child.stderr.on('data', bytes => appendFileSync(resolve(evidence, 'child.txt'), bytes))
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      if (process.platform === 'win32') { spawn('taskkill', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true }) }
      else { child.kill('SIGKILL') }
    }, 90_000)
    const result = await new Promise((resolveExit, reject) => {
      child.once('error', reject)
      child.once('close', (code, signal) => resolveExit({ code, signal }))
    }).finally(() => clearTimeout(timer))
    writeFileSync(resolve(evidence, 'child-result.json'), JSON.stringify({ ...result, timedOut }, null, 2))
    assert.equal(timedOut, false, 'meta child must finish within its infrastructure budget: ' + evidence)
    assert.equal(result.code, 1, 'only the intentional deadline case must fail: ' + evidence)
    const raw = JSON.parse(readFileSync(report, 'utf8'))
    assert.equal(raw.stats.expected, 5)
    assert.equal(raw.stats.unexpected, 1)
    assert.equal(raw.stats.skipped, 0)
    assert.equal(raw.stats.flaky, 0)
    assert.deepEqual(raw.errors, [])
    const specs = []
    const visit = suites => { for (const suite of suites ?? []) { specs.push(...(suite.specs ?? [])); visit(suite.suites) } }
    visit(raw.suites)
    assert.equal(specs.length, 6)
    const failed = specs.filter(item => !item.ok)
    assert.deepEqual(failed.map(item => item.title), ['actualTenSecondDeadlineFailsAndRejectsLateSideEffects'])
    assert.match(JSON.stringify(failed[0].tests[0].results[0].errors), /Deadline exceeded: runtime actual ten second timeout/)
    const lines = file => readFileSync(file, 'utf8').trim().split('\n').map(line => JSON.parse(line))
    const events = lines(resolve(evidence, 'browser-progress.ndjson'))
    const business = events.filter(event => event.event === 'end' && event.name === 'runtime actual ten second timeout')
    assert.equal(business.length, 1)
    assert.equal(business[0].deadlineAt - business[0].startedAt, 10_000)
    assert.equal(business[0].status, 'failed')
    assert.ok(business[0].elapsedMs >= 9_950 && business[0].elapsedMs < 15_000)
    const rows = lines(proof)
    const one = event => { const matches = rows.filter(row => row.event === event); assert.equal(matches.length, 1, event); return matches[0] }
    assert.ok(one('readinessPassed').elapsedMs >= 10_000)
    assert.ok(one('separateCommandsPassed').elapsedMs >= 10_000)
    assert.ok(one('actualTimeoutObserved').elapsedMs >= 9_950)
    assert.equal(one('lateCallbacks').clickBlocked, true)
    assert.equal(one('lateCallbacks').requestBlocked, true)
    one('defaultContextClosed'); one('extraContextClosed'); one('freshFixturePassed')
    const boundRoute = one('boundRoutePassed')
    assert.equal(boundRoute.callbackId, boundRoute.ownerId)
    assert.equal(boundRoute.nestedId, boundRoute.ownerId)
    assert.equal(boundRoute.callbackDeadlineAt, boundRoute.ownerDeadlineAt)
    assert.equal(boundRoute.nestedDeadlineAt, boundRoute.ownerDeadlineAt)
    assert.equal(boundRoute.responseStatus, 200)
    assert.equal(boundRoute.rawValue, 'Bound callback succeeded')
    assert.equal(boundRoute.fetchStatus, 200)
    assert.equal(boundRoute.fetchRawValue, boundRoute.rawValue)
    const closedRoute = one('closedBoundRouteRejected')
    assert.notEqual(closedRoute.independentId, closedRoute.ownerId)
    assert.equal(closedRoute.errorDeadlineAt, closedRoute.ownerDeadlineAt)
    assert.equal(closedRoute.callbackWorkRuns, 0)
    assert.equal(closedRoute.inactiveRejected, true)
    assert.equal(closedRoute.independentStillActive, true)
    const owner = events.find(event => event.event === 'start' && event.id === boundRoute.ownerId)
    assert.equal(owner?.deadlineAt, boundRoute.ownerDeadlineAt)
    assert.equal(owner?.deadlineAt - owner?.startedAt, 10_000)
    assert.equal(events.filter(event => event.name === 'runtime nested bound route IO').length, 0, 'bound nested work must inherit the original scope without creating another budget')
    assert.equal(observed.filter(row => row.path === '/route-upstream').length, 1)
    assert.equal(observed.filter(row => row.path === '/route-upstream-fetch').length, 1)
    assert.equal(observed.filter(row => row.path === '/forbidden-bound-effect' || row.path === '/forbidden-bound-fetch' || row.path === '/deferred-route' || row.path === '/bound-result').length, 0)
    assert.equal(observed.filter(row => row.path === '/late-click' || row.path === '/late-request').length, 0)
    assert.equal(events.filter(event => event.event === 'end' && event.kind === 'cleanup' && event.status === 'passed').length, 6)
    assert.deepEqual(sourceHashes(), inputs, 'the runtime helper source must stay unchanged during the child run')
    writeFileSync(resolve(evidence, 'meta-result.json'), JSON.stringify({ status: 'passed', assertions: 'real ten second failure; late side effects blocked; owned contexts closed; readiness and cumulative independent operations exceed ten seconds; real route callback API/raw/fulfill inherits original id/deadline; closed callback rejected without active scope and during an independent command', evidence }, null, 2))
  } finally {
    writeFileSync(resolve(evidence, 'server-observations.json'), JSON.stringify(observed, null, 2))
    server.closeAllConnections()
    await new Promise(resolveClosed => server.close(resolveClosed))
  }
})
