import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'
if (!process.env.A3_API_PORT || !process.env.A3_EVIDENCE) throw new Error('Run backend/verify-db.ps1 -RunBrowser')
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('../.local-tools/playwright')
// Mandatory for the product suite; isolated infrastructure meta tests use their own config.
process.env.A3_DEADLINE_INVENTORY = resolve('auth-e2e/deadline-inventory.json')
export default defineConfig({
  testDir: '.', testMatch: '*.spec.ts', testIgnore: ['site-maintenance.spec.ts', 'package-runtime.spec.ts'], workers: 1, retries: 0, forbidOnly: true, timeout: 0,
  outputDir: resolve(process.env.A3_EVIDENCE, 'browser-results'),
  reporter: [[resolve('auth-e2e/deadline-reporter.ts')], ['list'], ['json', { outputFile: resolve(process.env.A3_EVIDENCE, 'browser-report.json') }],
    ...(process.env.P09_B_BROWSER_DIAGNOSTICS==='1' ? [[resolve(process.env.P09_B_DIAGNOSTIC_REPORTER!)]] as [[string]] : [])],
  use: { baseURL: 'http://127.0.0.1:24176', trace: 'off', screenshot: 'only-on-failure',
    actionTimeout: 10_000, navigationTimeout: 10_000, launchOptions: { timeout: 30_000 } },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'node auth-e2e/serve-current-bundle.mjs',
    cwd: resolve('.'), url: 'http://127.0.0.1:24176', reuseExistingServer: false, timeout: 30000,
  },
})
