// @vitest-environment node
import { randomUUID } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'vitest'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const frontend = resolve(root, 'frontend')
const require = createRequire(import.meta.url)
type CommandResult = { code: number | null; timedOut: boolean; err: string }
const { runOwned } = require(resolve(root, 'infra/p12-d/process-runner.cjs')) as {
  runOwned: (file: string, args: string[], options: { cwd: string; env: NodeJS.ProcessEnv; output: string; timeout: number }) => Promise<CommandResult>
}
type Requirement = { tests: string[] }
type ListedTest = { projectName: string; results: unknown[] }
type Suite = { suites?: Suite[]; specs?: { file: string; title: string; tests: ListedTest[] }[] }
type Listing = { errors?: unknown[]; suites: Suite[] }
function required(file: string) {
  const value = JSON.parse(readFileSync(resolve(root, 'verification', file), 'utf8')) as Requirement | Requirement[]
  return (Array.isArray(value) ? value : [value]).flatMap(item => item.tests).sort()
}
function collected(suites: Suite[]): string[] {
  return suites.flatMap(suite => [
    ...(suite.specs ?? []).flatMap(spec => spec.tests.map(item => {
      expect(item.results).toEqual([]) // Collection evidence must never be counted as executed browser tests.
      return item.projectName + '#' + spec.file.replaceAll('\\', '/') + ':' + spec.title
    })), ...collected(suite.suites ?? []),
  ])
}

test('actual Playwright discovery preserves every ordinary mandatory test and isolates both release modes', { timeout: 45_000 }, async () => {
  const evidence = mkdtempSync(resolve(root, '.local-tools/p12-d-browser-discovery-'))
  const summary: Record<string, unknown> = { evidence, discoveryOnly: true, browserTestsExecuted: 0, childLimitMs: 10_000 }
  async function list(name: string, mode?: 'L' | 'P') {
    const folder = resolve(evidence, name); mkdirSync(folder)
    const env: NodeJS.ProcessEnv = { ...process.env, A3_API_PORT: '25001', A3_EVIDENCE: folder }
    for (const key of ['P12_D_BROWSER_CONTRACT', 'FIREBASE_AUTH_EMULATOR_HOST', 'GCLOUD_PROJECT', 'P09_B_BROWSER_DIAGNOSTICS']) delete env[key]
    if (mode) {
      const accounts = Object.fromEntries(['desktop', 'mobile'].map(project => [project,
        Object.fromEntries(['buyer', 'seller', 'other', 'admin'].map(role => [role, project + '-' + role + '@discovery.invalid']))]))
      const fixture = { runId: randomUUID(), mode,
        source: { baseURL: 'https://localhost:25001', observerURL: 'http://127.0.0.1:25003', observerKey: 'synthetic-discovery-observer-key' },
        restore: { baseURL: 'https://localhost:25002', observerURL: 'http://127.0.0.1:25004', observerKey: 'synthetic-discovery-observer-key' },
        accounts, passwords: Object.fromEntries(Object.values(accounts).flatMap(actors => Object.values(actors)).map(email => [email, 'synthetic-discovery-password'])),
        dist: { R0: resolve(folder, 'synthetic-R0'), R1: resolve(folder, 'synthetic-R1') },
        ...(mode === 'L' ? { emulator: { host: '127.0.0.1:25005', project: 'demo-discovery-only' } } : {}),
      }
      env.P12_D_BROWSER_CONTRACT = resolve(folder, 'synthetic-contract.json')
      writeFileSync(env.P12_D_BROWSER_CONTRACT, JSON.stringify(fixture, null, 2) + '\n')
    }
    const config = mode ? 'auth-e2e/release-runtime.config.ts' : 'auth-e2e/playwright.config.ts'
    const args = [resolve(frontend, 'node_modules/@playwright/test/cli.js'), 'test', '--config=' + config, '--list', '--reporter=json']
    writeFileSync(resolve(folder, 'command.json'), JSON.stringify({ executable: process.execPath, args, childLimitMs: 10_000,
      releaseContractProvided: !!mode, syntheticInputsOnly: true }, null, 2) + '\n')
    let result: CommandResult
    try {
      result = await runOwned(process.execPath, args, { cwd: frontend, env, output: resolve(folder, 'listing.json'), timeout: 10_000 })
    } catch (failure) {
      writeFileSync(resolve(folder, 'stderr.txt'), (failure as { result?: CommandResult }).result?.err ?? String(failure))
      throw failure
    }
    writeFileSync(resolve(folder, 'stderr.txt'), result.err)
    expect(result.code, 'Actual collection must succeed: ' + name + '; evidence: ' + folder).toBe(0)
    expect(result.timedOut).toBe(false)
    const report = JSON.parse(readFileSync(resolve(folder, 'listing.json'), 'utf8')) as Listing
    expect(report.errors ?? []).toEqual([])
    const ids = collected(report.suites).sort()
    expect(ids.length).toBe(new Set(ids).size)
    summary[name] = { ids, discovered: ids.length, browserTestsExecuted: 0 }
    return ids
  }
  try {
    const ordinary = await list('ordinary-without-release-contract')
    const mandatory = required('required-browser.json')
    expect(mandatory.length).toBeGreaterThan(0)
    expect(mandatory.filter(id => !ordinary.includes(id))).toEqual([])
    const dedicated = ['site-maintenance.spec.ts', 'package-runtime.spec.ts', 'release-recovery.spec.ts', 'release-public.spec.ts']
    const files = [...new Set(ordinary.map(id => id.slice(id.indexOf('#') + 1, id.indexOf(':'))))].sort()
    expect(files).toEqual(readdirSync(resolve(frontend, 'auth-e2e')).filter(file => file.endsWith('.spec.ts') && !dedicated.includes(file)).sort())
    const local = await list('release-L', 'L'), publicMode = await list('release-P', 'P')
    expect(local).toEqual(required('required-browser-release-recovery.json'))
    expect(publicMode).toEqual(required('required-browser-release-public.json'))
    expect(local).toEqual(['release#release-recovery.spec.ts:P12-D actual release exchange restores isolated commerce credentials and media'])
    expect(publicMode).toEqual(['release#release-public.spec.ts:P12-D public release rejects guest issuance and lookup without database or provider effects'])
    summary.status = 'passed'
  } finally {
    writeFileSync(resolve(evidence, 'discovery-summary.json'), JSON.stringify(summary, null, 2) + '\n')
    console.info('BROWSER_DISCOVERY_EVIDENCE=' + evidence)
  }
})
