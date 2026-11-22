import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'
import type { Approval } from './chatApi'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function openPlanFixture(plan: Record<string, unknown>, approvals: Record<string, unknown>[] = []) {
  const summary = { id: 'plan-chat', title: 'Plan chat', createdAt: 'now', updatedAt: 'now' }
  const fetchedApprovals: string[] = []
  const calls: { method: string; path: string }[] = []
  let cancelled = false
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    calls.push({ method: init?.method ?? 'GET', path })
    if (path.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (path.endsWith('/chat/providers')) return new Response('[{"id":"gemini","name":"Gemini","defaultModel":"m","models":[{"id":"m","name":"M"}]}]')
    if (path.endsWith('/conversations') && init?.method === 'POST') return new Response(JSON.stringify({ ...summary, messages: [] }))
    if (path.endsWith('/conversations')) return new Response(JSON.stringify([summary]))
    if (path.endsWith('/plan-chat/plans')) return new Response(JSON.stringify([plan]))
    if (path.endsWith('/plan-chat/approvals')) {
      fetchedApprovals.push('GET')
      return new Response(JSON.stringify(cancelled ? approvals.map((item) => ({ ...item, status: 'cancelled' })) : approvals))
    }
    if (path.endsWith('/plan-chat/tool-traces')) return new Response('[]')
    if (path.endsWith('/plan-chat') && init?.method !== 'POST') return new Response(JSON.stringify({ ...summary, messages: [] }))
    if (path.endsWith('/plans/p1/run')) return new Response(JSON.stringify({ ...plan, state: 'COMPLETED' }))
    if (path.endsWith('/plans/p1/cancel')) { cancelled = true; return new Response(JSON.stringify({ ...plan, state: 'CANCELLED' })) }
    if (path.endsWith('/approvals/a1/approve') || path.endsWith('/approvals/a1/reject')) return new Response(JSON.stringify({ ...approvals[0], status: 'CANCELLED' }))
    throw new Error(`Unexpected request: ${path}`)
  }))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Plan chat' }))
  await screen.findByRole('heading', { name: String(plan.goal) })
  return { calls, fetchedApprovals }
}

const completedStep = { id: 'done', title: 'Completed task', state: 'COMPLETED', dependsOn: [],
  observation: { todos: [{ id: 'todo-done', title: 'Already done', status: 'completed' }], truncated: false } }
const proposedStep = { id: 'next', title: 'Independent task', state: 'PROPOSED', dependsOn: [] }
const awaitingStep = { id: 'approval-step', title: 'Needs approval', state: 'AWAITING_APPROVAL', dependsOn: [], pendingApprovalId: 'a1' }
const planFor = (state: string, steps: Record<string, unknown>[]) => ({ id: 'p1', goal: 'Plan goal', summary: 'Plan details', state,
  version: 1, updatedAt: 'now', steps })

it('offers Continue after partial failure only when a dependency-ready proposed step remains', async () => {
  const { calls } = await openPlanFixture(planFor('FAILED', [completedStep, { ...proposedStep, dependsOn: ['done'] },
    { id: 'failed', title: 'Failed task', state: 'FAILED', dependsOn: [] }]))
  expect(screen.getByRole('status')).toHaveTextContent('Failed after 1 completed step')
  expect(screen.getByRole('button', { name: 'Continue plan' })).toBeEnabled()
  expect(screen.getByText('Completed task — completed')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Continue plan' }))
  await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.path.endsWith('/plans/p1/run'))).toBe(true))
})

it('does not offer Continue after failure when no runnable proposed step remains', async () => {
  await openPlanFixture(planFor('FAILED', [completedStep, { id: 'failed', title: 'Failed task', state: 'FAILED', dependsOn: [] }]))
  expect(screen.getByRole('status')).toHaveTextContent('Failed after 1 completed step')
  expect(screen.queryByRole('button', { name: 'Continue plan' })).not.toBeInTheDocument()
})

