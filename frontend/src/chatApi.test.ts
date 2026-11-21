import { describe, expect, it, vi } from 'vitest'
import { conversationApprovals, decideApproval, readEvents, type ChatEvent } from './chatApi'

describe('POST conversation stream', () => {
  it('parses approval SSE events without ending the chat stream', async () => {
    const approval = { id: 'a1', requestId: 'r', toolName: 'message.send', preview: 'Send a note', expiresAt: '2030-01-01T00:00:00Z', status: 'pending' }
    const bytes = new TextEncoder().encode(`event:approval\ndata:${JSON.stringify(approval)}\n\n`
      + 'event:complete\ndata:{"messageId":"m","status":"complete"}\n\n')
    const events: ChatEvent[] = []
    await readEvents(new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close() } }), (event) => events.push(event))
    expect(events[0]).toEqual({ kind: 'approval', approval })
    expect(events[1]?.kind).toBe('complete')
  })

  it('uses owner-scoped approval endpoints and sends no decision arguments', async () => {
    const approval = { id: 'a/1', requestId: 'r', toolName: 'message.send', preview: 'Send', expiresAt: '2030-01-01T00:00:00Z', status: 'approved' }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      void input; void init
      return new Response(JSON.stringify([approval]))
    })
    vi.stubGlobal('fetch', fetchMock)
    try {
      await conversationApprovals('chat/1')
      expect(fetchMock.mock.calls[0]?.[0]).toContain('/conversations/chat%2F1/approvals')
      expect((fetchMock.mock.calls[0]?.[1] as RequestInit).credentials).toBe('same-origin')
      fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        void input; void init
        return new Response(JSON.stringify(approval))
      })
      await decideApproval('chat/1', 'a/1', 'approve')
      const init = fetchMock.mock.calls[1]?.[1] as RequestInit
      expect(fetchMock.mock.calls[1]?.[0]).toContain('/approvals/a%2F1/approve')
      expect(init.method).toBe('POST')
      expect(init.body).toBeUndefined()
    } finally { vi.unstubAllGlobals() }
  })

  it('accepts null optional fields and rejects unknown approval statuses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      void input; void init
      return new Response(JSON.stringify([{ id: 'a', requestId: 'r', toolName: 'x', preview: 'safe',
        expiresAt: '2030-01-01T00:00:00Z', status: 'pending', result: null, error: null }]))
    })
    vi.stubGlobal('fetch', fetchMock)
    try {
      await expect(conversationApprovals('chat')).resolves.toMatchObject([{ status: 'pending' }])
      fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        void input; void init
        return new Response(JSON.stringify([{
          id: 'a', requestId: 'r', toolName: 'x', preview: 'safe', expiresAt: '2030-01-01T00:00:00Z', status: 'unknown',
        }]))
      })
      await expect(conversationApprovals('chat')).rejects.toThrow('invalid approval')
    } finally { vi.unstubAllGlobals() }
  })
  it('delivers text before the provider completes and preserves chunk order', async () => {
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { streamController = controller },
    })
    const received: ChatEvent[] = []
    const done = readEvents(stream, (event) => received.push(event))
    const write = (text: string) => streamController?.enqueue(new TextEncoder().encode(text))

    write('event:start\ndata:{"requestId":"r","messageId":"m"}\n\n')
    write('event:delta\ndata:{"text":"Hel')
    write('lo"}\n\n')
    await vi.waitFor(() => expect(received).toContainEqual({ kind: 'delta', text: 'Hello' }))
    expect(received.some((event) => event.kind === 'complete')).toBe(false)

    write('event:delta\ndata:{"text":" world"}\n\n')
    write('event:complete\ndata:{"messageId":"m","status":"complete"}\n\n')
    streamController?.close()
    await done
    expect(received).toEqual([
      { kind: 'start', requestId: 'r', messageId: 'm' },
      { kind: 'delta', text: 'Hello' },
      { kind: 'delta', text: ' world' },
      { kind: 'complete', messageId: 'm', status: 'complete' },
    ])
  })

  it('reports an interrupted stream instead of marking it complete', async () => {
    const bytes = new TextEncoder().encode('event:delta\ndata:{"text":"partial"}\n\n'
      + 'event:error\ndata:{"code":"INTERRUPTED","message":"Generation stopped.","status":"interrupted"}\n\n')
    const events: ChatEvent[] = []
    await readEvents(new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close() } }),
      (event) => events.push(event))
    expect(events.at(-1)).toEqual({ kind: 'error', code: 'INTERRUPTED', message: 'Generation stopped.', status: 'interrupted' })
  })

  it('accepts a structured plan before its readable chat response', async () => {
    const plan = { id: 'plan-1', goal: 'Prepare for the meeting', executed: false, steps: [] }
    const bytes = new TextEncoder().encode(`event:plan\ndata:${JSON.stringify(plan)}\n\n`
      + 'event:delta\ndata:{"text":"Proposed plan — nothing has been done yet"}\n\n'
      + 'event:complete\ndata:{"messageId":"m","status":"complete"}\n\n')
    const events: ChatEvent[] = []
    await readEvents(new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close() } }),
      (event) => events.push(event))
    expect(events).toContainEqual({ kind: 'plan', plan })
    expect(events).toContainEqual({ kind: 'delta', text: 'Proposed plan — nothing has been done yet' })
  })

  it('delivers validated tool progress and completion events', async () => {
    const event = { invocationId: 'tool-1', requestId: 'r', toolName: 'memory.search', status: 'running' }
    const bytes = new TextEncoder().encode(`event:tool\ndata:${JSON.stringify(event)}\n\n`
      + 'event:complete\ndata:{"messageId":"m","status":"complete"}\n\n')
    const events: ChatEvent[] = []
    await readEvents(new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close() } }),
      (value) => events.push(value))
    expect(events).toContainEqual({ kind: 'tool', trace: event })
  })

  it('parses the awaiting-approval tool trace state', async () => {
    const event = { invocationId: 'tool-2', requestId: 'r', toolName: 'message.send', status: 'awaiting_approval' }
    const bytes = new TextEncoder().encode(`event:tool\ndata:${JSON.stringify(event)}\n\n`
      + 'event:complete\ndata:{"messageId":"m","status":"complete"}\n\n')
    const events: ChatEvent[] = []
    await readEvents(new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close() } }), (item) => events.push(item))
    expect(events).toContainEqual({ kind: 'tool', trace: event })
  })
})
