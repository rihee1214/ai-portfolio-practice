import { request } from 'node:http'

// These fixtures observe local HTTP status/body and raw request targets. Native
// HTTP avoids Fetch's unrelated forbidden-port rule for OS-assigned listeners.
export function rawGet(port, path, {agent} = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, path, headers: { Accept: 'text/html' }, agent }, res => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', chunk => { body += chunk })
      res.on('end', () => resolve({ status: res.statusCode, type: res.headers['content-type'], body }))
      res.on('error', reject)
    })
    req.setTimeout(2000, () => req.destroy(new Error(`Test request timed out at 127.0.0.1:${port}${path}`)))
    req.on('error', error => {
      error.message = `Local HTTP 127.0.0.1:${port}${path}: ${error.message}`
      reject(error)
    })
    req.end()
  })
}
