import { AsyncLocalStorage } from 'node:async_hooks'
import { execFile } from 'node:child_process'
import { appendFileSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import { basename, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import { Deadlines, DeadlineExceeded, limits } from '../frontend/auth-e2e/deadline-core.ts'

export { limits }
export const describeError = error => ({ name: error?.name ?? 'Error', message: error?.message ?? String(error),
  ...(error?.code === undefined ? {} : { code: error.code }) })
export const alive = pid => { try { process.kill(pid, 0); return true } catch { return false } }
export async function persistResult(file, result, maximum = 1_000) {
  const controller = new AbortController(), duration = Math.max(1, Math.floor(maximum))
  let timer
  try {
    await Promise.race([writeFile(file, JSON.stringify(result, null, 2), { signal: controller.signal }),
      new Promise((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('Finite result write deadline exceeded')) }, duration) })])
  } finally { clearTimeout(timer) }
}

/** Kill only a process created by this invocation, and its descendants. */
export async function terminateTree(child, timeout = 2_000) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return
  if (child.pid === process.pid || child.pid === process.ppid) throw new Error('Refused to terminate supervising process')
  if (process.platform === 'win32') {
    await new Promise((accept, reject) => execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'],
      { timeout: Math.max(1, timeout), windowsHide: true }, error => {
        if (error && alive(child.pid)) reject(new Error('Owned process tree did not terminate: ' + child.pid))
        else accept()
      }))
  } else {
    try { process.kill(-child.pid, 'SIGKILL') } catch { if (alive(child.pid)) child.kill('SIGKILL') }
  }
}

