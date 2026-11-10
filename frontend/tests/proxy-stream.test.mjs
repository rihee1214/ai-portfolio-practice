import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Agent, createServer } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'
import { setInterval, clearInterval } from 'node:timers'
import { performance } from 'node:perf_hooks'
/* global AbortSignal */
import { createServer as createViteServer, preview } from 'vite'
import { resolve } from 'node:path'
import { proxyViteConfig } from './proxyViteConfig.mjs'
import { requestJson } from '../src/api/http.ts'

test('pooled preview reuses the upstream socket but forwards only each request’s own credentials',async()=>{
  let connections=0
  const upstream=createServer(async(req,res)=>{
    if(req.url==='/api/interrupted'){
      res.writeHead(200,{'Content-Type':'application/json'})
      res.write('{"partial":');await delay(25);res.destroy();return
    }
    res.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store'})
    res.end(JSON.stringify({cookie:req.headers.cookie??null,csrf:req.headers['x-csrf-token']??null}))
  })
  upstream.on('connection',()=>connections++)
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve))
  const agent=new Agent({keepAlive:true})
  let server
  try{
    const config=await proxyViteConfig('http://127.0.0.1:'+upstream.address().port,agent)
    server=await preview({...config,preview:{host:'127.0.0.1',port:0}})
    const url='http://127.0.0.1:'+server.httpServer.address().port+'/api/whoami'
    for(const id of ['first','second',null]){
      const headers=id?{Cookie:'member='+id,'X-CSRF-TOKEN':id}:{}
      const r=await fetch(url,{headers})
      assert.equal(r.status,200)
      assert.equal(r.headers.get('Cache-Control'),'no-store')
      assert.deepEqual(await r.json(),{cookie:id?'member='+id:null,csrf:id})
    }
    assert.equal(connections,1)
    const interrupted=await requestJson(url.replace('whoami','interrupted'))
    assert.equal(interrupted.kind,'network')
    const renewed=await fetch(url,{headers:{Cookie:'member=fresh'}})
    assert.deepEqual(await renewed.json(),{cookie:'member=fresh',csrf:null})
    assert.equal(connections,2)
  }finally{
    if(server)await server.close()
    agent.destroy()
    await new Promise(resolve=>upstream.close(resolve))
  }
})

for (const mode of ['dev', 'preview']) {
  test(`${mode}: real partial responses terminate on upstream failure`, { concurrency: true }, async t => {
    const upstream = createServer(async (req, res) => {
      const scenario = new URL(req.url, 'http://localhost').searchParams.get('case')
      if (scenario === 'silent') return
      res.writeHead(200, { 'Content-Type': 'application/json' })
      if (scenario === 'healthy') { res.end('{"status":"UP"}'); return }
      res.write('{"status":')
      if (scenario === 'disconnect') { await delay(300); res.destroy(); return }
      if (scenario === 'stream') {
        const interval = setInterval(() => res.write(' '), 250)
        res.on('close', () => clearInterval(interval))
        await delay(12_000)
        clearInterval(interval)
        res.end('"UP"}')
      }
    })
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
    let vite
    try {
      const config = await proxyViteConfig(`http://127.0.0.1:${upstream.address().port}`)
      vite = mode === 'dev'
        ? await createViteServer({ ...config, server: { host: '127.0.0.1', port: 0 } })
        : await preview({ ...config, preview: { host: '127.0.0.1', port: 0 } })
      assert.equal(resolve(vite.config.cacheDir), resolve(config.cacheDir))
      t.diagnostic(`owned cache: ${vite.config.cacheDir}`)
      if (mode === 'dev') await vite.listen()
      const base = `http://127.0.0.1:${vite.httpServer.address().port}/actuator/health`
      await Promise.all(['silent', 'stall', 'disconnect', 'stream'].map(scenario => t.test(scenario, { concurrency: true }, async () => {
        const start = performance.now()
        const result = await requestJson(`${base}?case=${scenario}`, { signal: AbortSignal.timeout(15_000) })
        const elapsed = Math.round(performance.now() - start)
        t.diagnostic(`${scenario}: ${elapsed}ms ${JSON.stringify(result)}`)
        if (scenario === 'silent') {
          assert.equal(result.kind, 'http'); assert.equal(result.status, 502)
          assert.deepEqual(result.data, { error: 'UPSTREAM_UNAVAILABLE' })
        } else if (scenario === 'stream') {
          assert.equal(result.kind, 'success'); assert.deepEqual(result.data, { status: 'UP' })
          assert.ok(elapsed >= 11_500, 'continuous progress may exceed the idle timeout')
        } else assert.equal(result.kind, 'network', 'incomplete upstream must fail transport, not wait for caller cancellation')
        if (scenario === 'silent' || scenario === 'stall') assert.ok(elapsed >= 9000 && elapsed < 14_000)
        if (scenario === 'disconnect') assert.ok(elapsed < 3000)
        const recovered = await requestJson(`${base}?case=healthy`)
        assert.deepEqual(recovered, { kind: 'success', status: 200, data: { status: 'UP' } })
      })))
      await t.test('caller cancels while reading body and can retry', async () => {
        const result = await requestJson(`${base}?case=stall`, { signal: AbortSignal.timeout(300) })
        assert.equal(result.kind, 'cancelled')
        assert.equal((await requestJson(`${base}?case=healthy`)).kind, 'success')
      })
    } finally {
      if (mode === 'dev') await vite?.close()
      else if (vite) { vite.httpServer.closeAllConnections(); await new Promise(resolve => vite.httpServer.close(resolve)) }
      upstream.closeAllConnections()
      await new Promise(resolve => upstream.close(resolve))
    }
  })
}