it('does not offer Continue after failure while an approval is still pending', async () => {
  const approval = { id: 'a1', requestId: 'r1', toolName: 'todo.write', preview: 'Create a task',
    expiresAt: new Date(Date.now() + 300_000).toISOString(), status: 'pending' }
  await openPlanFixture(planFor('FAILED', [completedStep, awaitingStep, proposedStep]), [approval])
  expect(screen.queryByRole('button', { name: 'Continue plan' })).not.toBeInTheDocument()
})

it('does not offer Continue while approval is still pending and allows cancellation to invalidate and refetch its card', async () => {
  const approval = { id: 'a1', requestId: 'r1', toolName: 'todo.write', preview: 'Create a task',
    expiresAt: new Date(Date.now() + 300_000).toISOString(), status: 'pending' }
  const { calls, fetchedApprovals } = await openPlanFixture(planFor('AWAITING_APPROVAL', [completedStep, awaitingStep, proposedStep]), [approval])
  expect(within(screen.getByRole('region', { name: 'Saved plan' })).getByRole('status')).toHaveTextContent('Waiting for your approval')
  expect(screen.queryByRole('button', { name: 'Continue plan' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Cancel plan' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'Approve action' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel plan' }))
  await waitFor(() => expect(within(screen.getByRole('region', { name: 'Saved plan' })).getByRole('status')).toHaveTextContent('Cancelled after 1 completed step'))
  await waitFor(() => expect(fetchedApprovals.length).toBeGreaterThanOrEqual(2))
  expect(within(screen.getByRole('region', { name: 'Saved plan' })).getByRole('status')).toHaveTextContent('Cancelled after 1 completed step')
  expect(within(screen.getByRole('region', { name: 'Action approval' })).getByRole('status')).toHaveTextContent('Cancelled')
  expect(screen.queryByRole('button', { name: 'Approve action' })).not.toBeInTheDocument()
  expect(calls.some((call) => call.method === 'POST' && call.path.endsWith('/plans/p1/cancel'))).toBe(true)
})

it('offers Continue to resolve an expired approval when independent work is ready', async () => {
  const approval = { id: 'a1', requestId: 'r1', toolName: 'todo.write', preview: 'Create a task',
    expiresAt: '2000-01-01T00:00:00Z', status: 'pending' }
  await openPlanFixture(planFor('AWAITING_APPROVAL', [awaitingStep, proposedStep]), [approval])
  expect(within(screen.getByRole('region', { name: 'Action approval' })).getByRole('status')).toHaveTextContent('Approval expired')
  expect(screen.getByRole('button', { name: 'Continue plan' })).toBeEnabled()
})

it('an unrelated approval does not block a ready plan', async () => {
  await openPlanFixture(planFor('PROPOSED', [proposedStep]), [{ id: 'other', requestId: 'r2', toolName: 'todo.create',
    preview: 'Another action', expiresAt: new Date(Date.now() + 300_000).toISOString(), status: 'pending' }])
  expect(screen.getByRole('button', { name: 'Continue plan' })).toBeEnabled()
})

it('recognizes the server-reported expiry after reopening a partially failed plan', async () => {
  await openPlanFixture(planFor('FAILED', [awaitingStep, proposedStep]), [{ id: 'a1', requestId: 'r1', toolName: 'todo.create',
    preview: 'Expired action', expiresAt: '2000-01-01T00:00:00Z', status: 'expired' }])
  expect(screen.getByRole('button', { name: 'Continue plan' })).toBeEnabled()
})

it.each(['PROPOSED', 'BLOCKED'] as const)('does not offer Continue for an empty %s plan', async (state) => {
  await openPlanFixture(planFor(state, []))
  expect(screen.queryByRole('button', { name: 'Continue plan' })).not.toBeInTheDocument()
})

it('does not offer Continue for an expired approval when no independent work is ready', async () => {
  const approval = { id: 'a1', requestId: 'r1', toolName: 'todo.write', preview: 'Create a task',
    expiresAt: '2000-01-01T00:00:00Z', status: 'pending' }
  await openPlanFixture(planFor('AWAITING_APPROVAL', [awaitingStep]), [approval])
  expect(screen.queryByRole('button', { name: 'Continue plan' })).not.toBeInTheDocument()
})

