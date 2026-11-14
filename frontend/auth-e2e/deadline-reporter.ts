import type { Reporter, FullResult, TestCase, TestResult } from '@playwright/test/reporter'
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import type { Event } from './deadline-core'
import { verifyDeadlineEvidence, type TestRun, type ScopedEvent } from './deadline-evidence'

/** No suite/test deadline. The Java supervisor independently bounds gaps and unfinished scopes. */
export default class DeadlineReporter implements Reporter {
  private end?: Event
  private tests: TestRun[] = []
  onTestBegin(test: TestCase) {
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-tests.ndjson'), JSON.stringify({event:'start',id:test.id,title:test.title,
      project:test.parent.project()?.name,file:test.location.file,at:Date.now()})+'\n')
  }
  onTestEnd(test: TestCase, result: TestResult) {
    this.tests.push({id:test.id,title:test.title,project:test.parent.project()?.name??'',file:test.location.file,status:result.status})
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-tests.ndjson'), JSON.stringify({event:'end',id:test.id,status:result.status,at:Date.now()})+'\n')
  }
  async onEnd(result: FullResult) {
    const startedAt = Date.now()
    this.end = { event: 'start', id: randomUUID(), kind: 'lifecycle', name: 'report and runner shutdown: ' + result.status,
      startedAt, deadlineAt: startedAt + 30_000, pid: process.pid }
    this.write(this.end)
    try {
      const events = readFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-progress.ndjson'),'utf8').trim().split('\n').map(line=>JSON.parse(line) as ScopedEvent)
      const evidence = verifyDeadlineEvidence(this.tests,events)
      writeFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-deadlines.json'),JSON.stringify(evidence,null,2),{flag:'wx'})
      if(evidence.status!=='passed') return {status:'failed' as const}
    } catch(error) {
      writeFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-deadline-error.txt'),String(error),{flag:'wx'})
      return {status:'failed' as const}
    }
  }
  async onExit() {
    if (this.end) this.write({ ...this.end, event: 'end', endedAt: Date.now(), status: 'passed' })
  }
  private write(event: Event) {
    appendFileSync(resolve(process.env.A3_EVIDENCE!, 'browser-progress.ndjson'), JSON.stringify(event) + '\n')
  }
}
