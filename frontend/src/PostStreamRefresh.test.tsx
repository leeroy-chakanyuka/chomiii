import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('requests saved messages and sidebar history concurrently after a completed stream', async () => {
  let created = false
  let requestedDetail = false
  let requestedList = false
  let resolveDetail: ((response: Response) => void) | undefined
  let resolveList: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response(JSON.stringify({ status: 'UP', service: 'chomiii' }))
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([{
      id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
      models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }],
    }]))
    if (url.endsWith('/conversations') && init?.method === 'POST') {
      created = true
      return new Response(JSON.stringify({
        id: 'chat-1', title: 'New chat', createdAt: 'now', updatedAt: 'now', messages: [],
      }), { status: 201 })
    }
    if (url.endsWith('/conversations') && created) {
      requestedList = true
      return new Promise<Response>(resolve => { resolveList = resolve })
    }
    if (url.endsWith('/conversations')) return new Response('[]')
    if (url.endsWith('/conversations/chat-1/messages/stream')) {
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode(
            'event:delta\ndata:{"text":"streaming"}\n\n'
            + 'event:complete\ndata:{"messageId":"assistant-1","status":"complete"}\n\n',
          ))
          controller.close()
        },
      }))
    }
    if (url.endsWith('/conversations/chat-1')) {
      requestedDetail = true
      return new Promise<Response>(resolve => { resolveDetail = resolve })
    }
    throw new Error("Unexpected endpoint: " + url)
  }))

  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Hello' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))

  try {
    // Either request being delayed must not stop the other from starting.
    await waitFor(() => expect(requestedDetail && requestedList).toBe(true))
  } finally {
    resolveDetail?.(new Response(JSON.stringify({
      id: 'chat-1', title: 'Hello', createdAt: 'now', updatedAt: 'now',
      messages: [
        { id: 'user-1', role: 'user', content: 'Hello', status: 'complete', createdAt: 'now' },
        { id: 'assistant-1', role: 'assistant', content: 'finished', status: 'complete', createdAt: 'now' },
      ],
    })))
    resolveList?.(new Response(JSON.stringify([
      { id: 'chat-1', title: 'Hello', createdAt: 'now', updatedAt: 'now' },
    ])))
  }

  await screen.findByText('finished')
  expect(screen.getByRole('button', { name: 'Hello' })).toBeVisible()
})
