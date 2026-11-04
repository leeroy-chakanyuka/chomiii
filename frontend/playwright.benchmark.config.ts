import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/benchmark-live.spec.ts',
  timeout: 600_000,
  use: { baseURL: process.env.CHOMIII_BENCH_UI ?? 'http://127.0.0.1:5173', trace: 'off', screenshot: 'off' },
})
