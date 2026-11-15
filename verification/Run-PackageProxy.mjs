import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describeError, persistResult } from './package-execution.mjs'
import { watchPackageChild } from './package-watchdog.mjs'

const root = resolve(import.meta.dirname, '..'), folder = resolve(root, process.argv[2])
assert.ok(folder.startsWith(resolve(root, '.local-tools') + '\\'))
await mkdir(folder)
const outcomes = []
let failure
try {
  for (const [name, program] of [['execution-regressions','Test-PackageExecution.mjs'], ['proxy-contracts','Test-PackageProxy.mjs']]) {
    const outcome = await watchPackageChild({ program: resolve(import.meta.dirname, program), folder: resolve(folder, name) })
    outcomes.push({ name, ...outcome })
    assert.equal(outcome.status, 'passed', name + ' failed; inspect owned child and supervisor results')
  }
} catch (error) { failure = error }
const result = { status: failure ? 'failed' : 'passed', originalFailure: failure ? describeError(failure) : null, outcomes }
try { await persistResult(resolve(folder, 'result.json'), result) }
catch (error) { result.status = 'failed'; process.stderr.write('Package supervisor result write failed: ' + error.code + '\n') }
if (result.status !== 'passed') process.exitCode = 1
else console.log('PASS supervised package proxy: five negative regressions, network allocator assertions and eight unchanged contract groups')
