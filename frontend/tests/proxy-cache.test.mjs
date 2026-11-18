import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { Agent, createServer as createHttpServer } from 'node:http'
import { connect } from 'node:net'
import { createServer, resolveConfig } from 'vite'
import { proxyViteConfig } from './proxyViteConfig.mjs'
import { rawGet } from './proxyHttp.mjs'

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
      const port = server.httpServer.address().port
      t.diagnostic(`owned proxy HTTP listener: 127.0.0.1:${port}`)
      const response = await rawGet(port, '/account/orders')
      assert.equal(response.status, 200)
      assert.match(response.body, /id="root"/)
    }))
    for (const result of results) if (result.status === 'rejected') throw result.reason
  } finally {
    await Promise.all(servers.map(server => server.close()))
  }
})

test('cache HTML observation keeps native HTTP independent of Fetch forbidden ports', async t => {
  let requests = 0
  const server = createHttpServer((req, response) => {
    requests++
    assert.equal(req.url, '/account/orders')
    assert.equal(req.headers.host, '127.0.0.1:6000')
    response.writeHead(200, {'Content-Type':'text/html'})
    response.end('<div id="root"></div>')
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  const agent = new Agent()
  agent.createConnection = () => connect({host:'127.0.0.1',port})
  try {
    t.diagnostic(`controlled requested port: 6000; owned native HTTP transport: 127.0.0.1:${port}`)
    await assert.rejects(fetch('http://127.0.0.1:6000/account/orders'), error => error.cause?.message === 'bad port')
    assert.equal(requests, 0, 'Fetch refusal must occur before any owned HTTP request')
    const result = await rawGet(6000, '/account/orders', {agent})
    assert.equal(result.status, 200)
    assert.match(result.body, /id="root"/)
    assert.equal(requests, 1)
  } finally {
    agent.destroy()
    await new Promise(resolve => server.close(resolve))
  }
})
