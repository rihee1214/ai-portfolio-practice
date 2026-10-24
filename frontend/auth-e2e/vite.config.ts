import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { apiProxy } from '../vite/apiProxy.ts'
const port = process.env.A3_API_PORT
if (!port || !/^\d+$/.test(port)) throw new Error('Requires isolated test API port')
export default defineConfig({ plugins: [react(), apiProxy('http://127.0.0.1:' + port)] })
