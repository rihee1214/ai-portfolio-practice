import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'
import { contract } from './release-contract'

const input = contract()
if (!process.env.A3_EVIDENCE) throw new Error('Fresh release browser evidence path required')
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('../.local-tools/playwright')
process.env.A3_DEADLINE_INVENTORY = resolve('auth-e2e/deadline-inventory.json')
export default defineConfig({
  testDir: '.', testMatch: input.mode === 'L' ? 'release-recovery.spec.ts' : 'release-public.spec.ts',
  workers: 1, retries: 0, forbidOnly: true, timeout: 0,
  outputDir: resolve(process.env.A3_EVIDENCE, 'browser-results'),
  reporter: [[resolve('auth-e2e/deadline-reporter.ts')], ['list'], ['json', { outputFile: resolve(process.env.A3_EVIDENCE, 'browser-report.json') }]],
  use: { baseURL: input.source.baseURL, ignoreHTTPSErrors: true, trace: 'off', screenshot: 'only-on-failure',
    actionTimeout: 10_000, navigationTimeout: 10_000, launchOptions: { timeout: 30_000 },
    ...(input.source.gateUser ? { httpCredentials: { username: input.source.gateUser, password: input.source.gatePassword!, send: 'always' as const } } : {}) },
  projects: [{ name: 'release', use: { ...devices['Desktop Chrome'] } }],
})