it('labels a running plan in progress and does not offer pause, cancel, or continue', async () => {
  await openPlanFixture(planFor('RUNNING', [completedStep, { id: 'running', title: 'Current task', state: 'RUNNING', dependsOn: [] }, proposedStep]))
  expect(screen.getByRole('status')).toHaveTextContent('In progress')
  expect(screen.getByText(/Steps already in progress cannot be paused/)).toBeVisible()
  expect(screen.queryByRole('button', { name: /^(Continue|Pause|Cancel) plan$/ })).not.toBeInTheDocument()
})

it('waits for the matching conversation before applying approvals and drops approvals after open failure', async () => {
  let releaseSecond: ((response: Response) => void) | undefined
  const summaries = ['first', 'second', 'broken'].map((id) => ({ id, title: `${id} conversation`, createdAt: 'now', updatedAt: 'now' }))
  const approvalFor = (id: string) => ({ id, requestId: 'r', toolName: 'message.send', preview: `Preview ${id}`,
    expiresAt: '2035-01-01T00:00:00Z', status: 'pending' })
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
    const path = String(input)
    if (path.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (path.endsWith('/chat/providers')) return new Response('[{"id":"gemini","name":"Gemini","defaultModel":"m","models":[{"id":"m","name":"M"}]}]')
    if (path.endsWith('/conversations')) return new Response(JSON.stringify(summaries))
    if (path.endsWith('/tool-traces')) return new Response('[]')
    if (path.endsWith('/conversations/first/approvals')) return new Response(JSON.stringify([approvalFor('first')]))
    if (path.endsWith('/conversations/second/approvals')) return new Response(JSON.stringify([approvalFor('second')]))
    if (path.endsWith('/conversations/broken/approvals')) return new Response(JSON.stringify([approvalFor('broken')]))
    if (path.endsWith('/conversations/first')) return new Response(JSON.stringify({ ...summaries[0], messages: [] }))
    if (path.endsWith('/conversations/second')) return new Promise<Response>((resolve) => { releaseSecond = resolve })
    if (path.endsWith('/conversations/broken')) return new Response('unavailable', { status: 503 })
    throw new Error(`Unexpected request: ${path}`)
  }))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'first conversation' }))
  expect(await screen.findByText('Preview first')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: 'second conversation' }))
  await waitFor(() => expect(releaseSecond).toBeDefined())
  await waitFor(() => expect(screen.queryByText('Preview second')).not.toBeInTheDocument())
  expect(screen.queryByText('Preview first')).not.toBeInTheDocument()
  releaseSecond?.(new Response(JSON.stringify({ ...summaries[1], messages: [] })))
  expect(await screen.findByText('Preview second')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: 'broken conversation' }))
  await waitFor(() => expect(screen.queryByText('Preview broken')).not.toBeInTheDocument())
  expect(screen.queryByText('Preview second')).not.toBeInTheDocument()
})

