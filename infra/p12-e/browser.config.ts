import { defineConfig, devices } from '../../frontend/node_modules/@playwright/test/index.mjs'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
const access=JSON.parse(readFileSync(process.env.P12_E_ACCESS!,'utf8'))
export default defineConfig({testDir:'.',testMatch:'reproduction.spec.ts',workers:1,retries:0,forbidOnly:true,timeout:0,
 outputDir:resolve(process.env.A3_EVIDENCE!,'browser-results'),
 reporter:[[resolve('auth-e2e/deadline-reporter.ts')],['list'],['json',{outputFile:resolve(process.env.A3_EVIDENCE!,'browser-report.json')}]],
 use:{baseURL:access.baseURL,ignoreHTTPSErrors:true,httpCredentials:{username:access.gateUser,password:access.gatePassword,send:'always'},trace:'off',screenshot:'off',actionTimeout:10000,navigationTimeout:10000,launchOptions:{timeout:30000}},
 projects:[{name:'desktop',use:{...devices['Desktop Chrome']}},{name:'mobile',use:{...devices['Pixel 7']}}]})
