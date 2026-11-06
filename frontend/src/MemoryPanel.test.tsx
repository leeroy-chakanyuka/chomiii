import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryPanel } from './MemoryPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const updated = (content: string) => ({
  id: 'memory-1', content, source: 'user_explicit', status: 'active',
  confidence: 1, sourceConversationId: null,
  createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T10:00:00Z',
})

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

it('explicitly saves, corrects and deletes a personal memory', async () => {
  let existing: ReturnType<typeof updated>[] = []
  const requests: { method: string; body: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    requests.push({ method, body: String(init?.body ?? '') })
    if (url.endsWith('/memories') && method === 'GET') return jsonResponse(existing)
    if (url.endsWith('/memories') && method === 'POST') {
      const payload = JSON.parse(String(init?.body)) as { content: string }
      existing = [updated(payload.content)]
      return jsonResponse(existing[0], 201)
    }
    if (url.endsWith('/memories/memory-1') && method === 'PUT') {
      const payload = JSON.parse(String(init?.body)) as { content: string }
      existing = [updated(payload.content)]
      return jsonResponse(existing[0])
    }
    if (url.endsWith('/memories/memory-1') && method === 'DELETE') {
      existing = []
      return new Response(null, { status: 204 })
    }
    throw new Error('Unexpected endpoint: ' + url)
  }))

  render(<MemoryPanel onClose={() => undefined} />)
  await screen.findByText('Nothing saved yet.')

  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), {
    target: { value: 'My usual groceries are milk and oats' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save memory' }))
  await screen.findByText('My usual groceries are milk and oats')
  expect(requests.some(entry => entry.method === 'POST'
    && entry.body.includes('My usual groceries are milk and oats'))).toBe(true)

  fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
  fireEvent.change(screen.getByLabelText('Edit saved memory'), {
    target: { value: 'My usual groceries are fruit' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
  await screen.findByText('My usual groceries are fruit')

  fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
  await screen.findByText('Nothing saved yet.')
  expect(requests.map(entry => entry.method)).toEqual(['GET', 'POST', 'PUT', 'DELETE'])
})

it('shows a provider error without pretending a memory was saved', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.method) return jsonResponse([])
    return jsonResponse({ status: 409, title: 'Memory is full. Delete an old memory.' }, 409)
  }))

  render(<MemoryPanel onClose={() => undefined} />)
  await screen.findByText('Nothing saved yet.')
  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), {
    target: { value: 'I like tea' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save memory' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Memory is full'))
  expect(screen.queryByText('I like tea', { selector: 'p' })).not.toBeInTheDocument()
})

it('requires explicit confirmation before clearing all saved memories', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'DELETE') return new Response(null, { status: 204 })
    return jsonResponse([updated('I prefer Saturdays')])
  })
  vi.stubGlobal('fetch', fetchMock)
  try {
    render(<MemoryPanel onClose={() => undefined} />)
    await screen.findByText('I prefer Saturdays')
    fireEvent.click(screen.getByRole('button', { name: 'Delete all' }))
    expect(confirm).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.getByText('I prefer Saturdays')).toBeInTheDocument()
  } finally {
    confirm.mockRestore()
  }
})

it('keeps Save unavailable until the initial list has loaded, so a stale list cannot overwrite a new save', async () => {
  const requests: string[] = []
  let releaseList: (value: Response) => void = () => undefined
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    requests.push(method)
    if (method === 'GET') {
      return new Promise<Response>(resolve => { releaseList = resolve })
    }
    const payload = JSON.parse(String(init?.body)) as { content: string }
    return jsonResponse({ ...updated(payload.content), id: 'memory-2' }, 201)
  }))

  render(<MemoryPanel onClose={() => undefined} />)
  expect(await screen.findByText('Loading memories…')).toBeVisible()
  const save = screen.getByRole('button', { name: 'Save memory' })
  expect(save).toBeDisabled()
  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), { target: { value: 'I like tea' } })
  fireEvent.click(save) // must be a no-op while the list is still loading
  expect(requests).toEqual(['GET'])

  releaseList(jsonResponse([updated('I prefer Saturdays')]))
  await screen.findByText('I prefer Saturdays')
  await waitFor(() => expect(save).toBeEnabled())
  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), { target: { value: '' } })
  fireEvent.click(save) // empty content; still must not POST
  expect(requests).toEqual(['GET'])

  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), { target: { value: 'I like tea once more' } })
  fireEvent.click(save)
  await screen.findByText('I like tea once more')
  expect(requests).toEqual(['GET', 'POST'])
})