it('renders SSE approvals, sends explicit decisions, and restores server approval state on reopen', async () => {
  const baseApproval = (id: string): Approval => ({ id, requestId: 'req', toolName: 'message.send', preview: `Preview ${id}`,
    expiresAt: '2035-01-01T00:00:00Z', status: 'pending' })
  const approvals = new Map<string, Approval>([['a1', baseApproval('a1')], ['a2', baseApproval('a2')]])
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined
  const fake = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input)
    if (path.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (path.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'model', models: [{ id: 'model', name: 'Model' }] },
    ]))
    if (path.endsWith('/conversations') && init?.method === 'POST') return new Response(JSON.stringify({
      id: 'chat', title: 'Saved approvals', createdAt: 'now', updatedAt: 'now', messages: [],
    }), { status: 201 })
    if (path.endsWith('/conversations')) return new Response(JSON.stringify([
      { id: 'chat', title: 'Saved approvals', createdAt: 'now', updatedAt: 'now' },
    ]))
    if (path.endsWith('/conversations/chat/approvals')) return new Response(JSON.stringify([...approvals.values()]))
    if (path.includes('/conversations/chat/approvals/') && init?.method === 'POST') {
      const match = path.match(/approvals\/(a[12])\/(approve|reject)$/)
      if (!match) throw new Error(`Unexpected decision: ${path}`)
      expect(init.body).toBeUndefined()
      const existing = approvals.get(match[1])
      if (!existing) throw new Error('Unknown approval')
      const value: Approval = { ...existing, status: match[2] === 'approve' ? 'approved' : 'rejected' }
      approvals.set(match[1], value)
      return new Response(JSON.stringify(value))
    }
    if (path.endsWith('/conversations/chat/tool-traces')) return new Response('[]')
    if (path.endsWith('/conversations/chat')) return new Response(JSON.stringify({ id: 'chat', title: 'Saved approvals',
      createdAt: 'now', updatedAt: 'now', messages: [] }))
    if (path.endsWith('/messages/stream')) return new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller } }))
    throw new Error(`Unexpected request: ${path}`)
  })
  vi.stubGlobal('fetch', fake)
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Please send' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  await waitFor(() => expect(stream).toBeDefined())
  stream?.enqueue(new TextEncoder().encode(`event:approval\ndata:${JSON.stringify(baseApproval('a1'))}\n\n`
    + `event:approval\ndata:${JSON.stringify(baseApproval('a2'))}\n\n`))
  expect(await screen.findByText('Preview a1')).toBeVisible()
  expect(await screen.findByText('Preview a2')).toBeVisible()
  fireEvent.click(within(screen.getByText('Preview a1').closest('section')!).getByRole('button', { name: 'Approve action' }))
  await waitFor(() => expect(within(screen.getByText('Preview a1').closest('section')!).getByRole('status')).toHaveTextContent('Approved'))
  fireEvent.click(within(screen.getByText('Preview a2').closest('section')!).getByRole('button', { name: 'Reject action' }))
  await waitFor(() => expect(within(screen.getByText('Preview a2').closest('section')!).getByRole('status')).toHaveTextContent('Rejected'))
  stream?.enqueue(new TextEncoder().encode('event:complete\ndata:{"messageId":"m","status":"complete"}\n\n'))
  stream?.close()
  fireEvent.click(await screen.findByRole('button', { name: 'Saved approvals' }))
  await waitFor(() => expect(within(screen.getByText('Preview a1').closest('section')!).getByRole('status')).toHaveTextContent('Approved'))
  expect(within(screen.getByText('Preview a2').closest('section')!).getByRole('status')).toHaveTextContent('Rejected')
})

