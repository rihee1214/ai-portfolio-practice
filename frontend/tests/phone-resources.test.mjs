import test from 'node:test'
import assert from 'node:assert/strict'
import { withPhoneResources } from '../../verification/phone-resources.mjs'
import * as resources from '../../verification/phone-resources.mjs'

for (const failure of ['listen', 'launch', 'run', 'browser-close']) {
  test(`phone probe cleans owned resources after ${failure} failure`, async () => {
    const closed = []
    const server = { listen: async () => { if (failure === 'listen') throw Error('listen') }, close: async () => { closed.push('server') } }
    const browser = { close: async () => { closed.push('browser'); if (failure === 'browser-close') throw Error('close') } }
    await assert.rejects(withPhoneResources(server, async () => { if (failure === 'launch') throw Error('launch'); return browser }, async () => { if (failure === 'run') throw Error('run') }))
    assert.deepEqual(closed, failure === 'listen' || failure === 'launch' ? ['server'] : ['browser', 'server'])
  })
}
test('phone probe diagnostics identify network stage and cause without printing credentials', () => {
  const secret = 'sensitive-session-token-and-code'
  const error = new TypeError(secret, { cause: Object.assign(new Error(secret), { code: 'ECONNREFUSED', address: secret }) })
  const diagnostic = resources.phoneProbeFailure?.('JAVA_ISSUE', error)
  assert.deepEqual(diagnostic, { stage: 'JAVA_ISSUE', error: 'TypeError', causes: ['ECONNREFUSED'] })
  assert.equal(JSON.stringify(diagnostic).includes(secret), false)
})
test('phone probe diagnostics bound aggregate network causes and redact untrusted stage names and messages', () => {
  const secret='secret-token'
  const cause = Object.assign(new AggregateError(['ECONNRESET','ECONNREFUSED','ETIMEDOUT','EADDRINUSE','UND_ERR_SOCKET'].map(code => Object.assign(new Error(secret),{code})), secret),{code:secret})
  const error = new Error(secret, { cause })
  const diagnostic = resources.phoneProbeFailure?.(secret,error)
  assert.deepEqual(diagnostic, { stage: 'UNKNOWN', error: 'Error', causes: ['ECONNRESET','ECONNREFUSED','ETIMEDOUT','EADDRINUSE'] })
  assert.equal(diagnostic.causes.includes('UND_ERR_SOCKET'),false,'fifth aggregate cause is excluded')
  assert.equal(JSON.stringify(diagnostic).includes(secret), false)
})
