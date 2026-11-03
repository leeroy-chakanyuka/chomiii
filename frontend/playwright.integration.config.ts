import { defineConfig } from '@playwright/test'

/**
 * Real backend integration suite. Requires the chomiii backend (Java, with
 * PostgreSQL) already running at BACKEND_URL (default http://localhost:8080).
 * No skip gate: a missing or unhealthy backend fails these tests loudly.
 */
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/integration.spec.ts',
  timeout: 30_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://127.0.0.1:5175',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --host --port 5175 --strictPort',
    url: 'http://127.0.0.1:5175',
    env: { BACKEND_URL: backendUrl },
    reuseExistingServer: !process.env.CI,
  },
})
