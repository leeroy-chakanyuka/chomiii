import { afterEach, expect, it, vi } from 'vitest'
import { sendMessage } from './chatApi'

afterEach(() => { vi.unstubAllGlobals() })

it('sends memory opt-out header only when a person disables it', async () => {
  const fetchMock = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
    new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'event:complete\ndata:{"messageId":"saved","status":"complete"}\n\n',
        ))
        controller.close()
      },
    })),
  )
  vi.stubGlobal('fetch', fetchMock)
  const request = {
    conversationId: 'chat-1', requestId: 'req-1',
    content: 'What are my groceries?', provider: 'gemini',
    model: 'gemini-3.5-flash-lite', apiKey: 'test-key',
    signal: new AbortController().signal, onEvent: () => undefined,
  }
  await sendMessage({ ...request, useMemory: false })
  expect(fetchMock).toHaveBeenLastCalledWith(expect.stringMatching(/\/messages\/stream$/),
    expect.objectContaining({
      headers: expect.objectContaining({ 'X-Chomiii-Use-Memory': 'false' }),
    }))

  await sendMessage({ ...request, useMemory: true })
  const second = fetchMock.mock.calls[1][1] as RequestInit
  expect(second.headers).not.toHaveProperty('X-Chomiii-Use-Memory')
})
