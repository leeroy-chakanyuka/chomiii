import { expect, test } from '@playwright/test'

test('a user-supplied key completes a persistent two-turn Gemini conversation', async ({ page }) => {
  test.skip(process.env.CHOMIII_RUN_LIVE_CHAT !== 'true' || !process.env.GEMINI_API_KEY,
    'This separate smoke test needs an authorized Gemini key and a running backend.')
  test.setTimeout(180_000)
  const key = process.env.GEMINI_API_KEY
  if (!key) throw new Error('The authorized test key is unavailable.')

  await page.goto('/')
  await page.getByLabel('Gemini API key').fill(key)
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.getByLabel('Message').fill('My favourite programming language is Java. Please acknowledge that in one sentence.')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.locator('.message.assistant').last()).toContainText(/Java/i, { timeout: 140_000 })
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible({ timeout: 30_000 })

  await page.getByLabel('Message').fill('Which programming language did I mention? Reply with its name.')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.locator('.message.assistant').last()).toContainText(/Java/i, { timeout: 140_000 })
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: '../docs/screenshots/chat-live.png', fullPage: true })

  await page.reload()
  await expect(page.getByLabel('Gemini API key')).toBeEmpty()
  await page.getByLabel('Gemini API key').fill(key)
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.locator('.conversation-link').filter({ hasText: 'My favourite programming language is Java' }).click()
  await expect(page.locator('.message.assistant')).toHaveCount(2)
  await expect(page.locator('.message.assistant').last()).toContainText(/Java/i)
})
