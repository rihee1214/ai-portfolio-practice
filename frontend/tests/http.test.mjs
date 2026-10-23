import { test } from 'node:test'
import assert from 'node:assert/strict'

import { requestJson } from '../src/api/http.ts'

test('JSON request preserves path and sends Accept; returns unknown data with HTTP status', async () => {
  assert.equal(typeof requestJson, 'function', 'common JSON request must exist')
  let sent
  const result = await requestJson('/actuator/health', { fetcher: async (path, options) => {
    sent = { path, accept: new Headers(options.headers).get('Accept') }
    return new Response('{"status":"UP"}', { headers: { 'Content-Type': 'application/vnd.spring-boot.actuator.v3+json' } })
  } })
  assert.deepEqual(sent, { path: '/actuator/health', accept: 'application/json' })
  assert.deepEqual(result, { kind: 'success', status: 200, data: { status: 'UP' } })
})

for (const [name, response, kind, status] of [
  ['HTTP DOWN keeps parsed error body', () => new Response('{"status":"DOWN"}', { status: 503, headers: { 'Content-Type': 'application/json' } }), 'http', 503],
  ['HTML success is a contract error', () => new Response('<html>fallback</html>', { headers: { 'Content-Type': 'text/html' } }), 'invalid-response', 200],
  ['malformed JSON is a contract error', () => new Response('{', { headers: { 'Content-Type': 'application/json' } }), 'invalid-response', 200],
  ['204 has no JSON document', () => new Response(null, { status: 204 }), 'invalid-response', 204],
  ['malformed error JSON still preserves HTTP failure', () => new Response('{', { status: 503, headers: { 'Content-Type': 'application/json' } }), 'http', 503],
  ['empty proxy error keeps HTTP status', () => new Response('', { status: 502 }), 'http', 502],
  ['non-JSON HTTP error keeps status', () => new Response('<html>error</html>', { status: 404 }), 'http', 404],
]) test(name, async () => {
  assert.equal(typeof requestJson, 'function')
  const result = await requestJson('/api/example', { fetcher: async () => response() })
  assert.equal(result.kind, kind)
  assert.equal(result.status, status)
  if (name.startsWith('HTTP DOWN')) assert.deepEqual(result.data, { status: 'DOWN' })
})

test('network failure and caller cancellation are distinct', async () => {
  assert.equal(typeof requestJson, 'function')
  assert.equal((await requestJson('/api/example', { fetcher: async () => { throw new TypeError('offline') } })).kind, 'network')
  const controller = new AbortController()
  controller.abort()
  assert.equal((await requestJson('/api/example', { signal: controller.signal })).kind, 'cancelled')
})

test('cancellation during body read is not malformed JSON', async () => {
  assert.equal(typeof requestJson, 'function')
  const controller = new AbortController()
  let stream, reading, fetched = false
  const bodyReadStarted = new Promise(resolve => { reading = resolve })
  const body = new ReadableStream({ start(value) { stream = value }, pull() { reading() } }, { highWaterMark: 0 })
  const pending = requestJson('/api/example', { signal: controller.signal, fetcher: async () => {
    fetched = true
    return new Response(body, { headers: { 'Content-Type': 'application/json' } })
  } })
  await bodyReadStarted
  controller.abort()
  stream.error(new DOMException('cancelled', 'AbortError'))
  const result = await pending
  assert.equal(fetched, true, 'the request must reach fetch before body cancellation')
  assert.equal(result.kind, 'cancelled')
})

test('a JSON body interrupted by the connection is a network failure, not malformed JSON', async () => {
  const body = new ReadableStream({ pull(stream) { stream.error(new TypeError('connection reset')) } })
  const result = await requestJson('/api/example', { fetcher: async () => new Response(body, { headers: { 'Content-Type': 'application/json' } }) })
  assert.equal(result.kind, 'network')
})
