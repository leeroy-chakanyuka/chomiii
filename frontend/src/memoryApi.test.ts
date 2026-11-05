import { afterEach, expect, it, vi } from 'vitest'
import { addMemory, listMemories, updateMemory } from './memoryApi'

afterEach(() => { vi.unstubAllGlobals() })

it('rejects malformed records instead of silently trusting server data', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('[{"content":"secret"}]')))
  await expect(listMemories()).rejects.toThrow('Invalid memory response')
})

it('rejects records whose status is missing or unrecognized', async () => {
  const invalidStatuses = [undefined, 'approved', '']
  for (const status of invalidStatuses) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'm1', content: 'I like tea', source: 'user_explicit', ...(status !== undefined ? { status } : {}),
      confidence: 0.9, createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T09:00:00Z',
    }]))))
    await expect(listMemories()).rejects.toThrow('Invalid memory response')
  }
})

it('rejects records whose confidence is missing, non-numeric, or not a normal number between 0 and 1', async () => {
  const invalidConfidences: unknown[] = [undefined, 'high', 1.5, -0.1, null, Number.NaN]
  const base = {
    id: 'm1', content: 'I like tea', source: 'user_explicit', status: 'active',
    createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T09:00:00Z',
  }
  for (const confidence of invalidConfidences) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      ...base, ...(confidence !== undefined ? { confidence } : {}),
    }]))))
    await expect(listMemories()).rejects.toThrow('Invalid memory response')
  }
})

it('sends only explicit user text and validates created memory', async () => {
  const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) =>
    new Response(JSON.stringify({
      id: 'memory-id', content: 'I like tea', source: 'user_explicit', status: 'active',
      confidence: 1, sourceConversationId: null,
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

it('rejects a create response without a trustworthy status or confidence', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
    id: 'm1', content: 'I like tea', source: 'user_explicit',
    createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T09:00:00Z',
  }), { status: 201 })))
  await expect(addMemory('I like tea')).rejects.toThrow('Invalid memory response')
})
