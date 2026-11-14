import test from 'node:test'
import assert from 'node:assert/strict'
import { Deadlines, DeadlineExceeded } from '../auth-e2e/deadline-core.ts'

function harness() {
  let now = 0
  const timers = new Set(), events = []
  const clock = { now: () => now, wall: () => 1700000000000 + now,
    schedule(fn, ms) { const timer = { at: now + ms, fn }; timers.add(timer); return () => timers.delete(timer) } }
  return { d: new Deadlines(e => events.push(e), clock), events, advance(ms) {
    now += ms
    for (const timer of [...timers]) if (timer.at <= now) { timers.delete(timer); timer.fn() }
  } }
}

test('bound asynchronous callbacks retain the initiating token and reject adoption by later commands', async () => {
  const h = harness(); let bound, original
  assert.throws(() => h.d.bind(async () => {}), /active deadline/)
  await h.d.scope('operation','initiator',async deadline => {
    original=deadline.id
    bound=h.d.bind(async () => {
      assert.equal(h.d.current().id,original)
      return h.d.current().remaining()
    })
    h.advance(6000)
    assert.equal(await bound(),4000)
  })
  await h.d.scope('operation','later independent command',async deadline => {
    assert.notEqual(deadline.id,original)
    await assert.rejects(bound(),DeadlineExceeded)
    assert.equal(h.d.current().id,deadline.id)
    assert.equal(deadline.remaining(),10000)
  })
  assert.equal(h.events.filter(e => e.event==='start').length,2)
})
test('long explicit preparation is excluded from the next command deadline', async () => {
  const h = harness()
  await h.d.scope('prepare', 'API data and ready assertion', async () => { h.advance(45000); assert.equal(true, true) })
  await h.d.scope('operation', 'submit and result', async d => { h.advance(9000); assert.equal(d.remaining(), 1000) })
  assert.deepEqual(h.events.filter(e => e.event === 'end').map(e => [e.kind,e.elapsedMs,e.status]), [['prepare',45000,'passed'],['operation',9000,'passed']])
})
test('a command exceeding ten seconds fails even while its callback is unresolved', async () => {
  const h = harness()
  const pending = h.d.scope('operation', 'stalled response', async () => new Promise(() => {}))
  const rejection = assert.rejects(pending, DeadlineExceeded)
  h.advance(10000); await rejection
  assert.equal(h.events.at(-1).status, 'failed')
  assert.equal(h.events.at(-1).deadlineAt - h.events[0].startedAt,10000)
})
test('independent commands can total more than ten seconds without a scenario limit', async () => {
  const h = harness()
  for (const name of ['select address','submit order','ship']) await h.d.scope('operation', name, async () => { h.advance(9000) })
  assert.equal(h.events.filter(e => e.status === 'passed').length,3)
  assert.equal(h.events.at(-1).endedAt - h.events[0].startedAt,27000)
})
test('poll, retry and nested command helpers share the original deadline', async () => {
  const h = harness()
  await assert.rejects(h.d.scope('operation', 'lost order response', async outer => {
    h.advance(6000)
    await h.d.scope('operation','retry and reload',async inner => {
      assert.equal(inner.id,outer.id); assert.equal(inner.remaining(),4000)
      h.advance(3999); assert.equal(inner.remaining(),1)
      h.advance(1); inner.remaining()
    })
  }),DeadlineExceeded)
  assert.equal(h.events.filter(e => e.event === 'start').length,1)
})
test('an active command cannot reclassify delayed work as preparation or cleanup', async () => {
  const h = harness()
  await h.d.scope('operation','command',async () => {
    await assert.rejects(h.d.scope('prepare','escape',async () => {}),/Cannot move/)
    await assert.rejects(h.d.scope('cleanup','escape',async () => {}),/Cannot move/)
  })
})
test('stricter request contracts remain strict within the shared command budget', async () => {
  const h = harness()
  await h.d.scope('operation','query',async d => {
    assert.equal(d.remaining(500),500)
    const pending = d.run(async () => new Promise(() => {}),500)
    const rejection = assert.rejects(pending,DeadlineExceeded)
    h.advance(500); await rejection
    assert.equal(d.remaining(),9500)
  })
})
test('late callback cannot gain a new deadline after timeout', async () => {
  const h = harness(); let release
  const gate = new Promise(resolve => { release=resolve })
  let late
  const pending=h.d.scope('operation','late original',async()=>{ await gate; late=h.d.scope('operation','late retry',async()=>{}); await late })
  const rejection=assert.rejects(pending,DeadlineExceeded)
  h.advance(10000); await rejection; release(); await Promise.resolve()
  await assert.rejects(late,DeadlineExceeded)
  assert.equal(h.events.filter(e=>e.event==='start').length,1)
})
test('preparation and cleanup have finite independent limits', async () => {
  for(const [kind,ms] of [['prepare',60000],['cleanup',10000]]) {
    const h=harness(),pending=h.d.scope(kind,'stalled '+kind,async()=>new Promise(()=>{}))
    const rejection=assert.rejects(pending,DeadlineExceeded);h.advance(ms);await rejection
    assert.equal(h.events.at(-1).status,'failed')
  }
})
test('a delayed timer cannot let synchronous work overrun a stricter subdeadline', async () => {
  let now=0
  const d=new Deadlines(()=>{}, {now:()=>now,wall:()=>1700000000000+now,schedule:()=>()=>{}})
  await d.scope('operation','query',async scope=>{
    await assert.rejects(scope.run(async()=>{now+=6000;return 'late'},5000),DeadlineExceeded)
  })
})
