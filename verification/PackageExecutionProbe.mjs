import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { appendFileSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { PackageExecution } from './package-execution.mjs'

const folder = resolve(process.argv[2]), mode = process.argv[3]
await mkdir(folder)
if (mode === 'stalled-child' || mode === 'malformed-child') {
  const server = http.createServer()
  await new Promise(accept => server.listen(0, '127.0.0.1', accept))
  const descendant = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { windowsHide: true, stdio: 'ignore' })
  writeFileSync(resolve(folder, 'owned-stall.json'), JSON.stringify({ pid: process.pid, descendant: descendant.pid, port: server.address().port }))
  const startedAt = Date.now()
  appendFileSync(resolve(folder, 'proxy-progress.ndjson'), JSON.stringify({ event: 'start', id: randomUUID(), kind: 'operation',
    name: 'synthetic unresponsive child', startedAt, ...(mode === 'malformed-child' ? {} : { deadlineAt: startedAt + 80 }), pid: process.pid }) + '\n')
  // Only the regression fixture stalls. Its independent parent must terminate this tree.
  while (true) {}
}

const execution = new PackageExecution(folder, { budgets: { prepare: 1_000, operation: 200, cleanup: 500 } })
const observations = { received: 0, completed: 0, chunks: 0, closed: false }
const timers = new Set()
const server = http.createServer((request, response) => {
  observations.received++
  if (mode === 'trickle') {
    response.writeHead(200, { 'Content-Type': 'text/plain' })
    const timer = setInterval(() => { observations.chunks++; response.write('more') }, 5)
    timers.add(timer); response.once('close', () => { clearInterval(timer); timers.delete(timer) })
  } else {
    const timer = setTimeout(() => { timers.delete(timer); response.end('complete') }, 80)
    timers.add(timer); response.once('close', () => { clearTimeout(timer); timers.delete(timer) })
  }
})
let failure
try {
  await execution.scope('prepare', 'prepare synthetic owned server', () => new Promise(accept => server.listen(0, '127.0.0.1', accept)))
  await execution.scope('operation', 'synthetic ' + mode, async () => {
    if (mode === 'cleanup-failure') await execution.execute(process.execPath, ['-e', 'process.exit(7)'], { timeout: 1_000 })
    else if (mode === 'cumulative' || mode === 'trickle') {
      for (let attempt = 0; attempt < 5; attempt++) {
        const response = await execution.request({ protocol: 'http:', hostname: '127.0.0.1', port: server.address().port, path: '/' })
        assert.equal(response.status, 200); observations.completed++
      }
    } else throw new Error('Unknown synthetic probe')
  })
} catch (error) { failure = error }
if (mode === 'cleanup-failure') await mkdir(resolve(folder, 'result.json'))
const result = await execution.finalize(failure, [
  ...(mode === 'cleanup-failure' ? [{ name: 'synthetic failed log write', work: () => writeFile(folder, 'must fail: directory is not a file') }] : []),
  { name: 'close every owned socket despite cleanup failures', work: () => {
    const closedRequests = execution.closeRequests(); server.closeAllConnections()
    for (const timer of timers) { clearTimeout(timer); clearInterval(timer) }
    return Promise.all([closedRequests, new Promise((accept, reject) => server.close(error => {
      observations.closed = !error; error ? reject(error) : accept()
    }))])
  } },
], { mode, observations })
process.exitCode = result.status === 'passed' ? 0 : 1
