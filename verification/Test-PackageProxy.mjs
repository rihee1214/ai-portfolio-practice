import assert from 'node:assert/strict'
import http from 'node:http'
import https from 'node:https'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { PackageExecution, terminateTree } from './package-execution.mjs'
const root = resolve(import.meta.dirname, '..')
const folder = resolve(root, process.argv[2] ?? `.local-tools/package-proxy-${randomUUID()}`)
assert.ok(folder.startsWith(resolve(root, '.local-tools') + '\\'))
await mkdir(folder)
const execution = new PackageExecution(folder)
let calls = 0, container, port, outcome
const server = http.createServer((request, response) => {
  if (request.url.startsWith('/api/disconnect')) { calls++; request.socket.destroy(); return }
  if (request.url === '/api/stall') return
  if (request.url === '/api/partial') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '1000' })
    response.write('{"incomplete":'); setTimeout(() => response.destroy(), 20); return
  }
  if (request.url.startsWith('/api/status/')) {
    const status = Number(request.url.split('/').at(-1))
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ error: `EXACT_${status}` })); return
  }
  if (request.url.startsWith('/actuator/health')) {
    const down = request.url.includes('down')
    response.writeHead(down ? 503 : 200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ status: down ? 'DOWN' : 'UP' })); return
  }
  let body = ''
  request.on('data', part => { body += part })
  request.on('end', () => {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
      'Set-Cookie': ['SESSION=one; Secure; HttpOnly; SameSite=Lax; Path=/', 'GUEST=two; Secure; HttpOnly; SameSite=Lax; Path=/'] })
    response.end(JSON.stringify({ url: request.url, method: request.method, body, headers: request.headers }))
  })
})
const portServer = http.createServer()
const password = randomUUID()
const basic = 'Basic ' + Buffer.from('reviewer:' + password).toString('base64')
const checks = []
function request(path, { method = 'GET', body, gate = true, headers = {} } = {}) {
  return execution.request({ protocol: 'https:', hostname: 'localhost', port, path, method,
    rejectUnauthorized: false, headers: { ...(gate ? { Authorization: basic } : {}), ...headers } }, body)
}
async function check(name, work) {
  const started = performance.now()
  try {
    await execution.scope('operation', name, work)
    checks.push({ name, status: 'passed', elapsedMs: performance.now() - started })
  } catch (error) { checks.push({ name, status: 'failed', elapsedMs: performance.now() - started }); throw error }
}
let failure
try {
  await execution.scope('prepare', 'start isolated TLS proxy', async () => {
    await mkdir(resolve(folder, 'dist')); await mkdir(resolve(folder, 'dist/assets'))
    await writeFile(resolve(folder, 'dist/index.html'), '<!doctype html><title>Package contract</title>')
    await writeFile(resolve(folder, 'dist/assets/test-a1.js'), 'export const packageContract=true;')
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    await new Promise(resolve => portServer.listen(0, '127.0.0.1', resolve))
    port = portServer.address().port
    await new Promise(resolve => portServer.close(resolve))
    await execution.execute('C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe', [
      '-NoProfile', '-File', resolve(root, 'infra/p12-c/Start-LocalProxy.ps1'), '-Evidence', folder,
      '-ApiPort', String(server.address().port), '-HttpsPort', String(port), '-GatePassword', password, '-Dist', resolve(folder, 'dist')], { timeout: 60_000 })
    container = JSON.parse(await readFile(resolve(folder, 'proxy-owner.json'), 'utf8')).container
  })
  await check('Rendered package keeps internal roles and API-only media ownership', async () => {
    const allocation = JSON.parse(await readFile(resolve(folder, 'network-allocation.json'), 'utf8'))
    const inventory = JSON.parse(await readFile(resolve(folder, `network-inventory-${allocation.attempt}.json`), 'utf8'))
    const range = cidr => {
      const [address, prefix] = cidr.split('/'), octets = address.split('.').map(Number)
      const value = octets.reduce((number, octet) => number * 256 + octet, 0), size = 2 ** (32 - Number(prefix))
      const first = Math.floor(value / size) * size
      return [first, first + size - 1]
    }
    const selected = range(allocation.subnet)
    assert.match(allocation.subnet, /\/28$/)
    assert.ok(['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'].some(cidr => { const block = range(cidr); return selected[0] >= block[0] && selected[1] <= block[1] }))
    assert.equal(inventory.selected, allocation.subnet); assert.ok(inventory.networks.length); assert.ok(inventory.hostRoutes.length)
    for (const cidr of inventory.excluded) {
      const excluded = range(cidr)
      assert.ok(selected[1] < excluded[0] || selected[0] > excluded[1], 'Allocated subnet overlaps recorded Docker/host route: ' + cidr)
    }
    const rendered = await execution.execute('docker', ['compose', '--file', resolve(root, 'infra/p12-c/compose.yaml'), 'config', '--format', 'json'], {
      timeout: 10_000, env: { ...process.env, PACKAGE_PROJECT: 'p12c-config-' + randomUUID().slice(0,8),
        PACKAGE_HTTPS_PORT: String(port), PACKAGE_DIST: resolve(folder,'dist'), PACKAGE_CONFIG: resolve(folder,'proxy-config'),
        PACKAGE_SECRETS: resolve(folder,'proxy-secrets'), PACKAGE_APP_IMAGE: 'sha256:' + 'a'.repeat(64),
        PACKAGE_PUBLIC_ORIGIN: 'https://localhost', PACKAGE_MEDIA: resolve(folder,'synthetic-media'),
        PACKAGE_DB_NAME: 'synthetic_config_only', PACKAGE_DB_USER: 'synthetic_app',
        PACKAGE_DB_PASSWORD: 'synthetic-config-placeholder', PACKAGE_DB_ADMIN_PASSWORD: 'separate-synthetic-config-placeholder' } })
    const compose = JSON.parse(rendered.stdout), { api, worker, postgres, proxy } = compose.services
    assert.equal(api.image,worker.image); assert.equal(api.environment.SERVER_ADDRESS,'0.0.0.0')
    assert.equal(api.environment.MARKETPLACE_RUNTIME_MODE,'public-demo'); assert.equal(worker.environment.MARKETPLACE_RUNTIME_MODE,'public-demo')
    assert.equal(api.environment.SPRING_PROFILES_ACTIVE,'db'); assert.equal(worker.environment.SPRING_PROFILES_ACTIVE,'db,worker')
    assert.ok(!api.ports && !worker.ports && !postgres.ports)
    assert.equal(proxy.ports.length,1); assert.equal(proxy.ports[0].host_ip,'127.0.0.1')
    assert.equal(api.volumes.filter(v=>v.target==='/media').length,1); assert.equal(worker.volumes,undefined)
    assert.equal(worker.environment.MEDIA_ROOT,undefined); assert.equal(postgres.environment.POSTGRES_USER,'postgres')
    assert.notEqual(postgres.environment.APP_USER,'postgres'); assert.equal(compose.networks.data.internal,true)
    assert.deepEqual(Object.keys(worker.networks),['data']); assert.deepEqual(Object.keys(proxy.networks),['edge'])
    await writeFile(resolve(folder,'compose-rendered.json'),JSON.stringify(compose,null,2))
  })
  await check('TLS invitation and approved hostname', async () => {
    assert.equal((await request('/', { gate: false })).status, 401)
    assert.equal((await request('/', { headers: { Host: 'unapproved.invalid' } })).status, 421)
    assert.equal((await request('/')).status, 200)
  })
  await check('SPA methods assets MIME cache and exact API namespace', async () => {
    assert.equal((await request('/orders/deep-link')).status, 200)
    assert.equal((await request('/orders/deep-link', { method: 'POST' })).status, 403)
    const asset = await request('/assets/test-a1.js')
    assert.equal(asset.status, 200); assert.match(asset.headers['content-type'], /javascript/)
    assert.match(asset.headers['cache-control'], /immutable/)
    assert.equal((await request('/assets/absent.js')).status, 404)
    assert.match((await request('/')).headers['cache-control'], /no-cache/)
    assert.match((await request('/apix')).body, /Package contract/)
  })
  await check('Raw path aliases never reach normalized API or management', async () => {
    for (const path of ['/ACTUATOR/health','/actuator/env','/actuator/health/db','/%61ctuator/env','/a%63tuator/env',
      '/api/../actuator/env','/api//auth','/api/%2Fauth','/api/%5cauth','/API/auth','/a%70i/auth','/api/%ZZ','/api/foo;bar','/api/%252fadmin']) {
      const response = await request(path)
      assert.ok([400,404].includes(response.status), path)
      assert.match(response.headers['content-type'], /application\/json/, path)
      assert.match(response.headers['cache-control'], /no-store/, path)
    }
  })
  await check('Original API status JSON and exact health UP DOWN contract', async () => {
    for (const status of [401,403,404,409,500,503]) {
      const response = await request('/api/status/' + status)
      assert.equal(response.status, status); assert.match(response.headers['content-type'], /application\/json/)
      assert.deepEqual(JSON.parse(response.body), { error: `EXACT_${status}` }); assert.match(response.headers['cache-control'], /no-store/)
    }
    for (const [path,status,body] of [['/actuator/health',200,'UP'],['/actuator/health?down=1',503,'DOWN']]) {
      const response = await request(path); assert.equal(response.status,status); assert.deepEqual(JSON.parse(response.body),{status:body})
    }
  })
  await check('Methods body query CSRF multiple cookies and untrusted forwarding', async () => {
    const response = await request('/api/echo?q=%EC%84%9C%EC%9A%B8', { method: 'POST', body: '{"value":"original"}',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'original-csrf', Forwarded: 'host=evil',
        'X-Forwarded-For': '8.8.8.8', 'X-Forwarded-Proto': 'http', 'X-Forwarded-Host': 'evil.invalid',
        'X-Forwarded-Port': '80', 'X-Forwarded-Prefix': '/untrusted', 'X-Forwarded-Ssl': 'off' } })
    const upstream = JSON.parse(response.body)
    assert.equal(upstream.url,'/api/echo?q=%EC%84%9C%EC%9A%B8'); assert.equal(upstream.method,'POST'); assert.equal(upstream.body,'{"value":"original"}')
    assert.equal(upstream.headers['x-csrf-token'],'original-csrf'); assert.equal(upstream.headers.forwarded,undefined)
    assert.notEqual(upstream.headers['x-forwarded-for'],'8.8.8.8'); assert.equal(upstream.headers['x-forwarded-proto'],'https')
    assert.equal(upstream.headers['x-forwarded-host'],'localhost'); assert.equal(upstream.headers['x-forwarded-port'],String(port))
    assert.equal(upstream.headers['x-forwarded-prefix'],undefined); assert.equal(upstream.headers['x-forwarded-ssl'],undefined)
    assert.equal(upstream.headers.authorization,undefined); assert.equal(response.headers['set-cookie'].length,2)
    assert.match(response.headers['set-cookie'][0], /Secure; HttpOnly; SameSite=Lax; Path=\//)
  })
  await check('Upstream failure timeout partial body and no POST replay', async () => {
    const disconnected = await request('/api/disconnect?emailToken=synthetic-private-query', { method: 'POST', body: 'one effect only',
      headers: { Cookie: 'SESSION=synthetic-private-cookie' } })
    assert.equal(disconnected.status,502); assert.equal(calls,1); assert.deepEqual(JSON.parse(disconnected.body),{error:'UPSTREAM_UNAVAILABLE'})
    const timeout = await request('/api/stall'); assert.equal(timeout.status,504); assert.deepEqual(JSON.parse(timeout.body),{error:'UPSTREAM_TIMEOUT'})
    await assert.rejects(request('/api/partial'))
  })
  await check('Request limit is API JSON 413 without forwarding or media alias', async () => {
    const response = await request('/api/upload',{method:'POST',body:Buffer.alloc(6*1024*1024+1),headers:{'Content-Type':'multipart/form-data; boundary=limit'}})
    assert.equal(response.status,413); assert.deepEqual(JSON.parse(response.body),{error:'PAYLOAD_TOO_LARGE'})
  })
} catch (error) { failure = error }
finally {
  const ownedContainer = async () => {
    if (container) return container
    try {
      const plan = JSON.parse(await readFile(resolve(folder, 'proxy-plan.json'), 'utf8'))
      assert.match(plan.name, /^p12c-[0-9a-f]{32}$/)
      return plan.name
    } catch (error) { if (error.code === 'ENOENT') return undefined; throw error }
  }
  outcome = await execution.finalize(failure, [
    { name: 'close owned HTTP requests and servers', work: () => {
      const closedRequests = execution.closeRequests()
      for (const owned of [server, portServer]) owned.closeAllConnections()
      return Promise.all([closedRequests, ...[server, portServer].map(owned => owned.listening ? new Promise((accept, reject) => owned.close(error => error ? reject(error) : accept())) : Promise.resolve())])
    } },
    { name: 'terminate owned command trees', work: scope => Promise.all([...execution.children].map(child => terminateTree(child, Math.min(2_000, scope.remaining())))) },
    { name: 'stop owned proxy container', work: async () => {
      const owned = await ownedContainer()
      if (owned) await execution.execute('docker', ['stop', '--timeout', '5', owned], { timeout: 10_000 })
    } },
    { name: 'preserve safe proxy logs', work: async () => {
      const owned = await ownedContainer()
      if (!owned) return
      const logs = await execution.execute('docker', ['logs', owned], { timeout: 10_000 })
      await writeFile(resolve(folder, 'nginx.log'), String(logs.stdout) + String(logs.stderr))
      assert.ok(!String(logs.stdout).includes('synthetic-private-query') && !String(logs.stderr).includes('synthetic-private-query'), 'Query token leaked in proxy logs')
      assert.ok(!String(logs.stdout).includes('synthetic-private-cookie') && !String(logs.stderr).includes('synthetic-private-cookie'), 'Cookie leaked in proxy logs')
    } },
  ], { checks, container })
}
if (outcome.status === 'passed') console.log(`PASS package proxy: ${checks.length} contract groups; ${folder}`)
else { process.exitCode = 1; console.error('Package proxy failed; inspect result.json for the original failure and independent cleanup outcomes') }
