import { spawn, execFile } from 'node:child_process'
import { appendFileSync } from 'node:fs'
import { mkdir, readFile, writeFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import { setTimeout as pause } from 'node:timers/promises'
import { describeError, limits, terminateTree, alive, persistResult } from './package-execution.mjs'

async function bounded(milliseconds, work) {
  let timer
  try { return await Promise.race([work(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Supervisor I/O did not finish')), milliseconds) })]) }
  finally { clearTimeout(timer) }
}
function hostCommand(file, args, remaining) {
  return new Promise((accept, reject) => execFile(file, args, { timeout: Math.max(1, Math.floor(remaining())), windowsHide: true,
    maxBuffer: 1024 * 1024 }, (error, stdout) => error ? reject(new Error('Supervisor command failed: ' + file)) : accept(stdout)))
}

/** An independent process supervises phase progress, never an overall scenario duration. */
export async function watchPackageChild({ program, folder, args = [], grace = 1_000, bootstrap = 60_000, idle = 10_000, poll = 50 }) {
  await mkdir(folder)
  const evidence = resolve(folder, 'execution')
  const child = spawn(process.execPath, [program, evidence, ...args], { windowsHide: true, detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'] })
  let exited = false, closed = false, exitCode, signal, spawnFailure, watchdogFailure, stderr = '', outputBytes = 0
  const recordOutput = (stream, file, part) => {
    outputBytes += part.length
    if (outputBytes > 8 * 1024 * 1024) { watchdogFailure ??= new Error('Owned child output exceeded finite evidence limit'); return }
    try { appendFileSync(resolve(folder, file), part) } catch (error) { watchdogFailure ??= error }
    if (stream === 'stderr') stderr += part.toString()
  }
  child.stdout.on('data', part => recordOutput('stdout', 'stdout.txt', part))
  child.stderr.on('data', part => recordOutput('stderr', 'stderr.txt', part))
  child.stdout.on('error', error => { watchdogFailure ??= error })
  child.stderr.on('error', error => { watchdogFailure ??= error })
  child.once('error', error => { spawnFailure = error; exited = true })
  child.once('exit', (code, why) => { exitCode = code; signal = why; exited = true })
  child.once('close', () => { closed = true })
  const active = new Map(), seen = new Set(), accepted = []
  let lines = 0, gapDeadline = performance.now() + bootstrap
  const consume = async () => {
    let text
    try {
      text = await bounded(500, async () => {
        const metadata = await stat(resolve(evidence, 'proxy-progress.ndjson'))
        if (metadata.size > 1024 * 1024) throw new Error('Oversized child progress')
        return readFile(resolve(evidence, 'proxy-progress.ndjson'), 'utf8')
      })
    } catch (error) { if (error.code === 'ENOENT') return; throw error }
    const complete = text.split('\n'); complete.pop()
    if (complete.length < lines) throw new Error('Child progress was truncated')
    for (const line of complete.slice(lines)) {
      const row = JSON.parse(line)
      if (!['start','end'].includes(row.event) || !Object.hasOwn(limits, row.kind) || row.pid !== child.pid ||
        !Number.isSafeInteger(row.pid) || row.pid <= 0 || !Number.isSafeInteger(row.startedAt) || row.startedAt <= 0 || !Number.isSafeInteger(row.deadlineAt) ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(row.id) || typeof row.name !== 'string' || !row.name.trim() ||
        row.deadlineAt <= row.startedAt || row.deadlineAt - row.startedAt > limits[row.kind] || row.startedAt > Date.now() + 1_000) {
        throw new Error('Invalid owned child phase evidence')
      }
      if (row.event === 'start') {
        if (seen.has(row.id) || active.size) throw new Error('Duplicate or overlapping package phase')
        seen.add(row.id)
        active.set(row.id, { ...row, expires: performance.now() + row.deadlineAt - Date.now() + grace })
      } else {
        const start = active.get(row.id)
        if (!start || start.kind !== row.kind || start.name !== row.name || start.startedAt !== row.startedAt || start.deadlineAt !== row.deadlineAt ||
          !Number.isSafeInteger(row.endedAt) || row.endedAt < row.startedAt || row.endedAt > Date.now() + 1_000 ||
          !['passed','failed'].includes(row.status) || (row.status === 'passed' && row.endedAt > row.deadlineAt)) throw new Error('Mismatched child phase completion')
        active.delete(row.id); gapDeadline = performance.now() + idle
      }
      accepted.push(row)
    }
    lines = complete.length
  }
  const recovery = [], cleanupErrors = []
  let childResult, originalFailure, childResultRecoveredFromStderr = false
  try {
    while (true) {
      if (watchdogFailure) throw watchdogFailure
      await consume()
      const now = performance.now()
      if ([...active.values()].some(phase => now > phase.expires)) throw new Error('Owned package phase watchdog expired')
      if (!active.size && now > gapDeadline) throw new Error('Owned package bootstrap/progress gap watchdog expired')
      if (exited) break
      await pause(poll)
    }
    if (spawnFailure) throw spawnFailure
    if (active.size || !seen.size) throw new Error('Child exited without complete phase evidence')
  } catch (error) { watchdogFailure = error }
  finally {
    const finish = performance.now() + 10_000
    const remaining = () => { const left = finish - performance.now(); if (left <= 0) throw new Error('Supervisor cleanup deadline exceeded'); return left }
    const attempt = async (name, work) => {
      const row = { name, status: 'pending' }; recovery.push(row)
      try { await bounded(remaining(), work); row.status = 'passed' }
      catch (error) { row.status = 'failed'; row.error = describeError(error); cleanupErrors.push({ step: name, ...describeError(error) }) }
    }
    // Only the live ChildProcess handle of this invocation is terminated. Historical PID logs are evidence, never kill targets.
    await attempt('terminate current owned child tree', () => terminateTree(child, Math.min(2_000, remaining())))
    const closedWait = attempt('await current owned stdio close', () => closed ? Promise.resolve() : new Promise(accept => child.once('close', accept)))
    await attempt('read child result', async () => {
      try { childResult = JSON.parse(await bounded(Math.min(500, remaining()), () => readFile(resolve(evidence, 'result.json'), 'utf8'))) }
      catch (error) { if (error.code !== 'ENOENT') throw error }
    })
    let proxyRecovery = Promise.resolve()
    if (watchdogFailure || !childResult || childResult.cleanupErrors?.length || childResult.remainingOwnedProcesses?.length) {
      proxyRecovery = attempt('recover exact invocation proxy container', async () => {
        let plan
        try { plan = JSON.parse(await readFile(resolve(evidence, 'proxy-plan.json'), 'utf8')) }
        catch (error) { if (error.code === 'ENOENT') return; throw error }
        if (!/^p12c-[0-9a-f]{32}$/.test(plan.name) || plan.label !== 'marketplace.scope=p12-c') throw new Error('Invalid current invocation proxy ownership plan')
        let inspection
        try { inspection = JSON.parse(await hostCommand('docker', ['inspect', plan.name], remaining)) }
        catch { throw new Error('Cannot verify current invocation proxy ownership for recovery') }
        if (inspection[0]?.Config?.Labels?.['marketplace.scope'] !== 'p12-c' || inspection[0]?.Name !== '/' + plan.name) throw new Error('Proxy ownership differs from current plan')
        await hostCommand('docker', ['stop','--timeout','5',plan.name], remaining)
      })
    }
    // Resource recovery runs even if a stdio pipe refuses to close; both share the same remaining cleanup budget.
    await Promise.all([closedWait, proxyRecovery])
    if (!childResult) {
      const snapshot = stderr.split('\n').find(line => line.startsWith('PACKAGE_CHILD_RESULT='))
      if (snapshot) {
        try { childResult = JSON.parse(snapshot.substring('PACKAGE_CHILD_RESULT='.length)); childResultRecoveredFromStderr = true } catch {}
      }
    }
    originalFailure = childResult?.originalFailure
    if (!originalFailure) {
      const line = stderr.split('\n').find(line => line.startsWith('PACKAGE_ORIGINAL_FAILURE='))
      if (line) { try { originalFailure = JSON.parse(line.substring('PACKAGE_ORIGINAL_FAILURE='.length)) } catch {} }
    }
    if (alive(child.pid)) cleanupErrors.push({ step: 'owned child identity', name: 'Error', message: 'Current owned child remains alive' })
    const status = !watchdogFailure && !spawnFailure && exitCode === 0 && childResult?.status === 'passed' && !cleanupErrors.length ? 'passed' : 'failed'
    const result = { status, pid: child.pid, exitCode, signal, watchdogFailure: watchdogFailure ? describeError(watchdogFailure) : null,
      originalFailure: originalFailure ?? null, cleanupErrors, recovery, acceptedPhases: accepted, childResult, childResultRecoveredFromStderr,
      currentChildAlive: alive(child.pid) }
    // The supervisor owns this record even when the child cannot finish its own finally/result write.
    try { await persistResult(resolve(folder, 'result.json'), result, Math.min(1_000, Math.max(1, finish - performance.now()))) }
    catch (error) { result.status = 'failed'; result.supervisorWriteFailure = describeError(error); process.stderr.write('PACKAGE_SUPERVISOR_RESULT=' + JSON.stringify(result) + '\n') }
    if (!childResult) {
      try {
        await bounded(Math.max(1, finish - performance.now()), () => mkdir(evidence, { recursive: true }))
        await persistResult(resolve(evidence, 'result.json'), { status: 'failed', supervisorFallback: true,
          originalFailure: originalFailure ?? result.watchdogFailure, cleanupErrors, currentChildAlive: result.currentChildAlive }, Math.min(1_000, Math.max(1, finish - performance.now())))
      } catch (error) { result.status = 'failed'; result.fallbackWriteFailure = describeError(error); process.stderr.write('PACKAGE_FALLBACK_WRITE_FAILURE=' + error.code + '\n') }
    }
    return result
  }
}
