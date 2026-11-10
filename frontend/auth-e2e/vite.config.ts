import { defineConfig } from 'vite'
import {resolve} from 'node:path'
import {Agent} from 'node:http'
import react from '@vitejs/plugin-react'
import { apiProxy } from '../vite/apiProxy.ts'
import {diagnostic} from './diagnostics'
const port = process.env.A3_API_PORT
if (!port || !/^\d+$/.test(port)) throw new Error('Requires isolated test API port')
diagnostic('vite:config')
export default defineConfig({
  // The logout/lost-response contract drives the actual class and its app-wide lifetime bus.
  build: {rolldownOptions: {
    input: [resolve('index.html'), resolve('src/guest/GuestAccessStore.ts')],
    preserveEntrySignatures: 'strict',
  }},
  plugins: [react(), apiProxy('http://127.0.0.1:' + port, new Agent({keepAlive:true})),{name:'optional-browser-diagnostics',configureServer(server){diagnostic('vite:configure');server.httpServer?.once('listening',()=>diagnostic('vite:listening'))}}],
})
