import type { Event } from './deadline-core'
export type TestRun = { id: string; title: string; project: string; file: string; status: string }
export type ScopedEvent = Event & { testId?: string }
export type Declaration = { name: string; kind: string; maxOccurrencesPerTest: number }
export class ScopeInventory {
  private entries = new Map<string, number>()
  private counts = new Map<string, number>()
  constructor(entries: Declaration[]) {
    if (!Array.isArray(entries) || !entries.length) throw new Error('Empty deadline inventory')
    for (const entry of entries) {
      const key=entry.kind+':'+entry.name
      if(!['prepare','operation','cleanup'].includes(entry.kind) || !entry.name || !Number.isInteger(entry.maxOccurrencesPerTest)
        || entry.maxOccurrencesPerTest<1 || entry.maxOccurrencesPerTest>10002 || this.entries.has(key)) throw new Error('Invalid or duplicate scope declaration: '+key)
      this.entries.set(key,entry.maxOccurrencesPerTest)
    }
  }
  reset() { this.counts.clear() }
  consume(event: Event) {
    if(event.event!=='start')return
    const key=event.kind+':'+event.name, bound=this.entries.get(key), count=(this.counts.get(key)??0)+1
    if(bound===undefined)throw new Error('Undeclared deadline scope: '+key)
    if(count>bound)throw new Error('Declared command iteration limit exceeded: '+event.name)
    this.counts.set(key,count)
  }
}
/** Fail closed: a green Playwright result without measured commands is not deadline evidence. */
export function verifyDeadlineEvidence(tests: TestRun[], events: ScopedEvent[]) {
  const problems: string[] = [], ids = new Set<string>(), active = new Map<string, ScopedEvent>()
  const counts = new Map<string, number>()
  for (const event of events) {
    if (event.kind === 'lifecycle') continue
    if (!event.testId || !tests.some(t => t.id === event.testId)) { problems.push('unknown test for ' + event.id); continue }
    if (event.event === 'start') {
      if (ids.has(event.id)) problems.push('duplicate scope ' + event.id)
      ids.add(event.id); active.set(event.id,event)
      if (event.kind === 'operation') counts.set(event.testId,(counts.get(event.testId) ?? 0)+1)
    } else {
      const start=active.get(event.id)
      if (!start || start.testId!==event.testId || start.deadlineAt!==event.deadlineAt || start.name!==event.name || start.kind!==event.kind) problems.push('unmatched end ' + event.id)
      active.delete(event.id)
      if (event.status!=='passed' || event.endedAt===undefined || event.endedAt>event.deadlineAt) problems.push('failed or late scope ' + event.id)
    }
  }
  for (const id of active.keys()) problems.push('unfinished scope ' + id)
  for (const test of tests) if (!counts.get(test.id)) problems.push('no measured business command: ' + test.project + '/' + test.title)
  return { status: problems.length ? 'failed' : 'passed', problems, tests: tests.map(t=>({...t, operations:counts.get(t.id)??0})), scopes:ids.size }
}
