import { describe, expect, it, vi } from 'vitest'
import { readEvents, type ChatEvent } from './chatApi'

describe('POST conversation stream', () => {
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
})
