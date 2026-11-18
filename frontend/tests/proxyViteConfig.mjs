import { mkdir, mkdtemp } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import process from 'node:process'
import { apiProxy } from '../vite/apiProxy.ts'

// Vite can remove its dependency cache on startup. Each owned test server gets its
// own directory; retain it for diagnostics instead of touching a running dev server.
export async function proxyViteConfig(target,agent) {
  const evidence = fileURLToPath(new URL('../../.local-tools/', import.meta.url))
  await mkdir(evidence, { recursive: true })
  const cacheDir = await mkdtemp(join(evidence, 'vite-proxy-test-'))
  return {
    root: fileURLToPath(new URL('..', import.meta.url)),
    cacheDir,
    configFile: false,
    ...(process.env.D2_PRODUCTION_DIST ? {build:{outDir:process.env.D2_PRODUCTION_DIST}} : {}),
    plugins: [apiProxy(target,agent)],
    logLevel: 'silent',
  }
}
