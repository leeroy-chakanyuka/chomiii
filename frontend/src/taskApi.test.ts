import { afterEach, expect, it, vi } from 'vitest'
import { approveTaskProposal, proposeTask } from './taskApi'
afterEach(() => vi.unstubAllGlobals())

it('sends a draft first and a distinct explicit approval request', async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
    String(input).endsWith('/approve')
      ? { id: 'task-1', title: 'Call home', status: 'open', createdAt: '2026-11-05T08:00:00Z' }
      : { id: 'proposal-1', title: 'Call home', status: 'pending', createdAt: '2026-11-05T08:00:00Z' },
  )))
  vi.stubGlobal('fetch', fetchMock)
  const proposal = await proposeTask('Call home')
  expect(proposal.status).toBe('pending')
  const task = await approveTaskProposal(proposal.id)
  expect(task.status).toBe('open')
  expect(fetchMock.mock.calls[0][0]).toMatch(/\/actions\/tasks\/proposals$/)
  expect(fetchMock.mock.calls[1][0]).toMatch(/\/actions\/tasks\/proposals\/proposal-1\/approve$/)
})
