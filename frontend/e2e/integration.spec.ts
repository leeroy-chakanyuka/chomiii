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

test('a browser can save, correct and delete its memories', async ({ page, browser }) => {
  await page.goto('/')
  await expect(statusOn(page)).toHaveAttribute('data-state', 'up')
  await page.getByRole('button', { name: 'Memories' }).click()
  await expect(page.getByText('Nothing saved yet.')).toBeVisible()
  await page.getByLabel('What should Chomi remember?').fill('My usual groceries are oats and milk.')
  await page.getByRole('button', { name: 'Save memory' }).click()
  await expect(page.getByText('My usual groceries are oats and milk.')).toBeVisible()

  const other = await browser.newContext()
  try {
    const otherPage = await other.newPage()
    await otherPage.goto('/')
    await otherPage.getByRole('button', { name: 'Memories' }).click()
    await expect(otherPage.getByText('Nothing saved yet.')).toBeVisible()
    await expect(otherPage.getByText('My usual groceries are oats and milk.')).toHaveCount(0)
  } finally {
    await other.close()
  }

  await page.getByRole('button', { name: 'Edit' }).click()
  await page.getByLabel('Edit saved memory').fill('My usual groceries are fruit.')
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByText('My usual groceries are fruit.')).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Memories' }).click()
  await expect(page.getByText('My usual groceries are fruit.')).toBeVisible()
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await expect(page.getByText('Nothing saved yet.')).toBeVisible()
})
