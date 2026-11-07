import { expect, test, type Page } from '@playwright/test'

type StoredMessage = { id: string; role: string; content: string; status: string; createdAt: string }
type Chat = { id: string; title: string; createdAt: string; updatedAt: string; messages: StoredMessage[] }

async function fixture(page: Page) {
  const chats: Chat[] = []
  let sends = 0
  await page.route('**/api/health', (route) => route.fulfill({ json: { status: 'UP', service: 'chomiii' } }))
  await page.route('**/api/chat/providers', (route) => route.fulfill({ json: [
    { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
      models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
  ] }))
  await page.route('**/api/conversations**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/conversations' && method === 'GET') {
      await route.fulfill({ json: chats.map(({ id, title, createdAt, updatedAt }) => ({ id, title, createdAt, updatedAt })),
        headers: { 'Set-Cookie': 'chomiii_session=fixture; HttpOnly; SameSite=Lax; Path=/' } })
      return
    }
    if (path === '/api/conversations' && method === 'POST') {
      const chat = { id: `conversation-${chats.length + 1}`, title: 'New chat',
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), messages: [] }
      chats.unshift(chat)
      await route.fulfill({ status: 201, json: chat })
      return
    }
    const id = path.split('/')[3]
    const chat = chats.find((item) => item.id === id)
    if (!chat) { await route.fulfill({ status: 404 }); return }
    if (path.endsWith('/messages/stream') && method === 'POST') {
      expect(request.headers()['x-gemini-api-key']).toBe('browser-test-key')
      expect(request.url()).not.toContain('browser-test-key')
      const body: unknown = request.postDataJSON()
      if (typeof body !== 'object' || !body || !('content' in body) || typeof body.content !== 'string') {
        throw new Error('Invalid browser test request')
      }
      sends += 1
      chat.title = chat.messages.length === 0 ? body.content : chat.title
      chat.messages.push({ id: `user-${sends}`, role: 'user', content: body.content,
        status: 'complete', createdAt: new Date().toISOString() })
      const plan = 'plan' in body && body.plan === true
      const reply = plan ? '**Proposed plan — nothing has been done yet**\n\n1. Prepare a checklist — service not connected'
        : sends === 1 ? 'Java is a great language to explore.' : 'You mentioned Java.'
      chat.messages.push({ id: `assistant-${sends}`, role: 'assistant', content: reply,
        status: 'complete', createdAt: new Date().toISOString() })
      await route.fulfill({ contentType: 'text/event-stream', body: [
        'event:start\ndata:{"requestId":"r","messageId":"m"}\n\n',
        ...(plan ? ['event:plan\ndata:{"id":"test-plan","executed":false,"steps":[]}\n\n'] : []),
        `event:delta\ndata:${JSON.stringify({ text: reply.slice(0, 12) })}\n\n`,
        `event:delta\ndata:${JSON.stringify({ text: reply.slice(12) })}\n\n`,
        'event:complete\ndata:{"messageId":"m","status":"complete"}\n\n',
      ].join('') })
      return
    }
    if (path === `/api/conversations/${id}` && method === 'GET') { await route.fulfill({ json: chat }); return }
    if (path === `/api/conversations/${id}` && method === 'DELETE') {
      chats.splice(chats.indexOf(chat), 1)
      await route.fulfill({ status: 204 })
      return
    }
    await route.fulfill({ status: 404 })
  })
  return { chats, sends: () => sends }
}

test('configures a key, chats twice, refreshes, and reopens history', async ({ page }) => {
  const backend = await fixture(page)
  await page.goto('/')
  await page.getByLabel('Gemini API key').fill('browser-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await expect(page.getByText('What’s on your mind?')).toBeVisible()
  await page.getByLabel('Message').fill('My favourite language is Java')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText('Java is a great language to explore.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible()
  await page.getByLabel('Message').fill('Which language did I mention?')
  await page.getByLabel('Message').press('Enter')
  await expect(page.getByText('You mentioned Java.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible()
  expect(backend.sends()).toBe(2)
  await page.screenshot({ path: '../docs/screenshots/chat-desktop.png', fullPage: true })
  await page.reload()
  await expect(page.getByLabel('Gemini API key')).toBeEmpty()
  await page.getByLabel('Gemini API key').fill('browser-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.getByRole('button', { name: 'My favourite language is Java', exact: true }).click()
  await expect(page.getByText('You mentioned Java.')).toBeVisible()
  await page.getByRole('button', { name: 'Remove key' }).click()
  await expect(page.getByLabel('Gemini API key')).toBeEmpty()
})

test('requests and reopens a plan in chat on a narrow screen', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByLabel('Gemini API key').fill('browser-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.getByLabel('Message').fill('My appointment needs some preparation')
  await page.getByRole('button', { name: 'Make a plan' }).click()
  await expect(page.getByText('Proposed plan — nothing has been done yet')).toBeVisible()
  await expect(page.getByText('Prepare a checklist — service not connected')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible()
  expect((await page.locator('html').boundingBox())?.width).toBeLessThanOrEqual(390)
  await page.reload()
  await page.getByLabel('Gemini API key').fill('browser-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await page.getByRole('button', { name: 'My appointment needs some preparation', exact: true }).click()
  await expect(page.getByText('Proposed plan — nothing has been done yet')).toBeVisible()
})

test('keeps conversations and message composition usable on a narrow viewport', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByLabel('Gemini API key').fill('browser-test-key')
  await page.getByRole('button', { name: 'Start chatting' }).click()
  await expect(page.getByRole('button', { name: /New chat/ })).toBeVisible()
  await expect(page.getByLabel('Message')).toBeVisible()
  await page.getByLabel('Message').fill('A question')
  await page.getByLabel('Message').press('Shift+Enter')
  await expect(page.getByLabel('Message')).toHaveValue('A question\n')
  await page.getByLabel('Message').fill('A question')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText('Java is a great language to explore.')).toBeVisible()
  const pageBounds = await page.locator('html').boundingBox()
  expect(pageBounds?.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: '../docs/screenshots/chat-mobile.png', fullPage: true })
})
