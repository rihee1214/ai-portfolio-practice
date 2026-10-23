import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { apiProxy } from './vite/apiProxy.ts'

export default defineConfig({ plugins: [react(), apiProxy()] })
