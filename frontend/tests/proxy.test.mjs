import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer as createHttpServer } from 'node:http'
import { createServer as createViteServer } from 'vite'
import { fileURLToPath } from 'node:url'
import { apiProxy } from '../vite/apiProxy.ts'

test('real Vite proxy preserves health/API errors, blocks other management paths before SPA fallback', async () => {
  assert.equal(typeof apiProxy, 'function', 'proxy plugin must exist')
  const upstream = createHttpServer((req, res) => {
    res.setHeader('Content-Type', 'application/json')
    res.statusCode = req.url.startsWith('/actuator/health') ? 503 : 404
    res.end(JSON.stringify({ status: 'DOWN' }))
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  const target = `http://127.0.0.1:${upstream.address().port}`
  let vite
  try {
    vite = await createViteServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, plugins: [apiProxy(target)], server: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' })
    await vite.listen()
    const base = `http://127.0.0.1:${vite.httpServer.address().port}`
    for (const path of ['/actuator/health', '/actuator/health?check=1']) {
      const response = await fetch(base + path)
      assert.equal(response.status, 503)
      assert.deepEqual(await response.json(), { status: 'DOWN' })
    }
    for (const path of ['/api', '/api/status', '/api/not-found']) {
      const response = await fetch(base + path, { headers: { Accept: 'text/html' } })
      assert.equal(response.status, 404)
      assert.match(response.headers.get('content-type'), /json/)
    }
    for (const path of ['/actuator', '/actuator/env', '/actuator/health/', '/actuator/health/liveness', '/actuator/healthXYZ', '/actuator%2fenv']) {
      const response = await fetch(base + path, { headers: { Accept: 'text/html' } })
      assert.equal(response.status, 404, path)
      assert.match(response.headers.get('content-type'), /json/, path)
      assert.deepEqual(await response.json(), { error: 'NOT_FOUND' }, path)
    }
    const spa = await fetch(base + '/account/orders', { headers: { Accept: 'text/html' } })
    assert.equal(spa.status, 200)
    assert.match(await spa.text(), /id="root"/)
    await new Promise(resolve => upstream.close(resolve))
    const offline = await fetch(base + '/actuator/health')
    assert.equal(offline.status, 502)
    assert.deepEqual(await offline.json(), { error: 'UPSTREAM_UNAVAILABLE' })
  } finally {
    await vite?.close()
    upstream.closeAllConnections()
    if (upstream.listening) await new Promise(resolve => upstream.close(resolve))
  }
})
