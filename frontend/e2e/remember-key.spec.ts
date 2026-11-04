import { expect, test } from '@playwright/test'

test('remembers an opted-in Gemini key on this device and forgets it on removal', async ({ page }) => {
  await page.route('**/api/health', route => route.fulfill({ json: { status: 'UP', service: 'chomiii' } }))
  await page.route('**/api/chat/providers', route => route.fulfill({ json: [{
    id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
    models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }],
  }] }))
  await page.route('**/api/conversations', route => route.fulfill({ json: [] }))

  await page.goto('/')
  await page.getByLabel('Gemini API key').fill('remembered-test-key')
  await page.getByRole('checkbox', { name: /Remember this key on this device/ }).check()
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.reload()
  await expect(page.getByRole('button', { name: 'Remove key' })).toBeVisible()
  await expect(page.getByLabel('Gemini API key')).toHaveCount(0)

  const state = await page.context().storageState()
  expect(JSON.stringify(state)).not.toContain('remembered-test-key')
  const browserStores = await page.evaluate('JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie })')
  expect(browserStores).not.toContain('remembered-test-key')
  const saved = await page.evaluate(`new Promise((resolve, reject) => {
    const opened = indexedDB.open('chomiii-device-key-v1')
    opened.onerror = () => reject(new Error('Could not inspect local key storage'))
    opened.onsuccess = () => {
      const request = opened.result.transaction('credentials', 'readonly').objectStore('credentials').get('gemini')
      request.onerror = () => reject(new Error('Could not read saved credential'))
      request.onsuccess = () => resolve({ encrypted: request.result.cipher instanceof ArrayBuffer,
        nonExtractable: request.result.key.extractable === false,
        rawKeyIsAbsent: !Object.values(request.result).includes('remembered-test-key') })
    }
  })`)
  expect(saved).toEqual({ encrypted: true, nonExtractable: true, rawKeyIsAbsent: true })

  await page.getByRole('button', { name: 'Remove key' }).click()
  await page.reload()
  await expect(page.getByLabel('Gemini API key')).toBeEmpty()
  const remains = await page.evaluate(`new Promise((resolve, reject) => {
    const opened = indexedDB.open('chomiii-device-key-v1')
    opened.onerror = () => reject(new Error('Could not inspect local key storage'))
    opened.onsuccess = () => {
      const request = opened.result.transaction('credentials', 'readonly').objectStore('credentials').get('gemini')
      request.onerror = () => reject(new Error('Could not inspect removed key'))
      request.onsuccess = () => resolve(request.result !== undefined)
    }
  })`)
  expect(remains).toBe(false)
})

test('allows an in-memory key when browser credential storage is unavailable', async ({ page }) => {
  await page.addInitScript(`Object.defineProperty(indexedDB, 'open', { value: () => { throw new DOMException('Blocked', 'SecurityError') } })`)
  await page.route('**/api/health', route => route.fulfill({ json: { status: 'UP', service: 'chomiii' } }))
  await page.route('**/api/chat/providers', route => route.fulfill({ json: [{
    id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
    models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }],
  }] }))
  await page.route('**/api/conversations', route => route.fulfill({ json: [] }))
  await page.goto('/')
  await page.getByLabel('Gemini API key').fill('temporary-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await expect(page.getByRole('button', { name: 'Remove key' })).toBeVisible()
})
