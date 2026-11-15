import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import net from 'node:net'
import { resolve } from 'node:path'
import { PackageExecution, alive } from './package-execution.mjs'
import { watchPackageChild } from './package-watchdog.mjs'

const folder = resolve(process.argv[2])
await mkdir(folder)
const execution = new PackageExecution(folder), cases = []
let failure
try {
  await execution.scope('prepare', 'deterministic isolated network allocator assertions', async () => {
    const output = await execution.execute('C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe',
      ['-NoProfile','-File',resolve(import.meta.dirname,'Test-ProxyNetwork.ps1')], { timeout: 10_000 })
    assert.match(output.stdout, /PASS proxy subnet allocator/)
  })
  for (const mode of ['cumulative', 'trickle', 'stalled-child', 'malformed-child', 'cleanup-failure']) {
    await execution.scope('operation', 'package execution regression: ' + mode, async () => {
      const probeFolder = resolve(folder, mode)
      const report = await watchPackageChild({ program: resolve(import.meta.dirname, 'PackageExecutionProbe.mjs'), folder: probeFolder,
        args: [mode], grace: 80, bootstrap: 2_000, idle: 500, poll: 10 })
      assert.equal(report.status, 'failed', 'Synthetic negative must not pass')
      assert.equal(report.currentChildAlive, false)
      const result = mode === 'cleanup-failure' ? report.childResult : JSON.parse(await readFile(resolve(probeFolder, 'execution/result.json'), 'utf8'))
      assert.equal(result.status, 'failed')
      if (mode === 'stalled-child' || mode === 'malformed-child') {
        assert.match(report.watchdogFailure.message, mode === 'stalled-child' ? /phase watchdog expired/ : /Invalid owned child phase evidence/)
        assert.equal(result.supervisorFallback, true)
        const owned = JSON.parse(await readFile(resolve(probeFolder, 'execution/owned-stall.json'), 'utf8'))
        assert.equal(alive(owned.pid), false); assert.equal(alive(owned.descendant), false)
        await new Promise((accept, reject) => {
          const socket = net.createConnection({ host: '127.0.0.1', port: owned.port })
          socket.setTimeout(500, () => { socket.destroy(); reject(new Error('Owned stalled socket remained open')) })
          socket.once('connect', () => { socket.destroy(); reject(new Error('Owned stalled server survived watchdog')) })
          socket.once('error', error => error.code === 'ECONNREFUSED' ? accept() : reject(error))
        })
      } else {
        assert.equal(result.observations.closed, true)
        assert.equal(result.remainingOwnedRequests, 0); assert.deepEqual(result.remainingOwnedProcesses, [])
        const operation = result.phaseEvents.find(event => event.event === 'end' && event.kind === 'operation')
        assert.equal(operation.status, 'failed'); assert.equal(operation.deadlineAt - operation.startedAt, 200)
        if (mode === 'cleanup-failure') {
          assert.equal(result.originalFailure.code, 7, 'Cleanup must preserve the original child failure')
          assert.ok(['EISDIR','EPERM','EACCES'].includes(result.resultWriteFailure.code))
          assert.equal(report.childResultRecoveredFromStderr, true)
          const supervisor = JSON.parse(await readFile(resolve(probeFolder, 'result.json'), 'utf8'))
          assert.equal(supervisor.originalFailure.code, 7)
          assert.ok(supervisor.childResult.cleanupErrors.length > 0, 'Independent failure JSON must retain cleanup errors after child result write failure')
          assert.ok(result.cleanupErrors.some(error => error.step === 'synthetic failed log write'))
          assert.ok(result.cleanupAttempts.some(attempt => attempt.name === 'close every owned socket despite cleanup failures' && attempt.status === 'passed'))
        } else {
          assert.match(result.originalFailure.name + ' ' + result.originalFailure.message, /Deadline|Abort|aborted/)
          assert.ok(operation.elapsedMs >= 180 && operation.elapsedMs < 700)
          if (mode === 'cumulative') { assert.ok(result.observations.received >= 2); assert.ok(result.observations.completed >= 1 && result.observations.completed < 5) }
          else { assert.ok(result.observations.chunks >= 2); assert.equal(result.observations.completed, 0) }
        }
      }
      cases.push({ mode, status: 'passed', negativeStatus: report.status })
    })
  }
  assert.equal(cases.length, 5)
} catch (error) { failure = error }
const result = await execution.finalize(failure, [{ name: 'close regression-owned requests', work: () => execution.closeRequests() }], { cases })
if (result.status === 'passed') console.log('PASS package execution: five short negative deadline/watchdog/cleanup regressions and deterministic network allocator assertions')
else { process.exitCode = 1; console.error('Package execution regression failed; inspect preserved result.json') }
