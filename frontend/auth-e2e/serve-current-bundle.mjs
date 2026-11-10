import {createHash} from 'node:crypto'
import {mkdir, readFile, readdir, stat, writeFile} from 'node:fs/promises'
import {isAbsolute, join, relative, resolve, sep} from 'node:path'
import process from 'node:process'
import {performance} from 'node:perf_hooks'
import {gzipSync} from 'node:zlib'
import {URL, fileURLToPath, pathToFileURL} from 'node:url'

// The browser fixture exercises development-only API errors, the local phone emulator and StrictMode.
async function viteInDevelopment() {
  process.env.NODE_ENV = 'development'
  return import('vite')
}

async function outputFiles(directory, root = directory) {
  const result = []
  for (const entry of await readdir(directory, {withFileTypes:true})) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) result.push(...await outputFiles(path, root))
    else if (entry.isFile()) {
      const bytes = await readFile(path)
      result.push({file:relative(root, path).split(sep).join('/'), bytes:bytes.length,
        sha256:createHash('sha256').update(bytes).digest('hex')})
    }
  }
  return result.sort((a,b) => a.file.localeCompare(b.file))
}

export async function buildCurrentApplication(root, evidence, configFile) {
  const outDir = join(evidence, 'browser-app')
  // Atomically reserve this run's output. A prior build, even a partial one, must never be served.
  await mkdir(outDir)
  const vite = await viteInDevelopment()
  const start = performance.now()
  const options = {root, mode:'development', configFile, cacheDir:join(evidence,'vite-cache'),
    build:{outDir, emptyOutDir:false, minify:true, manifest:true, reportCompressedSize:false}}
  await vite.build(options)
  const manifest = JSON.parse(await readFile(join(outDir,'.vite','manifest.json'),'utf8'))
  if (!manifest['index.html']?.isEntry) throw new Error('Missing current application entry')
  const files = await outputFiles(outDir)
  await writeFile(join(evidence,'browser-app-build.json'), JSON.stringify({
    mode:'development',nodeEnv:process.env.NODE_ENV, buildMs:performance.now()-start,
    entry:manifest['index.html'].file, files
  }, null, 2), {flag:'wx'})
  return options
}

// Only content-hashed JS/CSS from this run are shared across navigations.
export async function compressedAssets(outDir) {
  const assets = new Map()
  const directory = join(outDir, 'assets')
  for (const file of await readdir(directory,{withFileTypes:true})) {
    if (!file.isFile() || !/^[\w.-]+-[\w-]{8}\.(?:js|css)$/.test(file.name)) continue
    const bytes = await readFile(join(directory,file.name))
    if (bytes.length < 1024) continue
    assets.set('/assets/'+file.name, {
      type:file.name.endsWith('.css')?'text/css':'text/javascript',
      bytes:gzipSync(bytes)
    })
  }
  return function serveAsset(request,response,next) {
    const asset = assets.get(request.url)
    const acceptsGzip = request.headers['accept-encoding']?.split(',').some(value=>value.trim().toLowerCase()==='gzip')
    if (!asset || request.method !== 'GET' || !acceptsGzip || request.headers.range) return next()
    response.writeHead(200, {'Content-Type':asset.type,'Content-Encoding':'gzip',
      'Content-Length':asset.bytes.length,'Cache-Control':'public, max-age=31536000, immutable','Vary':'Accept-Encoding'})
    response.end(asset.bytes)
  }
}

async function serve() {
  const root = fileURLToPath(new URL('../',import.meta.url))
  const evidence = process.env.A3_EVIDENCE
  const allowed = resolve(root,'../.local-tools')
  if (!evidence || !isAbsolute(evidence)) throw new Error('Absolute browser evidence directory required')
  const child = relative(allowed, evidence)
  if (!child || child === '..' || child.startsWith('..'+sep) || isAbsolute(child) || !(await stat(evidence)).isDirectory()) {
    throw new Error('Existing isolated browser evidence directory required')
  }
  const options = await buildCurrentApplication(root, evidence, join(root,'auth-e2e/vite.config.ts'))
  const vite = await viteInDevelopment()
  const serveAsset = await compressedAssets(options.build.outDir)
  const server = await vite.preview({...options,plugins:[{name:'current-browser-assets',
    configurePreviewServer(server){ server.middlewares.use(serveAsset) }
  }],preview:{host:'127.0.0.1',port:24176,strictPort:true}})
  const close = async () => { await server.close() }
  process.once('SIGTERM', close)
  process.once('SIGINT', close)
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) await serve()
