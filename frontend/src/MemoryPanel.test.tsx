import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryPanel } from './MemoryPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const updated = (content: string) => ({
  id: 'memory-1', content, source: 'user_explicit',
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
  await screen.findByText('Nothing saved yet. Chomi will not invent your preferences.')

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
  await screen.findByText('Nothing saved yet. Chomi will not invent your preferences.')
  expect(requests.map(entry => entry.method)).toEqual(['GET', 'POST', 'PUT', 'DELETE'])
})

it('shows a provider error without pretending a memory was saved', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.method) return jsonResponse([])
    return jsonResponse({ status: 409, title: 'Memory is full. Delete an old memory.' }, 409)
  }))

  render(<MemoryPanel onClose={() => undefined} />)
  await screen.findByText('Nothing saved yet. Chomi will not invent your preferences.')
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