it('ignores delayed approval loads and decisions after switching conversations', async () => {
  const load: { release?: (response: Response) => void } = {}
  let releaseDecision: ((response: Response) => void) | undefined
  const saved = [
    { id: 'old', title: 'Old conversation', createdAt: 'now', updatedAt: 'now' },
    { id: 'new', title: 'New conversation', createdAt: 'now', updatedAt: 'now' },
  ]
  const pending = { id: 'approval-old', requestId: 'r', toolName: 'message.send', preview: 'Old secret preview',
    expiresAt: '2035-01-01T00:00:00Z', status: 'pending' }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input)
    if (path.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (path.endsWith('/chat/providers')) return new Response('[{"id":"gemini","name":"Gemini","defaultModel":"m","models":[{"id":"m","name":"M"}]}]')
    if (path.endsWith('/conversations')) return new Response(JSON.stringify(saved))
    if (path.endsWith('/conversations/old')) return new Response(JSON.stringify({ ...saved[0], messages: [] }))
    if (path.endsWith('/conversations/new')) return new Response(JSON.stringify({ ...saved[1], messages: [] }))
    if (path.endsWith('/tool-traces')) return new Response('[]')
    if (path.endsWith('/conversations/old/approvals')) return new Promise<Response>((resolve) => { load.release = resolve })
    if (path.endsWith('/conversations/new/approvals')) return new Response('[]')
    if (path.endsWith('/approvals/approval-old/approve') && init?.method === 'POST') {
      return new Promise<Response>((resolve) => { releaseDecision = resolve })
    }
    throw new Error(`Unexpected request: ${path}`)
  }))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Old conversation' }))
  await waitFor(() => expect(load.release).toBeDefined())
  fireEvent.click(screen.getByRole('button', { name: 'New conversation' }))
  await screen.findByText('What’s on your mind?')
  load.release?.(new Response(JSON.stringify([pending])))
  await waitFor(() => expect(screen.queryByText('Old secret preview')).not.toBeInTheDocument())

  const firstLoad = load.release
  fireEvent.click(screen.getByRole('button', { name: 'Old conversation' }))
  await waitFor(() => expect(load.release).not.toBe(firstLoad))
  load.release?.(new Response(JSON.stringify([pending])))
  fireEvent.click(await screen.findByRole('button', { name: 'Approve action' }))
  await waitFor(() => expect(releaseDecision).toBeDefined())
  fireEvent.click(screen.getByRole('button', { name: 'New conversation' }))
  await screen.findByText('What’s on your mind?')
  releaseDecision?.(new Response(JSON.stringify({ ...pending, status: 'approved' })))
  await waitFor(() => expect(screen.queryByText('Old secret preview')).not.toBeInTheDocument())
})

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
  stream?.enqueue(new TextEncoder().encode('event:tool\ndata:{"invocationId":"tool-1","requestId":"r","toolName":"memory.search","status":"running"}\n\n'))
  await screen.findByText('Searching memories…')
  stream?.enqueue(new TextEncoder().encode('event:tool\ndata:{"invocationId":"tool-1","requestId":"r","toolName":"memory.search","status":"awaiting_approval"}\n\n'))
  await screen.findByText('Waiting for your approval…')
  stream?.enqueue(new TextEncoder().encode('event:tool\ndata:{"invocationId":"tool-1","requestId":"r","toolName":"memory.search","status":"succeeded"}\n\n'))
  await screen.findByText('Memory search completed')
  stream?.enqueue(new TextEncoder().encode('event:tool\ndata:{"invocationId":"tool-1","requestId":"r","toolName":"memory.search","status":"awaiting_approval"}\n\n'))
  await waitFor(() => expect(screen.getByText('Memory search completed')).toBeVisible())
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
    if (path.endsWith('/conversations/old-1/tool-traces')) return new Response(JSON.stringify([
      { invocationId: 'tool-1', requestId: 'request-1', toolName: 'memory.search', status: 'succeeded' },
    ]))
    if (path.endsWith('/conversations/old-2/tool-traces')) return new Response('[]')
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
  expect(screen.getByText('Memory search completed')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Second saved chat' }))
  await screen.findByText('From second chat')
  expect(screen.queryByText('From first chat')).not.toBeInTheDocument()
  expect(screen.queryByText('Memory search completed')).not.toBeInTheDocument()
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

it('starts provider streaming without waiting for a conversation-list refresh', async () => {
  let created = false
  let listed = false
  let streamed = false
  let releaseList: ((response: Response) => void) | undefined
  let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (url.endsWith('/conversations') && init?.method === 'POST') {
      created = true
      return new Response(JSON.stringify({ id: 'chat-1', title: 'New chat',
        createdAt: 'now', updatedAt: 'now', messages: [] }), { status: 201 })
    }
    if (url.endsWith('/conversations') && created) {
      listed = true
      return new Promise<Response>((resolve) => { releaseList = resolve })
    }
    if (url.endsWith('/conversations')) return new Response('[]')
    if (url.endsWith('/messages/stream')) {
      streamed = true
      return new Response(new ReadableStream<Uint8Array>({ start(controller) { streamController = controller } }),
        { headers: { 'content-type': 'text/event-stream' } })
    }
    if (url.endsWith('/conversations/chat-1')) return new Response(JSON.stringify({
      id: 'chat-1', title: 'Hello', createdAt: 'now', updatedAt: 'now', messages: [],
    }))
    throw new Error(`Unexpected request ${url}`)
  }))

  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'private-test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Hello' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  try {
    await waitFor(() => expect(streamed).toBe(true), { timeout: 700 })
    expect(created).toBe(true)
    expect(listed).toBe(false)
  } finally {
    releaseList?.(new Response('[]'))
    streamController?.enqueue(new TextEncoder().encode('event:complete\ndata:{"messageId":"m","status":"complete"}\n\n'))
    streamController?.close()
  }
})

