import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { Deadlines, DeadlineExceeded } from '../auth-e2e/deadline-core.ts'

let predicate
try { predicate = (await import('../auth-e2e/release-shipment-consumer.ts')).shipmentConsumerComplete }
catch (error) { if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error }
const complete = (number, proof) => {
  assert.equal(typeof predicate, 'function', 'the exact shipment-consumer readiness predicate must exist')
  return predicate(number, proof)
}
const number = 'O0ca3122cda1c4ea4a42700e68b836235'
const shipment = '9e922d17-fb8a-41b3-836e-577b479956f5'
const order = '444afb84-ed19-44c7-9f82-6d9aa2648ecf'
const job = '27a1c370-1a86-49f3-9cbb-32f656ca462e'
function completed() {
  return { order: { id: order, order_number: number, state: 'PAID', amount: 13000 },
    shipments: [{ id: shipment, order_id: order, order_number: number, state: 'PREPARING' }],
    shipmentConsumers: [{ shipment_id: shipment, order_id: order, order_number: number,
      job_id: job, job_type: 'SHIPMENT_CREATED', cause_key: 'created:' + shipment, dedupe_key: 'created:' + shipment,
      status: 'SUCCEEDED', job_attempts: 1, claim_job_id: job, claim_attempt: 1 }] }
}
const copy = value => JSON.parse(JSON.stringify(value))

test('public shipment readiness requires the tested order and its exact completed consumer', () => {
  const good = completed()
  assert.equal(complete(number, good), true)
  for (const mutate of [p => { p.order.order_number = 'other' }, p => { p.shipments[0].order_id = 'other' },
    p => { p.shipments[0].order_number = 'other' }, p => { p.shipmentConsumers[0].shipment_id = 'other' },
    p => { p.shipmentConsumers[0].order_id = 'other' }, p => { p.shipmentConsumers[0].order_number = 'other' },
    p => { p.shipmentConsumers[0].job_type = 'OTHER' }, p => { p.shipmentConsumers[0].cause_key = 'created:other' },
    p => { p.shipmentConsumers[0].dedupe_key = 'created:other' }, p => { p.shipments = [] },
    p => { p.shipmentConsumers = [] }, p => { p.shipmentConsumers.push(copy(p.shipmentConsumers[0])) }]) {
    const proof = completed(); mutate(proof); assert.equal(complete(number, proof), false)
  }
})
test('public shipment readiness rejects unfinished jobs and absent or mismatched durable claim attempts', () => {
  for (const mutate of [p => { p.shipmentConsumers[0].status = 'READY' }, p => { p.shipmentConsumers[0].status = 'RUNNING' },
    p => { p.shipmentConsumers[0].job_id = null }, p => { p.shipmentConsumers[0].job_attempts = 0 },
    p => { p.shipmentConsumers[0].claim_job_id = null }, p => { p.shipmentConsumers[0].claim_job_id = 'other' },
    p => { p.shipmentConsumers[0].claim_attempt = null }, p => { p.shipmentConsumers[0].claim_attempt = 2 }]) {
    const proof = completed(); mutate(proof); assert.equal(complete(number, proof), false)
  }
})
test('public shipment readiness rejects the preserved failed BEFORE state and accepts its completed AFTER state', () => {
  // Safe exact identities/statuses from perf2-baseline-d348... snapshots87ea.../ade12....
  // Added actual claim SHA1967f83434ec95e42888b9935b018e40ecc9caad7a07b84092960766635c8231.
  // This replays the readiness predicate only; it does not rerun or retroactively pass that browser test.
  const before = completed(); Object.assign(before.shipmentConsumers[0], {
    status: 'READY', job_attempts: 0, claim_job_id: null, claim_attempt: null,
  })
  assert.equal(complete(number, before), false)
  assert.equal(complete(number, completed()), true)
})
test('public shipment readiness polling retains the original ten-second commerce deadline', async () => {
  let now = 0
  const events = [], timers = new Set()
  const d = new Deadlines(e => events.push(e), { now: () => now, wall: () => 1700000000000 + now,
    schedule(fn, ms) { const timer = { fn, at: now + ms }; timers.add(timer); return () => timers.delete(timer) } })
  const advance = ms => { now += ms; for (const timer of [...timers]) if (timer.at <= now) { timers.delete(timer); timer.fn() } }
  await d.scope('operation', 'commerce', async original => {
    advance(9000)
    await d.scope('operation', 'observer/poll', async nested => {
      assert.equal(nested.id, original.id); assert.equal(nested.remaining(), 1000)
      assert.equal(complete(number, completed()), true)
    })
    const pending = original.run(async () => new Promise(() => {}))
    const rejected = assert.rejects(pending, DeadlineExceeded)
    advance(1000); await rejected
  }).then(() => assert.fail('commerce must fail at its original deadline'), error => assert.ok(error instanceof DeadlineExceeded))
  assert.equal(events.filter(e => e.event === 'start').length, 1)
  assert.equal(events.at(-1).deadlineAt - events[0].startedAt, 10000)
  assert.equal(events.at(-1).status, 'failed')
})
test('public shipment consumer polling stays inside commerce before the unchanged exact guest boundary', () => {
  const source = readFileSync(new URL('../auth-e2e/release-public.spec.ts', import.meta.url), 'utf8')
  const file = ts.createSourceFile('release-public.spec.ts', source, ts.ScriptTarget.Latest, true)
  let commerce, poll, before
  function visit(node) {
    if (ts.isCallExpression(node) && node.expression.getText(file) === 'operation'
      && node.arguments[0]?.text === 'release: public member commerce persists one autonomous worker effect') commerce = node
    if (ts.isCallExpression(node) && node.expression.getText(file) === 'shipmentConsumerComplete') poll = node
    if (ts.isVariableDeclaration(node) && node.name.getText(file) === 'before') before = node
    ts.forEachChild(node, visit)
  }
  visit(file)
  assert.ok(commerce && poll && before)
  assert.ok(poll.pos > commerce.pos && poll.end < commerce.end)
  assert.ok(before.pos > commerce.end)
  assert.ok(!commerce.getText(file).includes('prepare('))
  assert.ok(source.includes('expect(after.guest).toEqual(before.guest); expect(after.business).toEqual(before.business)'))
  assert.ok(source.includes("expect(response.status(), path + ' ' + await response.text()).toBe(503)"))
})
