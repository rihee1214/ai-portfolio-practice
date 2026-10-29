import {expect,type Page} from '@playwright/test'
import {request as httpRequest} from 'node:http'
// Real Java login from a dedicated loopback client keeps the suite's aggregate source quota isolated.
// Cookies, CSRF, body and server response stay intact; no rate-limit configuration is changed.
export async function isolateLoginSource(page: Page, source: '127.0.0.3'|'127.0.0.4'='127.0.0.3') { await isolateSource(page,'/api/guest/access/login',source) }
export async function isolatePhoneSource(page: Page) { await isolateSource(page,'/api/guest/verifications','127.0.0.4') }
async function isolateSource(page:Page,path:'/api/guest/access/login'|'/api/guest/verifications',source:'127.0.0.3'|'127.0.0.4') {
  const port=Number(process.env.A3_API_PORT);if(!Number.isInteger(port)||port<1||port>65535)throw new Error('isolated Java loopback port required')
  await page.route('**'+path, async route => {
    if(new URL(route.request().url()).hostname!=='127.0.0.1'||new URL(route.request().url()).pathname!==path||route.request().method()!=='POST')throw new Error('loopback browser destination required')
    const originalHeaders = await route.request().allHeaders()
    expect(Object.hasOwn(originalHeaders, 'cookie')).toBe(true)
    expect(Object.hasOwn(originalHeaders, 'x-csrf-token')).toBe(true)
    const result = await new Promise<{ status: number; headers: Record<string, string>; body: Buffer }>((resolve, reject) => {
      const request = httpRequest({ hostname: '127.0.0.1', port, localAddress: source, path, method: 'POST', headers: { ...originalHeaders, host: '127.0.0.1:' + process.env.A3_API_PORT } }, response => {
        const chunks: Buffer[] = []; response.on('data', chunk => chunks.push(Buffer.from(chunk))); response.on('error', reject)
        response.on('end', () => {
          const headers: Record<string, string> = {}
          for (const [name, value] of Object.entries(response.headers)) if (value !== undefined && name !== 'transfer-encoding') headers[name] = Array.isArray(value) ? value.join('\n') : value
          resolve({ status: response.statusCode ?? 500, headers, body: Buffer.concat(chunks) })
        })
      }); request.on('error', reject); request.end(route.request().postData() ?? '')
    })
    await route.fulfill(result)
  })
}
