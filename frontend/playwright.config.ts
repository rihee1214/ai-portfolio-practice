import { defineConfig, devices } from '@playwright/test'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const local = (path: string) => fileURLToPath(new URL(path, import.meta.url))
const jar = local('../.local-tools/d4-build/marketplace-api-0.0.1-SNAPSHOT.jar')
if (!existsSync(jar)) throw new Error('Build the D4 JAR first: see docs/work-items/P01-D4.md')
process.env.PLAYWRIGHT_BROWSERS_PATH = local('../.local-tools/playwright')

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  outputDir: '../.local-tools/d4-playwright/results',
  reporter: [['list'], ['json', { outputFile: '../.local-tools/d4-playwright/report.json' }]],
  use: {
    baseURL: 'http://127.0.0.1:24174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium-desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'chromium-mobile', use: { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } } },
  ],
  // Never adopt or stop an existing server. An occupied port fails the run.
  webServer: [
    {
      command: `"${local('../.local-tools/jdk-21.0.12.1+1/bin/java.exe')}" -jar "${jar}" --server.address=127.0.0.1 --server.port=18080 --spring.config.location=classpath:/application.properties --spring.profiles.active=d4-e2e --spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`,
      url: 'http://127.0.0.1:18080/actuator/health',
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: 'node node_modules/vite/bin/vite.js --config e2e/vite.config.ts --host 127.0.0.1 --port 24174 --strictPort',
      url: 'http://127.0.0.1:24174',
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
})