/** Production uses the shared 60/10/10 policy unchanged. Smaller budgets are a test seam only. */
export class PackageExecution {
  constructor(folder, { budgets = {} } = {}) {
    this.folder = folder
    this.local = new AsyncLocalStorage()
    this.children = new Set()
    this.requests = new Set()
    this.events = []
    this.evidenceErrors = []
    this.starts = new Map()
    this.budgets = Object.fromEntries(Object.entries(limits).map(([kind, value]) => [kind, Math.min(value, budgets[kind] ?? value)]))
    this.deadlines = new Deadlines(event => {
      if (event.event === 'start') this.starts.set(event.id, performance.now())
      const row = { ...event, deadlineAt: Math.min(event.deadlineAt, event.startedAt + this.budgets[event.kind]) }
      this.events.push(row)
      try { appendFileSync(resolve(folder, 'proxy-progress.ndjson'), JSON.stringify(row) + '\n') }
      catch (error) {
        // Evidence failure is fatal, but must not prevent resource cleanup from starting.
        this.evidenceErrors.push(describeError(error)); process.stderr.write('PACKAGE_PHASE_EVIDENCE_FAILURE=' + error.code + '\n')
      }
    })
  }
  current() {
    const scope = this.local.getStore()
    if (!scope) throw new Error('Package work requires a declared phase')
    scope.remaining()
    return scope
  }
  async scope(kind, name, work) {
    return this.deadlines.scope(kind, name, async deadline => {
      const finish = this.starts.get(deadline.id) + this.budgets[kind]
      const controller = new AbortController()
      const remaining = (existing = Infinity) => {
        const left = Math.min(deadline.remaining(existing), finish - performance.now())
        if (left <= 0 || controller.signal.aborted) throw new DeadlineExceeded(name, deadline.deadlineAt)
        return Math.max(1, left)
      }
      const effective = { id: deadline.id, signal: controller.signal, remaining,
        run: work => deadline.run(work, remaining()) }
      const timer = setTimeout(() => controller.abort(new DeadlineExceeded(name, deadline.deadlineAt)), remaining())
      try {
        const value = await deadline.run(() => this.local.run(effective, () => work(effective)), remaining())
        remaining()
        return value
      } finally { clearTimeout(timer); controller.abort(new Error('Package phase closed')) }
    })
  }
  request(options, body) {
    const scope = this.current(), client = options.protocol === 'http:' ? http : https
    return scope.run(() => new Promise((accept, reject) => {
      const request = client.request({ ...options, signal: scope.signal }, response => {
        let text = ''
        response.on('data', part => { text += part })
        response.on('error', reject)
        response.on('aborted', () => reject(new Error('Incomplete upstream response')))
        response.on('end', () => accept({ status: response.statusCode, headers: response.headers, body: text }))
      })
      this.requests.add(request)
      request.once('close', () => this.requests.delete(request))
      // Retain the old stricter inactivity limit as well as the shared absolute abort.
      request.setTimeout(Math.max(1, Math.floor(scope.remaining(10_000))), () => request.destroy(new Error('Request inactivity exceeded remaining phase budget')))
      request.on('error', reject); request.end(body)
    }))
  }
  execute(file, args, options = {}) {
    const scope = this.current(), duration = scope.remaining(options.timeout ?? Infinity)
    return scope.run(() => new Promise((accept, reject) => {
      let finished = false
      const child = execFile(file, args, { ...options, timeout: undefined, signal: undefined,
        maxBuffer: 8 * 1024 * 1024, windowsHide: true, detached: process.platform !== 'win32' }, (error, stdout, stderr) => {
        finished = true; clearTimeout(timer); scope.signal.removeEventListener('abort', abort)
        this.children.delete(child)
        if (error) {
          const failure = new Error('Owned command failed: ' + basename(file) + ' exit ' + (error.code ?? error.signal ?? 'unknown'))
          failure.code = error.code; failure.stdout = stdout; failure.stderr = stderr; reject(failure)
        } else accept({ stdout, stderr })
      })
      this.children.add(child)
      appendFileSync(resolve(this.folder, 'proxy-owned-processes.ndjson'), JSON.stringify({ pid: child.pid, executable: basename(file), startedAt: Date.now() }) + '\n')
      const abort = () => {
        if (finished) return
        // Start tree termination before rejecting; cleanup retries every still-owned child.
        terminateTree(child).catch(() => {})
        reject(new DeadlineExceeded('owned command ' + basename(file), Date.now()))
      }
      const timer = setTimeout(abort, Math.max(1, Math.floor(duration)))
      scope.signal.addEventListener('abort', abort, { once: true })
    }))
  }
  closeRequests() {
    return Promise.all([...this.requests].map(request => {
      if (request.closed) { this.requests.delete(request); return Promise.resolve() }
      return new Promise(accept => { request.once('close', accept); request.destroy(new Error('Owned request cleanup')) })
    }))
  }
  async finalize(originalFailure, steps, fields = {}) {
    const cleanupErrors = [], attempts = []
    if (originalFailure) {
      // The supervisor retains this even if this process is killed during cleanup.
      process.stderr.write('PACKAGE_ORIGINAL_FAILURE=' + JSON.stringify(describeError(originalFailure)) + '\n')
    }
    try {
      await this.scope('cleanup', 'close owned package resources', async scope => {
        // Start every independent attempt before waiting: one failed/hung cleanup cannot skip another.
        await Promise.all(steps.map(step => {
          const record = { name: step.name, status: 'pending' }; attempts.push(record)
          let work
          try { work = Promise.resolve(step.work(scope)) } catch (error) { work = Promise.reject(error) }
          work.catch(() => {})
          return scope.run(() => work).then(() => { record.status = 'passed' }, error => {
            record.status = 'failed'; record.error = describeError(error); cleanupErrors.push({ step: step.name, ...describeError(error) })
          })
        }))
      })
    } catch (error) { cleanupErrors.push({ step: 'cleanup phase', ...describeError(error) }) }
    const result = { ...fields, status: originalFailure || cleanupErrors.length || this.evidenceErrors.length || attempts.some(a => a.status !== 'passed') ? 'failed' : 'passed',
      originalFailure: originalFailure ? describeError(originalFailure) : null, cleanupErrors, cleanupAttempts: attempts,
      phaseEvents: this.events, phaseEvidenceErrors: this.evidenceErrors, remainingOwnedRequests: this.requests.size,
      remainingOwnedProcesses: [...this.children].filter(child => alive(child.pid)).map(child => child.pid) }
    if (result.remainingOwnedRequests || result.remainingOwnedProcesses.length) result.status = 'failed'
    try { await persistResult(resolve(this.folder, 'result.json'), result) }
    catch (error) {
      result.status = 'failed'; result.resultWriteFailure = describeError(error)
      process.stderr.write('PACKAGE_CHILD_RESULT=' + JSON.stringify(result) + '\n')
    }
    return result
  }
}
