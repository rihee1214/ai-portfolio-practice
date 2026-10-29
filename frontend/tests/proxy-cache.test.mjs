import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { createServer, resolveConfig } from 'vite'
import { proxyViteConfig } from './proxyViteConfig.mjs'

test('concurrent proxy test servers own distinct caches outside the shared frontend cache', async t => {
  const configs = await Promise.all([proxyViteConfig('http://127.0.0.1:1'), proxyViteConfig('http://127.0.0.1:1')])
  const resolved = await Promise.all(configs.map(config => resolveConfig(config, 'serve')))
  assert.notEqual(resolve(resolved[0].cacheDir), resolve(resolved[1].cacheDir), 'each server must own a different effective Vite cache')
  for (const config of resolved) {
    assert.notEqual(resolve(config.cacheDir), resolve(config.root, 'node_modules/.vite'), 'test must never clear the shared development cache')
    t.diagnostic(`owned cache: ${config.cacheDir}`)
  }
  const servers = []
  try {
    const results = await Promise.allSettled(configs.map(async config => {
      const server = await createServer({ ...config, server: { host: '127.0.0.1', port: 0 } })
      servers.push(server)
      assert.equal(resolve(server.config.cacheDir), resolve(config.cacheDir))
      await server.listen()
      const response = await fetch(`http://127.0.0.1:${server.httpServer.address().port}/account/orders`, { headers: { Accept: 'text/html' } })
      assert.equal(response.status, 200)
      assert.match(await response.text(), /id="root"/)
    }))
    for (const result of results) if (result.status === 'rejected') throw result.reason
  } finally {
    await Promise.all(servers.map(server => server.close()))
  }
})