it('shows missing tool activity, retries it, and does not leak it after switching chats', async () => {
  let failOldTrace = true
  let releaseOldTrace: ((response: Response) => void) | undefined
  const conversations = [
    { id: 'old', title: 'Old chat', createdAt: 'now', updatedAt: 'now' },
    { id: 'new', title: 'New chat', createdAt: 'now', updatedAt: 'now' },
  ]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (url.endsWith('/conversations')) return new Response(JSON.stringify(conversations))
    if (url.endsWith('/approvals')) return new Response('[]')
    if (url.endsWith('/conversations/old/tool-traces')) {
      if (failOldTrace) { failOldTrace = false; return new Response('unavailable', { status: 503 }) }
      return new Promise<Response>((resolve) => { releaseOldTrace = resolve })
    }
    if (url.endsWith('/conversations/new/tool-traces')) return new Response('[]')
    if (url.endsWith('/conversations/old')) return new Response(JSON.stringify({ ...conversations[0], messages: [
      { id: 'old-message', role: 'user', content: 'Readable old chat', status: 'complete', createdAt: 'now' },
    ] }))
    if (url.endsWith('/conversations/new')) return new Response(JSON.stringify({ ...conversations[1], messages: [
      { id: 'new-message', role: 'user', content: 'Readable new chat', status: 'complete', createdAt: 'now' },
    ] }))
    throw new Error(`Unexpected request: ${url}`)
  }))

  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'private-test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Old chat' }))
  await screen.findByText('Readable old chat')
  expect(await screen.findByText('Tool activity could not be loaded.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
  await waitFor(() => expect(releaseOldTrace).toBeDefined())
  fireEvent.click(screen.getByRole('button', { name: 'New chat' }))
  await screen.findByText('Readable new chat')
  expect(screen.queryByText('Tool activity could not be loaded.')).not.toBeInTheDocument()
  releaseOldTrace?.(new Response(JSON.stringify([
    { invocationId: 'old-tool', requestId: 'r', toolName: 'memory.search', status: 'succeeded' },
  ])))
  await waitFor(() => expect(screen.queryByText('Tool activity could not be loaded.')).not.toBeInTheDocument())
  expect(screen.queryByText('Memory search completed')).not.toBeInTheDocument()
})

it('shows a conversation while its tool history request is still pending', async () => {
  let traceRequested = false
  let releaseTrace: ((response: Response) => void) | undefined
  const saved = { id: 'saved', title: 'Saved chat', createdAt: 'now', updatedAt: 'now' }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (url.endsWith('/chat/providers')) return new Response(JSON.stringify([
      { id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
        models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }] },
    ]))
    if (url.endsWith('/conversations')) return new Response(JSON.stringify([saved]))
    if (url.endsWith('/conversations/saved/tool-traces')) {
      traceRequested = true
      return new Promise<Response>((resolve) => { releaseTrace = resolve })
    }
    if (url.endsWith('/conversations/saved')) return new Response(JSON.stringify({ ...saved, messages: [
      { id: 'saved-message', role: 'user', content: 'I can read this now', status: 'complete', createdAt: 'now' },
    ] }))
    throw new Error(`Unexpected request: ${url}`)
  }))

  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'private-test-key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Saved chat' }))
  expect(await screen.findByText('I can read this now')).toBeInTheDocument()
  expect(traceRequested).toBe(true)
  expect(releaseTrace).toBeDefined()
  expect(screen.queryByText('Tool activity could not be loaded.')).not.toBeInTheDocument()
  releaseTrace?.(new Response('[]'))
})

