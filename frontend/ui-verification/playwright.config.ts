import { defineConfig } from '@playwright/test'
import { resolve } from 'node:path'

const stage = process.env.UI_STAGE
const evidence = process.env.UI_EVIDENCE
const appRoot = process.env.UI_APP_ROOT
const bundle = process.env.UI_BUNDLE
if (!stage || !['before', 'after'].includes(stage) || !evidence || !appRoot || !bundle) {
  throw new Error('Set UI_STAGE (before or after), UI_EVIDENCE, UI_APP_ROOT, and UI_BUNDLE')
}

const port = Number(process.env.UI_PORT ?? '43139')
const baseURL = `http://127.0.0.1:${port}`
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('../.local-tools/playwright')
process.env.A3_EVIDENCE = resolve(evidence, stage)
delete process.env.A3_DEADLINE_INVENTORY

export default defineConfig({
  testDir: '.',
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 0,
  globalSetup: './preview.ts',
  outputDir: resolve(evidence, stage, 'results'),
  reporter: [['list'], ['json', { outputFile: resolve(evidence, stage, 'report.json') }]],
  use: { baseURL, locale: 'ko-KR', trace: 'retain-on-failure', screenshot: 'only-on-failure',
    actionTimeout: 10_000, navigationTimeout: 10_000, launchOptions: { timeout: 30_000 } },
})
