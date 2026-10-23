import { test } from 'node:test'
import assert from 'node:assert/strict'
import { interpretHealth, createHealthCheck } from '../src/api/health.ts'

for (const [result, expected] of [
  [{ kind: 'success', status: 200, data: { status: 'UP' } }, 'healthy'],
  [{ kind: 'success', status: 201, data: { status: 'UP' } }, 'invalid'],
  [{ kind: 'success', status: 200, data: { status: 'UNKNOWN' } }, 'unhealthy'],
  [{ kind: 'success', status: 200, data: { status: 'DOWN' } }, 'unhealthy'],
  [{ kind: 'http', status: 503, data: { status: 'DOWN' } }, 'unhealthy'],
  [{ kind: 'http', status: 503, data: { status: 'OUT_OF_SERVICE' } }, 'unhealthy'],
  [{ kind: 'http', status: 503, data: { status: 'UP' } }, 'connection-error'],
  [{ kind: 'http', status: 502 }, 'connection-error'],
  [{ kind: 'network' }, 'connection-error'],
  [{ kind: 'invalid-response', status: 200 }, 'invalid'],
  [{ kind: 'success', status: 200, data: null }, 'invalid'],
  [{ kind: 'success', status: 200, data: { status: 7 } }, 'invalid'],
  [{ kind: 'cancelled' }, 'cancelled'],
]) test(`health ${JSON.stringify(result)} => ${expected}`, () => {
  assert.equal(typeof interpretHealth, 'function')
  assert.equal(interpretHealth(result), expected)
})

test('new request wins even when the cancelled transport resolves late; dispose prevents updates', async () => {
  assert.equal(typeof createHealthCheck, 'function')
  const pending = [], states = []
  const check = createHealthCheck(state => states.push(state), signal => new Promise(resolve => pending.push({ signal, resolve })))
  const first = check.start()
  const second = check.start()
  assert.equal(pending[0].signal.aborted, true)
  assert.equal(states.at(-1), 'loading')
  pending[1].resolve({ kind: 'success', status: 200, data: { status: 'UP' } })
  await second
  pending[0].resolve({ kind: 'http', status: 503, data: { status: 'DOWN' } })
  await first
  assert.equal(states.at(-1), 'healthy')
  const third = check.start()
  check.cancel()
  pending[2].resolve({ kind: 'success', status: 200, data: { status: 'UP' } })
  await third
  assert.equal(states.at(-1), 'cancelled')
  const fourth = check.start()
  check.dispose()
  const count = states.length
  pending[3].resolve({ kind: 'success', status: 200, data: { status: 'UP' } })
  await fourth
  assert.equal(states.length, count)
  assert.equal(pending[3].signal.aborted, true)
})
