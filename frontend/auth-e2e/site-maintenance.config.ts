import { defineConfig } from '@playwright/test'
import base from './playwright.config'

const project = process.env.P12_B_BROWSER_PROJECT
if (project !== 'desktop' && project !== 'mobile') throw new Error('P12-B requires an explicitly owned desktop or mobile database')
export default defineConfig({
  ...base,
  testMatch: 'site-maintenance.spec.ts', testIgnore: [],
  projects: base.projects!.filter(candidate => candidate.name === project),
  use: { ...base.use, trace: 'on' },
})
