import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { apiProxy } from '../vite/apiProxy.ts'
import {diagnostic} from './diagnostics'
const port = process.env.A3_API_PORT
if (!port || !/^\d+$/.test(port)) throw new Error('Requires isolated test API port')
diagnostic('vite:config')
export default defineConfig({ plugins: [react(), apiProxy('http://127.0.0.1:' + port),{name:'optional-browser-diagnostics',configureServer(server){diagnostic('vite:configure');server.httpServer?.once('listening',()=>diagnostic('vite:listening'))}}] })