it('ignores an older load that finishes after a new memory was saved', async () => {
  const pending: ((response: Response) => void)[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.method) return new Promise<Response>(resolve => { pending.push(resolve) })
    return jsonResponse(updated('I enjoy tea'), 201)
  }))

  render(<StrictMode><MemoryPanel onClose={() => undefined} /></StrictMode>)
  await waitFor(() => expect(pending).toHaveLength(2))
  await act(async () => { pending[1](jsonResponse([])) })
  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), { target: { value: 'I enjoy tea' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save memory' }))
  await screen.findByText('I enjoy tea')
  await act(async () => { pending[0](jsonResponse([])) })
  expect(screen.getByText('I enjoy tea')).toBeVisible()
})

it('reports a failed initial load instead of presenting it as an empty memory list', async () => {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    requests.push(init?.method ?? 'GET')
    return new Response(null, { status: 500 })
  }))
  render(<MemoryPanel onClose={() => undefined} />)
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Could not load'))
  expect(screen.queryByText('Nothing saved yet.')).not.toBeInTheDocument()
  expect(screen.queryByText(/memories \(including suggestions\)/)).not.toBeInTheDocument()
  const save = screen.getByRole('button', { name: 'Save memory' })
  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), {
    target: { value: 'I like tea' },
  })
  expect(save).toBeDisabled()
  fireEvent.click(save) // must not add while the real list is still unknown
  expect(requests).toEqual(['GET'])
})

it('retries a failed load and then shows and extends the real saved memories', async () => {
  let failing = true
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    requests.push(init?.method ?? 'GET')
    if (failing) return new Response(null, { status: 500 })
    if (init?.method === 'POST') {
      const payload = JSON.parse(String(init.body)) as { content: string }
      return jsonResponse({ ...updated(payload.content), id: 'memory-2' }, 201)
    }
    return jsonResponse([updated('I prefer Saturdays')])
  }))

  render(<MemoryPanel onClose={() => undefined} />)
  const retry = await screen.findByRole('button', { name: 'Retry' })
  expect(screen.queryByText('I prefer Saturdays')).not.toBeInTheDocument()

  failing = false
  fireEvent.click(retry)
  await screen.findByText('I prefer Saturdays')
  expect(screen.queryByText(/Could not load/)).not.toBeInTheDocument()
  expect(screen.getByText('1 memory (including suggestions)')).toBeVisible()

  fireEvent.change(screen.getByLabelText('What should Chomi remember?'), {
    target: { value: 'I like tea' },
  })
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save memory' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Save memory' }))
  await screen.findByText('I like tea')
  expect(screen.getByText('I prefer Saturdays')).toBeVisible()
  expect(requests).toEqual(['GET', 'GET', 'POST'])
})

it('states deletion limits and does not promise Chomi is never wrong about preference facts', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if ((init?.method ?? 'GET') === 'GET') return jsonResponse([updated('I prefer Saturdays')])
    throw new Error('Unexpected memory request')
  }))
  render(<MemoryPanel onClose={() => undefined} />)
  await screen.findByText('I prefer Saturdays')
  expect(screen.getByText(/Deleting a memory here does not erase copies already sent/)).toBeVisible()
  expect(screen.queryByText(/will not invent/i)).not.toBeInTheDocument()
})

it('keeps memory recall control in Memories settings instead of the chat composer', async () => {
  const onMemoryChange = vi.fn()
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([])))
  render(<MemoryPanel onClose={() => undefined} useMemory={true} onMemoryChange={onMemoryChange} />)
  const setting = await screen.findByLabelText('Use saved memories in chats')
  fireEvent.click(setting)
  expect(onMemoryChange).toHaveBeenCalledWith(false)
})

