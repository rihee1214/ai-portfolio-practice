import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// DOM tests never start the development API proxy or contact the database.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['tests/unit/**/*.test.tsx'],
    setupFiles: ['tests/unit/setup.ts'],
  },
})
