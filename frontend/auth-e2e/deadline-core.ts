import { AsyncLocalStorage } from 'node:async_hooks'
import { randomUUID } from 'node:crypto'
import { performance } from 'node:perf_hooks'
import { setTimeout, clearTimeout } from 'node:timers'

export type Kind = 'prepare' | 'operation' | 'cleanup' | 'lifecycle'
export const limits = Object.freeze({ prepare: 60_000, operation: 10_000, cleanup: 10_000, lifecycle: 60_000 })
export type Event = { event: 'start' | 'end'; id: string; kind: Kind; name: string; startedAt: number;
  deadlineAt: number; endedAt?: number; elapsedMs?: number; status?: 'passed' | 'failed'; pid: number; parentId?: string }
export interface Clock { now(): number; wall(): number; schedule(fn: () => void, ms: number): () => void }
const clock: Clock = { now: () => performance.now(), wall: () => Date.now(), schedule: (fn, ms) => {
  const timer = setTimeout(fn, ms); return () => clearTimeout(timer)
} }
export class DeadlineExceeded extends Error {
  readonly label: string
  readonly deadlineAt: number
  constructor(label: string, deadlineAt: number) { super('Deadline exceeded: ' + label + ' at ' + deadlineAt); this.name = 'DeadlineExceeded'; this.label = label; this.deadlineAt = deadlineAt }
}
export interface Deadline {
  readonly id: string; readonly kind: Kind; readonly deadlineAt: number;
  remaining(existingLimit?: number): number;
  run<T>(work: () => Promise<T>, existingLimit?: number): Promise<T>;
}
type Scope = Deadline & { finishAt: number; name: string; expired: boolean }

/** A single monotonic deadline belongs to a command, never to an individual retry/assertion. */
export class Deadlines {
  private readonly local = new AsyncLocalStorage<Scope>()
  private readonly emit: (event: Event) => void
  private readonly time: Clock
  constructor(emit: (event: Event) => void, time: Clock = clock) { this.emit = emit; this.time = time }
  current(): Deadline | undefined { return this.local.getStore() }
  /** Capture the initiating command, including its closed state; never consult a later command. */
  bind<A extends unknown[], R>(work: (...args: A) => Promise<R>): (...args: A) => Promise<R> {
    const scope = this.local.getStore()
    if (!scope) throw new Error('Cannot bind callback without an active deadline')
    scope.remaining()
    return (...args: A) => this.local.run(scope, () => scope.run(() => work(...args)))
  }
  async scope<T>(kind: Kind, name: string, work: (deadline: Deadline) => Promise<T>): Promise<T> {
    const parent = this.local.getStore()
    if (parent?.kind === 'operation' && kind !== 'operation') throw new Error('Cannot move an active user command into ' + kind)
    // Helper calls, retries and polling inherit the command deadline; they cannot open a new budget.
    if (parent?.kind === 'operation') { parent.remaining(); const value = await work(parent); parent.remaining(); return value }
    const start = this.time.now(), startedAt = this.time.wall()
    const duration = Math.max(1, Math.floor(Math.min(limits[kind], parent ? parent.remaining() : Infinity)))
    const finishAt = start + duration, deadlineAt = startedAt + duration
    const scope: Scope = { id: randomUUID(), kind, name, finishAt, deadlineAt, expired: false,
      remaining: (existing = Infinity) => {
        const left = finishAt - this.time.now()
        if (scope.expired || left <= 0) throw new DeadlineExceeded(name, deadlineAt)
        return Math.max(1, Math.min(left, existing > 0 ? existing : Infinity))
      },
      run: async <R>(fn: () => Promise<R>, existing = Infinity) => {
        const left = scope.remaining(existing), subFinish = this.time.now() + left
        let cancel = () => {}
        const timeout = new Promise<never>((_, reject) => { cancel = this.time.schedule(() => reject(new DeadlineExceeded(name, deadlineAt)), left) })
        try { const result = await Promise.race([fn(), timeout]); scope.remaining();
          if (this.time.now() >= subFinish) throw new DeadlineExceeded(name, deadlineAt)
          return result
        } finally { cancel() }
      },
    }
    const event: Event = { event: 'start', id: scope.id, kind, name, startedAt, deadlineAt, pid: process.pid,
      ...(parent ? { parentId: parent.id } : {}) }
    this.emit(event)
    let cancel = () => {}, status: 'passed' | 'failed' = 'failed'
    const timeout = new Promise<never>((_, reject) => { cancel = this.time.schedule(() => {
      scope.expired = true; reject(new DeadlineExceeded(name, deadlineAt))
    }, duration) })
    try {
      const value = await Promise.race([this.local.run(scope, () => work(scope)), timeout])
      scope.remaining(); status = 'passed'; return value
    } finally {
      cancel()
      // Retain a closed scope in the losing callback's async context so late work cannot reopen a budget.
      scope.expired = true
      this.emit({ ...event, event: 'end', endedAt: this.time.wall(), elapsedMs: this.time.now() - start, status })
    }
  }
}
