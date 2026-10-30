import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'
if (!process.env.A3_API_PORT || !process.env.A3_EVIDENCE) throw new Error('Run backend/verify-db.ps1 -RunBrowser')
process.env.PLAYWRIGHT_BROWSERS_PATH = resolve('../.local-tools/playwright')
export default defineConfig({
  testDir: '.', testMatch: '*.spec.ts', workers: 1, retries: 0, forbidOnly: true, timeout: 30000,
  outputDir: resolve(process.env.A3_EVIDENCE, 'browser-results'),
  reporter: [['list'], ['json', { outputFile: resolve(process.env.A3_EVIDENCE, 'browser-report.json') }],
    ...(process.env.P09_B_BROWSER_DIAGNOSTICS==='1' ? [[resolve(process.env.P09_B_DIAGNOSTIC_REPORTER!)]] as [[string]] : [])],
  use: { baseURL: 'http://127.0.0.1:24176', trace: 'off', screenshot: 'only-on-failure' },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'node node_modules/vite/bin/vite.js --config auth-e2e/vite.config.ts --host 127.0.0.1 --port 24176 --strictPort',
    cwd: resolve('.'), url: 'http://127.0.0.1:24176', reuseExistingServer: false, timeout: 30000,
  },
})
