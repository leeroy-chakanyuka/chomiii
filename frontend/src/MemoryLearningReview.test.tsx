import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryPanel } from './MemoryPanel'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const inferred = {
  id: 'suggestion-id', content: 'I may prefer weekend shopping',
  source: 'inferred', status: 'suggested', confidence: 0.76,
  sourceConversationId: null,
  createdAt: '2026-11-04T09:00:00Z', updatedAt: '2026-11-04T10:00:00Z',
}

it('shows speculative memories for review and only activates an approved one', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push(method + ' ' + url)
    if (method === 'GET') return new Response(JSON.stringify([inferred]))
    if (method === 'POST' && url.endsWith('/approve')) {
      return new Response(JSON.stringify({ ...inferred, status: 'active' }))
    }
    throw new Error('Unexpected memory request')
  }))
  render(<MemoryPanel onClose={() => undefined} />)
  await screen.findByText('I may prefer weekend shopping')
  expect(screen.getByText(/Suggested inference — not used in chat/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
  await waitFor(() => expect(screen.queryByText(/Suggested inference/)).not.toBeInTheDocument())
  expect(calls).toHaveLength(2)
  expect(calls[1]).toMatch(/POST.*\/memories\/suggestion-id\/approve$/)
})
