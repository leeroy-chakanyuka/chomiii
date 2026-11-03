import { defineConfig, configDefaults } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Backend origin used by the development proxy. Override per environment, e.g.
// `BACKEND_URL=http://localhost:9000 npm run dev`. Defaults match the backend's
// Spring Boot default port.
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: backendUrl,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
