import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { apiProxy } from '../vite/apiProxy.ts'

// Same production proxy implementation, dedicated D4 API port.
export default defineConfig({ plugins: [react(), apiProxy('http://127.0.0.1:18080')] })
