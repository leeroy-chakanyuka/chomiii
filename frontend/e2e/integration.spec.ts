import { expect, test, type Page } from '@playwright/test'

/**
 * Real frontend-to-backend integration: requires the Java backend
 * (and its PostgreSQL) actually running at the configured backend origin.
 * There is no skip gate: without a healthy backend this suite fails loudly,
 * which is the point. Run with `npm run test:browser:integration`.
 */
const STATUS_SELECTOR = '[data-testid="status"]'

function statusOn(page: Page) {
  return page.locator(STATUS_SELECTOR)
}

test('shows up against a real running healthy chomiii backend', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading')).toHaveText('chomiii')
  await expect(statusOn(page)).toHaveAttribute('data-state', 'up')
  expect(await statusOn(page).textContent()).toBe('up')
})