it('selects a saved plan for revision and submits the revision through the stream', async () => {
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined
  const bodies: Record<string, unknown>[] = []
  const plan = { id: 'plan-1', goal: 'Plan a trip', summary: 'Visit the coast', state: 'PROPOSED', version: 7,
    updatedAt: '2020-01-01T00:00:00Z', steps: [
      { id: 'todo-step', title: 'Check itinerary', state: 'COMPLETED', dependsOn: [],
        observation: { todos: [{ id: 'todo-1', title: 'Book a hotel', status: 'pending' }], truncated: true } },
      { id: 'empty-step', title: 'Review notes', state: 'COMPLETED', dependsOn: [],
        observation: { todos: [], truncated: false } },
    ] }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/health')) return new Response('{"status":"UP","service":"chomiii"}')
    if (url.endsWith('/chat/providers')) return new Response('[{"id":"gemini","name":"Gemini","defaultModel":"m","models":[{"id":"m","name":"M"}]}]')
    if (url.endsWith('/conversations') && init?.method === 'POST') return new Response(JSON.stringify({ id: 'chat', title: 'Trip', createdAt: '2020-01-01', updatedAt: '2020-01-01', messages: [] }))
    if (url.endsWith('/conversations')) return new Response('[]')
    if (url.endsWith('/conversations/chat/plans')) return new Response(JSON.stringify([plan]))
    if (url.endsWith('/conversations/chat/approvals') || url.endsWith('/conversations/chat/tool-traces')) return new Response('[]')
    if (url.endsWith('/messages/stream')) {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      bodies.push(body)
      return new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller } }))
    }
    if (url.endsWith('/conversations/chat')) return new Response(JSON.stringify({ id: 'chat', title: 'Trip', createdAt: '2020-01-01', updatedAt: '2020-01-01', messages: [] }))
    throw new Error(`Unexpected request: ${url}`)
  }))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'key' } })
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Start trip plan' } })
  fireEvent.click(screen.getByRole('button', { name: 'Make a plan' }))
  await waitFor(() => expect(stream).toBeDefined())
  stream?.enqueue(new TextEncoder().encode('event:complete\ndata:{"messageId":"m1","status":"complete"}\n\n'))
  stream?.close()
  await screen.findByRole('heading', { name: 'Plan a trip' })
  expect(screen.getByRole('status')).toHaveTextContent('Ready to start')
  expect(screen.getByText('Book a hotel — pending')).toBeVisible()
  expect(screen.getByText('Some tasks were omitted.')).toBeVisible()
  expect(screen.getByText('No tasks found.')).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Pause plan' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Revise plan' }))
  expect(screen.getByText(/Revising plan: Plan a trip/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Clear selection' }))
  expect(screen.queryByText(/Revising plan:/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Revise plan' }))
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Add two days' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  await waitFor(() => expect(bodies).toHaveLength(2))
  expect(bodies[1]).toMatchObject({ content: 'Add two days', plan: true, revisePlanId: 'plan-1', reviseVersion: 7 })
  expect(screen.getByRole('button', { name: 'Revising this plan' })).toBeDisabled()
  stream?.enqueue(new TextEncoder().encode('event:complete\ndata:{"messageId":"m","status":"complete"}\n\n'))
  stream?.close()
  await waitFor(() => expect(screen.queryByText(/Revising plan:/)).not.toBeInTheDocument())
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'A normal follow-up' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  await waitFor(() => expect(bodies).toHaveLength(3))
  expect(bodies[2]).toMatchObject({ content: 'A normal follow-up' })
  expect(bodies[2]).not.toHaveProperty('revisePlanId')
  expect(bodies[2]).not.toHaveProperty('reviseVersion')
})
