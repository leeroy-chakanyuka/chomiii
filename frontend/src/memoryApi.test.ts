import { afterEach, expect, it, vi } from 'vitest'
import { addMemory, listMemories, updateMemory } from './memoryApi'

afterEach(() => { vi.unstubAllGlobals() })

it('rejects malformed records instead of silently trusting server data', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('[{"content":"secret"}]')))
  await expect(listMemories()).rejects.toThrow('Invalid memory response')
})

it('sends only explicit user text and validates created memory', async () => {
  const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) =>
    new Response(JSON.stringify({
      id: 'memory-id', content: 'I like tea', source: 'user_explicit',
      createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T09:00:00Z',
    }), { status: init?.method === 'POST' ? 201 : 200 }),
  )
  vi.stubGlobal('fetch', fetchMock)
  const saved = await addMemory('I like tea')
  expect(saved.content).toBe('I like tea')
  expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/\/api\/memories$/), expect.objectContaining({
    method: 'POST', credentials: 'same-origin', body: JSON.stringify({ content: 'I like tea' }),
  }))
  await updateMemory('memory-id', 'I like tea')
  expect(fetchMock).toHaveBeenLastCalledWith(expect.stringMatching(/\/api\/memories\/memory-id$/),
    expect.objectContaining({ method: 'PUT' }))
})
