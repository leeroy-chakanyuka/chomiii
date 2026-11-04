import { afterEach, expect, it, vi } from 'vitest'
import { sendMessage } from './chatApi'

afterEach(() => { vi.unstubAllGlobals() })

it('requires explicit learn-memory opt-in before sending an extraction permission header', async () => {
  const fetchMock = vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(
        'event:complete\ndata:{"messageId":"saved","status":"complete"}\n\n',
      ))
      controller.close()
    },
  })))
  vi.stubGlobal('fetch', fetchMock)
  const base = {
    conversationId: 'chat-1', requestId: 'req-1',
    content: 'My usual groceries are oats and milk', provider: 'gemini',
    model: 'gemini-3.5-flash-lite', apiKey: 'test-key',
    signal: new AbortController().signal, onEvent: () => undefined,
  }
  await sendMessage(base)
  const first = fetchMock.mock.calls[0][1] as RequestInit
  expect(first.headers).not.toHaveProperty('X-Chomiii-Learn-Memory')

  await sendMessage({ ...base, learnMemory: true })
  const second = fetchMock.mock.calls[1][1] as RequestInit
  expect(second.headers).toHaveProperty('X-Chomiii-Learn-Memory', 'true')

  await sendMessage({ ...base, learnMemory: false })
  const third = fetchMock.mock.calls[2][1] as RequestInit
  expect(third.headers).not.toHaveProperty('X-Chomiii-Learn-Memory')
})
