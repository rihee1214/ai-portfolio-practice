import { test as base, expect, type BrowserContext, type APIRequestContext, type TestInfo, type Page, type Locator } from '@playwright/test'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout, clearTimeout } from 'node:timers'
import { Deadlines, type Deadline, type Event } from './deadline-core'
import { ScopeInventory } from './deadline-evidence'

let currentTest: Pick<TestInfo, 'testId' | 'title' | 'file'> | undefined
let timedOut = false
const inventory = process.env.A3_DEADLINE_INVENTORY
  ? new ScopeInventory(JSON.parse(readFileSync(process.env.A3_DEADLINE_INVENTORY,'utf8')).entries) : undefined
function emit(event: Event) {
  if (!process.env.A3_EVIDENCE) throw new Error('Deadline evidence directory required')
  inventory?.consume(event)
  if (event.event === 'end' && event.status === 'failed' && (event.elapsedMs ?? 0) >= event.deadlineAt - event.startedAt) timedOut = true
  appendFileSync(resolve(process.env.A3_EVIDENCE, 'browser-progress.ndjson'), JSON.stringify({ ...event,
    testId: currentTest?.testId, testTitle: currentTest?.title, file: currentTest?.file }) + '\n')
}
const deadlines = new Deadlines(emit)
export function currentDeadline(): Deadline {
  const scope=deadlines.current()
  if(!scope)throw new Error('No active deadline for browser work')
  scope.remaining();return scope
}
export const operation = <T>(name: string, work: (deadline: Deadline) => Promise<T>) => deadlines.scope('operation', name, work)
export const prepare = <T>(name: string, work: (deadline: Deadline) => Promise<T>) => deadlines.scope('prepare', name, work)
export const cleanup = <T>(name: string, work: (deadline: Deadline) => Promise<T>) => deadlines.scope('cleanup', name, work)
export const bindDeadline = <A extends unknown[], R>(work: (...args: A) => Promise<R>) => deadlines.bind(work)
/** Node-side emulator requests retain the command's budget through response body consumption. */
export function boundedFetch(input: Parameters<typeof fetch>[0], init: RequestInit = {}) {
  const remaining = Math.max(1, Math.floor(currentDeadline().remaining(10_000)))
  const timeout = AbortSignal.timeout(remaining)
  return fetch(input, { ...init, signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout })
}
export { expect }
export type { Deadline }
export type { Page, Locator, Browser, BrowserContext, TestInfo, APIRequestContext, APIResponse } from '@playwright/test'

function active(requireScope = false) {
  const scope = deadlines.current()
  if (timedOut && scope?.kind !== 'cleanup') throw new Error('A deadline has failed; subsequent browser commands are forbidden')
  if (requireScope && !scope) throw new Error('Browser command outside a declared deadline scope')
  scope?.remaining()
}
async function closeLate(close: () => Promise<void>) {
  let timer: ReturnType<typeof setTimeout> | undefined
  try { await Promise.race([close(), new Promise<never>((_, reject) => { timer=setTimeout(()=>reject(new Error('Late browser resource cleanup exceeded 5 seconds')),5000) })]) }
  finally { clearTimeout(timer) }
}
const locatorCache = new WeakMap<Locator, Locator>()
function guardLocator(locator: Locator): Locator {
  const old = locatorCache.get(locator); if (old) return old
  const proxy = new Proxy(locator, { get(target, key) {
    const value = Reflect.get(target, key, target)
    if (typeof value !== 'function') return value
    return (...args: unknown[]) => {
      active(['click','dblclick','fill','press','check','uncheck','selectOption','setInputFiles','evaluate','count','all','textContent','innerText','isVisible','isEnabled','_expect','waitFor','focus'].includes(String(key)))
      const result = Reflect.apply(value, target, args)
      if (key === 'all') return (result as Promise<Locator[]>).then(items => items.map(guardLocator))
      return result && typeof result === 'object' && 'locator' in result && 'click' in result ? guardLocator(result as Locator) : result
    }
  } })
  locatorCache.set(locator, proxy); return proxy
}
function guardPage(page: Page): Page {
  for (const name of ['locator','getByRole','getByLabel','getByText','getByTestId','getByPlaceholder','getByTitle','getByAltText'] as const) {
    const original = page[name].bind(page)
    Object.defineProperty(page, name, { configurable: true, value: (...args: unknown[]) => {
      active(); return guardLocator(Reflect.apply(original, page, args) as Locator)
    } })
  }
  for (const name of ['goto','reload','goBack','goForward','evaluate','evaluateHandle','waitForResponse','waitForRequest','waitForFunction','waitForURL','click','fill','press','bringToFront'] as const) {
    const original = page[name].bind(page)
    Object.defineProperty(page, name, { configurable: true, value: (...args: unknown[]) => { active(true); return Reflect.apply(original, page, args) } })
  }
  for (const device of [page.keyboard,page.mouse,page.touchscreen]) for (const name of ['press','type','insertText','down','up','move','click','dblclick','wheel','tap']) {
    const value=Reflect.get(device,name)
    if(typeof value!=='function')continue
    Object.defineProperty(device,name,{configurable:true,value:(...args:unknown[])=>{active(true);return Reflect.apply(value,device,args)}})
  }
  return page
}

/** Requests have an individual finite ceiling as well as the enclosing command's shared deadline. */
function boundRequests(request: APIRequestContext) {
  for (const method of ['get', 'post', 'put', 'patch', 'delete', 'head', 'fetch'] as const) {
    const original = request[method].bind(request)
    Object.defineProperty(request, method, { configurable: true, value: (url: string, options: Record<string, unknown> = {}) => {
      active(true)
      const requested = typeof options.timeout === 'number' && options.timeout > 0 ? options.timeout : 10_000
      const timeout = deadlines.current()?.remaining(Math.min(requested, 10_000)) ?? Math.min(requested, 10_000)
      return original(url, { ...options, timeout })
    } })
  }
}

export const test = base.extend<{ deadlineLifecycle: void }>({
  deadlineLifecycle: [async ({ browser, context, request }, use, info) => {
    currentTest = info; timedOut = false; inventory?.reset()
    const owned = new Set<BrowserContext>([context])
    const original = browser.newContext.bind(browser)
    const configure = (c: BrowserContext) => {
      owned.add(c); c.setDefaultTimeout(10_000); c.setDefaultNavigationTimeout(10_000); boundRequests(c.request)
      const newPage = c.newPage.bind(c)
      c.newPage = async () => {
        active()
        const work = async () => {
          const page=await newPage()
          try { active(); return guardPage(page) } catch(error) { await closeLate(()=>page.close()); throw error }
        }
        return deadlines.current() ? deadlines.current()!.run(work, 10_000) : prepare('browser page connection', work)
      }
      return c
    }
    configure(context); boundRequests(request)
    browser.newContext = async options => {
      active()
      const work = async () => {
        const c=await original(options)
        try { active(); return configure(c) } catch(error) { await closeLate(()=>c.close()); throw error }
      }
      return deadlines.current() ? deadlines.current()!.run(work, 10_000) : prepare('browser context connection', work)
    }
    try { await use(); if (timedOut) throw new Error('Test swallowed a failed deadline') }
    finally {
      browser.newContext = original
      await cleanup('owned browser contexts', async () => {
        const results = await Promise.allSettled([...owned].map(c => c.close()).concat(request.dispose()))
        const rejected = results.find(result => result.status === 'rejected')
        if (rejected?.status === 'rejected') throw rejected.reason
      })
    }
  }, { auto: true }],
})
