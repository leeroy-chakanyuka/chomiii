import { expect, test, type Page } from '@playwright/test'

const STATUS_SELECTOR = '[data-testid="status"]'

function statusOn(page: Page) {
  return page.locator(STATUS_SELECTOR)
}

test('renders the lowercase shell and resolves the health check in a real browser', async ({
  page,
}) => {
  await page.goto('/')

  await expect(page.getByRole('heading')).toHaveText('chomiii')
  // Accepts either terminal state: the point is a real round trip to (or real
  // miss against) the configured backend origin, never a hang or crash.
  await expect(statusOn(page)).toHaveAttribute('data-state', /^(up|unavailable)$/)
})

test('shows unavailable when the backend does not answer the proxy', async ({ page }) => {
  // Server two proxies /api to a port with nothing listening: a real
  // connection refusal, not a stubbed fetch.
  await page.goto('http://127.0.0.1:5174/')

  await expect(page.getByRole('heading')).toHaveText('chomiii')
  await expect(statusOn(page)).toHaveAttribute('data-state', 'unavailable')
  expect(await statusOn(page).textContent()).toBe('unavailable')
})

test('shows unavailable without crashing when the backend answers non-OK', async ({ page }) => {
  // Deterministic non-OK reply, intercepted in the browser before the proxy.
  await page.route('**/api/health', (route) =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"status":"DOWN"}' }),
  )

  await page.goto('/')

  await expect(statusOn(page)).toHaveAttribute('data-state', 'unavailable')
  expect(await statusOn(page).textContent()).toBe('unavailable')
  await expect(page.getByRole('heading')).toHaveText('chomiii')
})

test('shows unavailable without crashing when the backend answers malformed JSON', async ({
  page,
}) => {
  await page.route('**/api/health', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: 'not-json-at-all' }),
  )

  await page.goto('/')

  await expect(statusOn(page)).toHaveAttribute('data-state', 'unavailable')
  await expect(page.getByRole('heading')).toHaveText('chomiii')
})
