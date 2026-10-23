import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'
import { setInterval, clearInterval } from 'node:timers'
import { performance } from 'node:perf_hooks'
/* global AbortSignal */
import { fileURLToPath } from 'node:url'
import { createServer as createViteServer, preview } from 'vite'
import { apiProxy } from '../vite/apiProxy.ts'
import { requestJson } from '../src/api/http.ts'

for (const mode of ['dev', 'preview']) {
  test(`${mode}: real partial responses terminate on upstream failure`, { concurrency: true }, async t => {
    const upstream = createServer(async (req, res) => {
      const scenario = new URL(req.url, 'http://localhost').searchParams.get('case')
      if (scenario === 'silent') return
      res.writeHead(200, { 'Content-Type': 'application/json' })
      if (scenario === 'healthy') { res.end('{"status":"UP"}'); return }
      res.write('{"status":')
      if (scenario === 'disconnect') { await delay(300); res.destroy(); return }
      if (scenario === 'stream') {
        const interval = setInterval(() => res.write(' '), 250)
        res.on('close', () => clearInterval(interval))
        await delay(12_000)
        clearInterval(interval)
        res.end('"UP"}')
      }
    })
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
    const config = { root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, plugins: [apiProxy(`http://127.0.0.1:${upstream.address().port}`)], logLevel: 'silent' }
    let vite
    try {
      vite = mode === 'dev'
        ? await createViteServer({ ...config, server: { host: '127.0.0.1', port: 0 } })
        : await preview({ ...config, preview: { host: '127.0.0.1', port: 0 } })
      if (mode === 'dev') await vite.listen()
      const base = `http://127.0.0.1:${vite.httpServer.address().port}/actuator/health`
      await Promise.all(['silent', 'stall', 'disconnect', 'stream'].map(scenario => t.test(scenario, { concurrency: true }, async () => {
        const start = performance.now()
        const result = await requestJson(`${base}?case=${scenario}`, { signal: AbortSignal.timeout(15_000) })
        const elapsed = Math.round(performance.now() - start)
        t.diagnostic(`${scenario}: ${elapsed}ms ${JSON.stringify(result)}`)
        if (scenario === 'silent') {
          assert.equal(result.kind, 'http'); assert.equal(result.status, 502)
          assert.deepEqual(result.data, { error: 'UPSTREAM_UNAVAILABLE' })
        } else if (scenario === 'stream') {
          assert.equal(result.kind, 'success'); assert.deepEqual(result.data, { status: 'UP' })
          assert.ok(elapsed >= 11_500, 'continuous progress may exceed the idle timeout')
        } else assert.equal(result.kind, 'network', 'incomplete upstream must fail transport, not wait for caller cancellation')
        if (scenario === 'silent' || scenario === 'stall') assert.ok(elapsed >= 9000 && elapsed < 14_000)
        if (scenario === 'disconnect') assert.ok(elapsed < 3000)
        const recovered = await requestJson(`${base}?case=healthy`)
        assert.deepEqual(recovered, { kind: 'success', status: 200, data: { status: 'UP' } })
      })))
      await t.test('caller cancels while reading body and can retry', async () => {
        const result = await requestJson(`${base}?case=stall`, { signal: AbortSignal.timeout(300) })
        assert.equal(result.kind, 'cancelled')
        assert.equal((await requestJson(`${base}?case=healthy`)).kind, 'success')
      })
    } finally {
      if (mode === 'dev') await vite?.close()
      else if (vite) { vite.httpServer.closeAllConnections(); await new Promise(resolve => vite.httpServer.close(resolve)) }
      upstream.closeAllConnections()
      await new Promise(resolve => upstream.close(resolve))
    }
  })
}
