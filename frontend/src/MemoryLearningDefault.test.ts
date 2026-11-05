import { afterEach, expect, it, vi } from 'vitest'
import { sendMessage } from './chatApi'

afterEach(() => { vi.unstubAllGlobals() })

it('automatically learns on the backend without any browser opt-in header', async () => {
  const fetchMock = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
    new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'event:complete\ndata:{"messageId":"saved","status":"complete"}\n\n',
        ))
        controller.close()
      },
    })))
  vi.stubGlobal('fetch', fetchMock)
  const request = {
    conversationId: 'chat-1', requestId: 'req-1',
    content: 'My usual groceries are oats and milk', provider: 'gemini',
    model: 'gemini-3.5-flash-lite', apiKey: 'test-key',
    signal: new AbortController().signal, onEvent: () => undefined,
  }
  await sendMessage(request)
  const sent = fetchMock.mock.calls[0][1] as RequestInit
  expect(sent.headers).not.toHaveProperty('X-Chomiii-Learn-Memory')
  expect(sent.headers).not.toHaveProperty('X-Chomiii-Use-Memory')
})
