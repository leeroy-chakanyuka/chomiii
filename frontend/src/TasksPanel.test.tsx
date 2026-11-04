import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TasksPanel } from './TasksPanel'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('does not create a task until the person approves a proposal', async () => {
  const stored: { id: string; title: string; status: string; createdAt: string }[] = []
  const pending: { id: string; title: string; status: string; createdAt: string }[] = []
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    const method = init?.method ?? 'GET'
    calls.push(method + ' ' + path)
    if (path.endsWith('/actions/tasks') && method === 'GET') {
      return new Response(JSON.stringify(stored))
    }
    if (path.endsWith('/actions/tasks/proposals') && method === 'GET') {
      return new Response(JSON.stringify(pending))
    }
    if (path.endsWith('/actions/tasks/proposals') && method === 'POST') {
      const body = JSON.parse(String(init?.body)) as { title: string }
      const proposal = { id: 'proposal-1', title: body.title, status: 'pending',
        createdAt: '2026-11-05T08:00:00Z' }
      pending.push(proposal)
      return new Response(JSON.stringify(proposal), { status: 201 })
    }
    if (path.endsWith('/proposals/proposal-1/approve') && method === 'POST') {
      const task = { id: 'task-1', title: 'Call the dentist', status: 'open',
        createdAt: '2026-11-05T08:00:00Z' }
      stored.push(task)
      pending.length = 0
      return new Response(JSON.stringify(task))
    }
    if (path.endsWith('/tasks/task-1/complete') && method === 'POST') {
      stored[0].status = 'completed'
      return new Response(JSON.stringify(stored[0]))
    }
    throw new Error('Unexpected task API call: ' + method + ' ' + path)
  }))
  render(<TasksPanel onClose={() => undefined} />)
  await screen.findByText('No tasks created yet.')
  fireEvent.change(screen.getByLabelText('Task to add'), {
    target: { value: 'Call the dentist' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Prepare task' }))
  await screen.findByText('Call the dentist')
  expect(screen.getByText('No tasks created yet.')).toBeInTheDocument()
  expect(stored).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Approve task' }))
  await waitFor(() => expect(stored).toHaveLength(1))
  expect(screen.queryByText('No tasks created yet.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Mark complete' }))
  await screen.findByText('Completed')
  expect(calls.some(entry => /POST .*\/actions\/tasks\/proposals\/proposal-1\/approve$/.test(entry))).toBe(true)
})

it('shows an error instead of claiming a failed action completed', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.method) return new Response('[]')
    return new Response(JSON.stringify({ title: 'Task service unavailable' }), { status: 503 })
  }))
  render(<TasksPanel onClose={() => undefined} />)
  await screen.findByText('No tasks created yet.')
  fireEvent.change(screen.getByLabelText('Task to add'), { target: { value: 'Call' } })
  fireEvent.click(screen.getByRole('button', { name: 'Prepare task' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Task service unavailable'))
  expect(screen.getByText('No tasks created yet.')).toBeInTheDocument()
})
