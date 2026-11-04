import { defineConfig } from '@playwright/test'

/**
 * Standalone browser checks: no chomiii backend required. Tests 1–4 verify the
 * shell, a real connection-refusal through the proxy, and browser-intercepted
 * non-OK and malformed-JSON replies. Real backend integration lives in
 * e2e/integration.spec.ts (playwright.integration.config.ts).
 *
 * Backend origin the Vite development proxy forwards `/api` to.
 * Defaults to the Spring Boot port. Override with BACKEND_URL, e.g.
 * `BACKEND_URL=http://localhost:9000 npm run test:browser`.
 */
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/integration.spec.ts', '**/live-chat.spec.ts', '**/benchmark-live.spec.ts'],
  timeout: 30_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      // Dev server behind the configured backend origin; same default as `npm run dev`.
      command: 'npm run dev -- --host --port 5173 --strictPort',
      url: 'http://127.0.0.1:5173',
      env: { BACKEND_URL: backendUrl },
      reuseExistingServer: !process.env.CI,
    },
    {
      // Dev server behind a deliberately unreachable backend port so the
      // browser test exercises a real connection-refused through the proxy.
      command: 'npm run dev -- --host --port 5174 --strictPort',
      url: 'http://127.0.0.1:5174',
      env: { BACKEND_URL: 'http://127.0.0.1:51999' },
      reuseExistingServer: false,
    },
  ],
})
