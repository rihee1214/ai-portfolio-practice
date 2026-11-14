import test from 'node:test'
import assert from 'node:assert/strict'
import { verifyDeadlineEvidence, ScopeInventory } from '../auth-e2e/deadline-evidence.ts'
const tests=[{id:'test',title:'command',project:'desktop',file:'a.spec.ts',status:'passed'}]
const start={event:'start',id:'scope',kind:'operation',name:'submit',startedAt:100,deadlineAt:10100,pid:1,testId:'test'}
const end={...start,event:'end',endedAt:200,status:'passed'}
test('deadline evidence matches a measured command to its exact test',()=>assert.equal(verifyDeadlineEvidence(tests,[start,end]).status,'passed'))
test('a passing test without any measured command fails the evidence gate',()=>assert.equal(verifyDeadlineEvidence(tests,[]).status,'failed'))
test('missing, duplicate, unknown and late scope evidence fails closed',()=>{
  for(const events of [[start],[end],[start,start,end],[{...start,testId:'unknown'},end],[start,{...end,endedAt:10101}],[start,{...end,deadlineAt:20000}],[start,{...end,status:'failed'}]])assert.equal(verifyDeadlineEvidence(tests,events).status,'failed')
})
test('undeclared phases and repeated preparation cannot extend execution indefinitely',()=>{
  const inventory=new ScopeInventory([{kind:'operation',name:'submit',maxOccurrencesPerTest:2}])
  inventory.consume(start);inventory.consume({...start,id:'two'})
  assert.throws(()=>inventory.consume({...start,id:'three'}),/iteration limit/)
  assert.throws(()=>inventory.consume({...start,kind:'prepare'}),/Undeclared/)
  inventory.reset();assert.doesNotThrow(()=>inventory.consume(start))
})
test('invalid, duplicate and unlimited scope inventories are rejected',()=>{
  for(const entries of [[],[{kind:'operation',name:'x',maxOccurrencesPerTest:0}],[{kind:'operation',name:'x',maxOccurrencesPerTest:Infinity}],
    Array(2).fill({kind:'operation',name:'x',maxOccurrencesPerTest:1})])assert.throws(()=>new ScopeInventory(entries))
})
