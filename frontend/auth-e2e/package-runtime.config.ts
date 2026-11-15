import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'

const baseURL = process.env.P12_C_BASE_URL
if (!baseURL || !/^https:\/\/localhost:\d+$/.test(baseURL) || !process.env.A3_EVIDENCE
  || !process.env.P12_C_ACCOUNTS || !process.env.P12_C_PASSWORDS || !process.env.P12_C_GATE_USER || !process.env.P12_C_GATE_PASSWORD
  || !process.env.P12_C_OBSERVER_URL || !process.env.P12_C_OBSERVER_KEY || !process.env.P12_C_DIST
  || !process.env.P12_C_LEGACY_DIST || !/^https:\/\/localhost:\d+$/.test(process.env.P12_C_LEGACY_BASE_URL ?? ''))
  throw new Error('Run the isolated package-runtime database group with its actual HTTPS NGINX/JAR/worker')
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('../.local-tools/playwright')
process.env.A3_DEADLINE_INVENTORY = resolve('auth-e2e/deadline-inventory.json')
export default defineConfig({
  testDir: '.', testMatch: 'package-runtime.spec.ts', workers: 1, retries: 0, forbidOnly: true, timeout: 0,
  outputDir: resolve(process.env.A3_EVIDENCE, 'browser-results'),
  reporter: [[resolve('auth-e2e/deadline-reporter.ts')], ['list'], ['json', { outputFile: resolve(process.env.A3_EVIDENCE, 'browser-report.json') }]],
  use: { baseURL, ignoreHTTPSErrors: true, httpCredentials: { username: process.env.P12_C_GATE_USER,
    password: process.env.P12_C_GATE_PASSWORD, send: 'always' }, trace: 'off', screenshot: 'only-on-failure',
    actionTimeout: 10_000, navigationTimeout: 10_000, launchOptions: { timeout: 30_000 } },
  projects: [{ name: 'desktop', use: { ...devices['Desktop Chrome'] } }, { name: 'mobile', use: { ...devices['Pixel 7'] } }],
})
