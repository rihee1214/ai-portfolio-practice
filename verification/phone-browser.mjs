import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'
import { withPhoneResources, phoneProbeFailure } from './phone-resources.mjs'
const require = createRequire(resolve('frontend/package.json'))
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('.local-tools/playwright')
const { chromium } = require('@playwright/test')
const [base, host, project] = process.argv.slice(2)
assert.match(host, /^127\.0\.0\.1:[0-9]+$/)
assert.match(project, /^demo-/)
const server = await createServer({ root: resolve('frontend'), configFile: false, server: { host: '127.0.0.1', port: 0 }, appType: 'custom' })
server.middlewares.use('/', (_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<html><body>Provider test fixture</body></html>') })
let stage = 'BROWSER_LISTEN'
try {
 await withPhoneResources(server, () => chromium.launch({ headless: true }), async browser => {
  const page = await browser.newPage()
  stage = 'BROWSER_NAVIGATION'
  await page.goto('http://127.0.0.1:' + server.httpServer.address().port)
  async function post(path, body) {
    stage = ({ '/issue': 'JAVA_ISSUE', '/exchange': 'JAVA_EXCHANGE', '/confirm': 'JAVA_CONFIRM', '/binding-issue': 'JAVA_BINDING_ISSUE', '/binding-exchange': 'JAVA_BINDING_EXCHANGE', '/binding-confirm': 'JAVA_BINDING_CONFIRM' })[path] ?? 'UNKNOWN'
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    return { status: response.status, body: await response.json() }
  }
  async function codeFor(session) {
    stage = 'EMULATOR_CODES'
    const response = await fetch('http://' + host + '/emulator/v1/projects/' + project + '/verificationCodes')
    const body = await response.json()
    const entry = body.verificationCodes.find(item => item.sessionInfo === session)
    assert.ok(entry, 'Emulator issued a code')
    return entry.code
  }
  const issued = await post('/issue', { phone: '01012345678' })
  assert.equal(issued.status, 200)
  const code = await codeFor(issued.body.session)
  const wrongCode = await post('/exchange', { id: issued.body.id, code: code === '000000' ? '111111' : '000000' })
  assert.equal(wrongCode.status, 400, 'server rejects incorrect OTP')
  const exchanged = await post('/exchange', { id: issued.body.id, code })
  assert.equal(exchanged.status, 200, 'server exchanges OTP exactly once')
  assert.equal((await post('/exchange', { id: issued.body.id, code })).status, 400, 'OTP exchange replay rejected')
  stage = 'BROWSER_SDK'
  const token = await page.evaluate(async ({ host, project, customToken }) => {
    const client = await import('/src/phone/FirebasePhoneClient.ts')
    return client.confirmPhoneCode(host, project, customToken)
  }, { host, project, customToken: exchanged.body.customToken })
  assert.equal(typeof token, 'string')
  const fake = await post('/confirm', { id: issued.body.id, code, token: 'verified:true' })
  assert.equal(fake.status, 400)
  const verified = await post('/confirm', { id: issued.body.id, code, token })
  assert.equal(verified.status, 200)
  assert.equal(verified.body.phone, '+821012345678')
  assert.ok(verified.body.authenticatedAt)
  assert.equal((await post('/confirm', { id: issued.body.id, code, token })).status, 400)
  // Gateway binding test: same phone and same second, independent of the provider cooldown policy.
  let previousToken = token
  for (let i = 0; i < 2; i++) {
    const fresh = await post('/binding-issue', { phone: '01012345678' })
    assert.equal(fresh.status, 200)
    const freshCode = await codeFor(fresh.body.session)
    const custom = await post('/binding-exchange', { session: fresh.body.session, code: freshCode })
    assert.equal(custom.status, 200)
    stage = 'BROWSER_SDK'
    const freshToken = await page.evaluate(async ({ host, project, customToken }) => {
      const client = await import('/src/phone/FirebasePhoneClient.ts')
      return client.confirmPhoneCode(host, project, customToken)
    }, { host, project, customToken: custom.body.customToken })
    assert.equal((await post('/binding-confirm', { session: fresh.body.session, token: previousToken })).status, 400, 'earlier same-phone purchase/recovery proof must not satisfy a new exchange')
    assert.equal((await post('/binding-confirm', { session: fresh.body.session, token: freshToken })).status, 200, 'only the exact exchange proof succeeds')
    previousToken = freshToken
  }
  const other = await post('/issue', { phone: '01099999999' })
  assert.equal(other.status, 200)
  const otherCode = await codeFor(other.body.session)
  const otherExchanged = await post('/exchange', { id: other.body.id, code: otherCode })
  assert.equal(otherExchanged.status, 200)
  stage = 'BROWSER_SDK'
  const otherToken = await page.evaluate(async ({ host, project, customToken }) => {
    const client = await import('/src/phone/FirebasePhoneClient.ts')
    return client.confirmPhoneCode(host, project, customToken)
  }, { host, project, customToken: otherExchanged.body.customToken })
  // Auth Emulator deliberately uses unsigned tokens. Mutate claims only in this local fixture.
  function claimsToken(patch) {
    const parts = otherToken.split('.')
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString())
    parts[1] = Buffer.from(JSON.stringify({ ...claims, ...patch })).toString('base64url')
    return parts.join('.')
  }
  for (const [reason, invalidToken] of [['different phone', token], ['different project', claimsToken({ aud: 'demo-other-project' })], ['expired', claimsToken({ exp: 1 })]]) {
    assert.equal((await post('/confirm', { id: other.body.id, code: otherCode, token: invalidToken })).status, 400, reason)
  }
  assert.equal((await post('/confirm', { id: other.body.id, code: otherCode, token: otherToken })).status, 200)
  assert.equal(await page.evaluate(() => localStorage.length), 0)
  stage = 'BROWSER_SDK'
  const rejected = await page.evaluate(async () => {
    const client = await import('/src/phone/FirebasePhoneClient.ts')
    try { await client.confirmPhoneCode('example.com:9099', 'real-project', 'x'); return false } catch { return true }
  })
  assert.equal(rejected, true)
  console.log('PASS browser SDK → emulator → Java verification; forged proof, wrong code, replay, nonlocal and persistence guards')
 })
} catch (error) {
  console.error('FAIL provider browser probe:', JSON.stringify(phoneProbeFailure(stage, error)))
  process.exitCode = 1
}
