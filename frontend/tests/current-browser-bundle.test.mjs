import assert from 'node:assert/strict'
import {Buffer} from 'node:buffer'
import test from 'node:test'
import {createHash} from 'node:crypto'
import {mkdir, mkdtemp, readFile, writeFile} from 'node:fs/promises'
import {join} from 'node:path'
import {URL, fileURLToPath} from 'node:url'
import {gunzipSync} from 'node:zlib'
import {buildCurrentApplication,compressedAssets} from '../auth-e2e/serve-current-bundle.mjs'

test('the browser server builds current development sources in an exclusive output directory', async () => {
  const local = fileURLToPath(new URL('../../.local-tools/',import.meta.url))
  const run = await mkdtemp(join(local,'p10-a-bundle-contract-'))
  const root = join(run,'app'), first = join(run,'first'), second = join(run,'second')
  await Promise.all([root,first,second].map(path => mkdir(path)))
  await writeFile(join(root,'index.html'),'<script type="module" src="/main.js"></script>')
  const source = marker => "document.title=import.meta.env.DEV ? '"+marker+"' : 'PRODUCTION_ONLY_MARKER'"
  await writeFile(join(root,'main.js'),source('FIRST_SOURCE_MARKER'))
  await buildCurrentApplication(root,first,false)
  const asset = async evidence => {
    const report = JSON.parse(await readFile(join(evidence,'browser-app-build.json'),'utf8'))
    const file = report.files.find(f => f.file === report.entry)
    assert.equal(report.nodeEnv,'development')
    const bytes = await readFile(join(evidence,'browser-app',report.entry))
    assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256)
    return bytes.toString('utf8')
  }
  const original = await asset(first)
  assert.match(original,/FIRST_SOURCE_MARKER/)
  assert.doesNotMatch(original,/PRODUCTION_ONLY_MARKER/)
  await writeFile(join(root,'main.js'),source('SECOND_SOURCE_MARKER'))
  await assert.rejects(buildCurrentApplication(root,first,false),{code:'EEXIST'})
  assert.equal(await asset(first),original)
  await buildCurrentApplication(root,second,false)
  const latest = await asset(second)
  assert.match(latest,/SECOND_SOURCE_MARKER/)
  assert.doesNotMatch(latest,/FIRST_SOURCE_MARKER|PRODUCTION_ONLY_MARKER/)
})

test('cached browser assets contain only the current hashed script or style and never an API response',async()=>{
  const local=fileURLToPath(new URL('../../.local-tools/',import.meta.url))
  const dir=await mkdtemp(join(local,'p10-b-static-contract-'))
  await mkdir(join(dir,'assets'))
  const bytes=Buffer.from('current source '.repeat(160))
  await writeFile(join(dir,'assets','entry-AbC12345.js'),bytes)
  await writeFile(join(dir,'assets','unhashed.js'),bytes)
  const serve=await compressedAssets(dir)
  const get=url=>{
    let headers=null,body=null,delegated=false
    serve({url,method:'GET',headers:{'accept-encoding':'gzip, deflate'}},{
      writeHead(code,h){assert.equal(code,200);headers=h},end(value){body=value}
    },()=>{delegated=true})
    return {headers,body,delegated}
  }
  const asset=get('/assets/entry-AbC12345.js')
  assert.equal(asset.delegated,false)
  assert.equal(asset.headers['Content-Encoding'],'gzip')
  assert.match(asset.headers['Cache-Control'],/immutable/)
  assert.deepEqual(gunzipSync(asset.body),bytes)
  for(const url of ['/','/products/example','/assets/unhashed.js','/api/questions/example/messages/example/images/example']){
    assert.deepEqual(get(url),{headers:null,body:null,delegated:true})
  }
})
