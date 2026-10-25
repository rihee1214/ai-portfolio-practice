import test from 'node:test'
import assert from 'node:assert/strict'
import { withPhoneResources } from '../../verification/phone-resources.mjs'

for (const failure of ['listen', 'launch', 'run', 'browser-close']) {
  test(`phone probe cleans owned resources after ${failure} failure`, async () => {
    const closed = []
    const server = { listen: async () => { if (failure === 'listen') throw Error('listen') }, close: async () => { closed.push('server') } }
    const browser = { close: async () => { closed.push('browser'); if (failure === 'browser-close') throw Error('close') } }
    await assert.rejects(withPhoneResources(server, async () => { if (failure === 'launch') throw Error('launch'); return browser }, async () => { if (failure === 'run') throw Error('run') }))
    assert.deepEqual(closed, failure === 'listen' || failure === 'launch' ? ['server'] : ['browser', 'server'])
  })
}
