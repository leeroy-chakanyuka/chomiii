import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('shows Gemini setup and asks for a key before starting chat', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response(JSON.stringify({ status: 'UP', service: 'chomiii' }))
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (url.endsWith('/conversations')) return new Response('[]')
    throw new Error('Unexpected request')
  }))

  render(<App />)
  await screen.findByLabelText('Gemini API key')
  expect(screen.getByRole('link', { name: /Google AI Studio/ })).toHaveAttribute('href', 'https://aistudio.google.com/apikey')
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  expect(screen.getByRole('alert')).toHaveTextContent('Enter a Gemini API key')
})

it('shows a message immediately, updates from real stream frames, and loads the saved response', async () => {
  const messages: unknown[] = []
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response(JSON.stringify({ status: 'UP', service: 'chomiii' }))
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (url.endsWith('/conversations') && init?.method === 'POST') return new Response(JSON.stringify({
      id: 'chat-1', title: 'New chat', createdAt: 'now', updatedAt: 'now', messages: [],
    }), { status: 201 })
    if (url.endsWith('/conversations')) return new Response(JSON.stringify([
      { id: 'chat-1', title: 'My language is Java', createdAt: 'now', updatedAt: 'now' },
    ]))
    if (url.endsWith('/conversations/chat-1')) return new Response(JSON.stringify({
      id: 'chat-1', title: 'My language is Java', createdAt: 'now', updatedAt: 'now', messages,
    }))
    if (url.endsWith('/messages/stream')) {
      expect(init?.headers).toEqual({ 'Content-Type': 'application/json', 'X-Gemini-Api-Key': 'private-test-key' })
      const body = JSON.parse(String(init?.body))
      expect(body).toMatchObject({ provider: 'gemini', model: 'gemini-3.5-flash-lite', content: 'My language is Java' })
      expect(url).not.toContain('private-test-key')
      messages.push({ id: 'user-1', role: 'user', content: body.content, status: 'complete', createdAt: 'now' })
      return new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller } }),
        { headers: { 'content-type': 'text/event-stream' } })
    }
    throw new Error(`Unexpected path: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'private-test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'My language is Java' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  expect(within(screen.getByRole('region', { name: 'Chat' })).getByText('My language is Java')).toBeInTheDocument()
  await waitFor(() => expect(stream).toBeDefined())
  stream?.enqueue(new TextEncoder().encode('event:delta\ndata:{"text":"Hello"}\n\n'))
  await screen.findByText('Hello')
  expect(screen.queryByText('Hello world')).not.toBeInTheDocument()
  messages.push({ id: 'assistant-1', role: 'assistant', content: 'Hello world', status: 'complete', createdAt: 'now' })
  stream?.enqueue(new TextEncoder().encode('event:delta\ndata:{"text":" world"}\n\n'
    + 'event:complete\ndata:{"messageId":"assistant-1","status":"complete"}\n\n'))
  stream?.close()
  await screen.findByText('Hello world')
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Stop generation' })).not.toBeInTheDocument())
})

it('switches conversations, starts a new chat, stops a stream, and removes the key', async () => {
  const summaries = [
    { id: 'old-1', title: 'First saved chat', createdAt: 'now', updatedAt: 'now' },
    { id: 'old-2', title: 'Second saved chat', createdAt: 'now', updatedAt: 'now' },
  ]
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined
  let stopped = false
  let created = false
  let savedStatus: 'pending' | 'interrupted' = 'pending'
  const fake = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/health')) return new Response(JSON.stringify({ status: 'UP', service: 'chomiii' }))
    if (path.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (path.endsWith('/conversations') && init?.method === 'POST') {
      created = true
      return new Response(JSON.stringify({ id: 'new-chat', title: 'New chat', createdAt: 'now', updatedAt: 'now', messages: [] }),
        { status: 201 })
    }
    if (path.endsWith('/conversations')) return new Response(JSON.stringify(summaries))
    if (path.endsWith('/conversations/old-1')) return new Response(JSON.stringify({ ...summaries[0],
      messages: [{ id: 'm1', role: 'user', content: 'From first chat', status: 'complete', createdAt: 'now' }] }))
    if (path.endsWith('/conversations/old-2')) return new Response(JSON.stringify({ ...summaries[1],
      messages: [{ id: 'm2', role: 'user', content: 'From second chat', status: 'complete', createdAt: 'now' }] }))
    if (path.endsWith('/conversations/new-chat')) return new Response(JSON.stringify({
      id: 'new-chat', title: 'New chat', createdAt: 'now', updatedAt: 'now',
      messages: [{ id: 'm3', role: 'user', content: 'Help me', status: 'complete', createdAt: 'now' },
        { id: 'm4', role: 'assistant', content: savedStatus === 'pending' ? '' : 'partial',
          status: savedStatus, createdAt: 'now' }],
    }))
    if (path.endsWith('/messages/stream')) return new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        stream = controller
        init?.signal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')),
          { once: true })
      },
    }), { headers: { 'content-type': 'text/event-stream' } })
    if (path.endsWith('/cancel')) {
      stopped = true
      setTimeout(() => {
        savedStatus = 'interrupted'
        stream?.enqueue(new TextEncoder().encode('event:error\ndata:{"code":"INTERRUPTED","message":"Generation stopped.","status":"interrupted"}\n\n'))
        stream?.close()
      }, 40)
      return new Response('{}', { status: 202 })
    }
    throw new Error(`Unexpected request: ${path}`)
  })
  vi.stubGlobal('fetch', fake)
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'private-test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'First saved chat' }))
  await screen.findByText('From first chat')
  fireEvent.click(screen.getByRole('button', { name: 'Second saved chat' }))
  await screen.findByText('From second chat')
  expect(screen.queryByText('From first chat')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /New chat/ }))
  expect(screen.getByText('What’s on your mind?')).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Help me' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  await waitFor(() => expect(stream).toBeDefined())
  stream?.enqueue(new TextEncoder().encode('event:delta\ndata:{"text":"partial"}\n\n'))
  await screen.findByText('partial')
  fireEvent.click(screen.getByRole('button', { name: 'Stop generation' }))
  await waitFor(() => expect(stopped).toBe(true))
  expect(created).toBe(true)
  await screen.findByText('Response stopped')
  expect(screen.getByRole('alert')).toHaveTextContent('Generation stopped.')
  fireEvent.click(screen.getByRole('button', { name: 'Remove key' }))
  expect(screen.getByLabelText('Gemini API key')).toHaveValue('')
})
