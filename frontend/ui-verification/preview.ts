import { stat, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { preview } from 'vite'
import { Deadlines } from '../auth-e2e/deadline-core'

const deadlines = new Deadlines(() => {})

export default async function setup() {
  const outDir = process.env.UI_BUNDLE!
  const host = '127.0.0.1'
  const port = Number(process.env.UI_PORT ?? '43139')
  const server = await deadlines.scope('prepare', 'preview starts with the designated bundle', async () => {
    if (!(await stat(join(outDir, 'index.html'))).isFile()) throw new Error('Build the isolated UI bundle first')
    await mkdir(process.env.A3_EVIDENCE!, { recursive: true })
    return preview({ root: process.env.UI_APP_ROOT!, configFile: false,
      build: { outDir }, preview: { host, port, strictPort: true } })
  })
  const close = () => deadlines.scope('cleanup', 'close the isolated preview', async () => {
    if ('closeAllConnections' in server.httpServer) server.httpServer.closeAllConnections()
    await server.close()
  })
  try {
    await deadlines.scope('prepare', 'preview responds with the app shell', async deadline => {
      const response = await fetch(`http://${host}:${port}`, {
        signal: AbortSignal.timeout(Math.floor(deadline.remaining(10_000))),
      })
      if (response.status !== 200 || !(await response.text()).includes('<div id="root">')) {
        throw new Error('The isolated preview does not serve the designated app shell')
      }
    })
    return close
  } catch (error) {
    await close()
    throw error
  }
}
