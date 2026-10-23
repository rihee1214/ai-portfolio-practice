import type { Plugin, ProxyOptions, ViteDevServer } from 'vite'

// Runs before Vite's proxy and SPA fallback, including local build preview.
function requestPathGuard(server: Pick<ViteDevServer, 'middlewares'>) {
  server.middlewares.use((req, res, next) => {
    const rawPath = (req.url ?? '/').split('?')[0]
    function reject(status: 400 | 404) {
      res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      res.end(JSON.stringify({ error: status === 400 ? 'INVALID_PATH' : 'NOT_FOUND' }))
    }
    let path: string
    try { path = decodeURIComponent(rawPath) } catch { reject(400); return }
    if (/^\/actuator(?:\/|$)/i.test(path) && rawPath !== '/actuator/health') {
      reject(404)
      return
    }

    // Proxy/servlet path normalization must not turn /api/../... into another namespace.
    // Reject structural aliases rather than rewriting them. Unicode IDs and query values remain valid.
    const hasControl = [...path].some(character => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)
    if (!path.startsWith('/') || path.includes('//') || hasControl || /[\\;%?#]/.test(path)
      || /(?:^|\/)\.{1,2}(?:\/|$)/.test(path) || /%2f/i.test(rawPath)) {
      reject(400)
      return
    }
    if (/^\/api(?:\/|$)/i.test(path) && !/^\/api(?:\/|$)/.test(rawPath)) {
      reject(404)
      return
    }
    next()
  })
}

export function apiProxy(target = 'http://127.0.0.1:8080'): Plugin {
  const options: ProxyOptions = {
    target,
    proxyTimeout: 10_000,
    configure(proxy) {
      proxy.on('proxyRes', (upstream, _req, downstream) => {
        // pipe() forwards data/end, but not an incomplete source's failure.
        // Never end() a truncated JSON response: the client must see transport failure.
        const fail = () => { if (!downstream.destroyed) downstream.destroy() }
        upstream.once('aborted', fail)
        upstream.once('error', fail)
        upstream.once('close', () => { if (!upstream.complete) fail() })
        downstream.once('close', () => {
          if (!downstream.writableFinished) upstream.destroy()
        })
      })
      proxy.on('error', (_error, _req, res) => {
        if ('writeHead' in res && res.headersSent && !res.writableEnded) {
          res.destroy()
          return
        }
        if ('writeHead' in res && !res.headersSent && !res.writableEnded) {
          res.writeHead(502, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
          res.end(JSON.stringify({ error: 'UPSTREAM_UNAVAILABLE' }))
        }
      })
    },
  }
  const proxy = { '^/actuator/health(?:\\?|$)': options, '^/api(?:/|\\?|$)': options }
  return {
    name: 'marketplace-api-proxy',
    config: () => ({ server: { proxy }, preview: { proxy } }),
    configureServer: requestPathGuard,
    configurePreviewServer: requestPathGuard,
  }
}
