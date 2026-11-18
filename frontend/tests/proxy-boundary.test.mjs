import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, dirname, basename } from 'node:path'
import { createServer as createViteServer, preview } from 'vite'
import { apiProxy } from '../vite/apiProxy.ts'
import { rawGet } from './proxyHttp.mjs'

for (const mode of ['dev', 'preview']) {
  test(`${mode}: API path aliases cannot escape into management routes or SPA fallback`, async t => {
    const seen = []
    const upstream = createServer((req, res) => {
      seen.push(req.url)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ received: req.url }))
    })
    const root = await mkdtemp(join(tmpdir(), 'p01-b4-boundary-'))
    await writeFile(join(root, 'index.html'), '<main>SPA fallback marker</main>')
    let vite
    try {
      await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
      const config = { root, configFile: false, plugins: [apiProxy(`http://127.0.0.1:${upstream.address().port}`)], logLevel: 'silent' }
      vite = mode === 'dev'
        ? await createViteServer({ ...config, server: { host: '127.0.0.1', port: 0 } })
        : await preview({ ...config, build: { outDir: '.' }, preview: { host: '127.0.0.1', port: 0 } })
      if (mode === 'dev') await vite.listen()
      const port = vite.httpServer.address().port

      for (const path of ['/api/../actuator/env', '/api/%2e%2e/actuator/health', '/api/%2e%2e%2factuator/env', '/api/..;/actuator/env', '/api/%252e%252e/actuator/env', '/api/..%5cactuator/env', '//actuator/env', '/%61ctuator/env', '/ACTUATOR/env', '/%61pi/missing', '/API/missing', '/api%2fmissing', '/actu%61tor/%zz']) {
        await t.test(path, async () => {
          const count = seen.length
          const response = await rawGet(port, path)
          assert.ok(response.status === 400 || response.status === 404, `${path}: expected rejection, got ${response.status} ${response.body.slice(0, 100)}`)
          assert.match(response.type ?? '', /json/, `${path} must return JSON`)
          assert.equal(seen.length, count, `${path} must not reach the upstream`)
        })
      }
      await t.test('ordinary API identifiers and exact health remain usable', async () => {
        for (const path of ['/api', '/api?probe=1', '/api/products/%ED%95%9C%EA%B8%80?name=a%2Fb', '/actuator/health?probe=1']) {
          const response = await rawGet(port, path)
          assert.equal(response.status, 200)
          assert.deepEqual(JSON.parse(response.body), { received: path })
        }
        for (const path of ['/account/orders', '/apiary', '/actuator-help']) {
          const response = await rawGet(port, path)
          assert.equal(response.status, 200)
          assert.match(response.body, /SPA fallback marker/)
        }
      })
    } finally {
      if (mode === 'dev') await vite?.close()
      else if (vite) { vite.httpServer.closeAllConnections(); await new Promise(resolve => vite.httpServer.close(resolve)) }
      upstream.closeAllConnections()
      if (upstream.listening) await new Promise(resolve => upstream.close(resolve))
      const cleanupPath = resolve(root)
      assert.equal(dirname(cleanupPath), resolve(tmpdir()))
      assert.ok(basename(cleanupPath).startsWith('p01-b4-boundary-'))
      await rm(cleanupPath, { recursive: true, force: true })
    }
  })
}
