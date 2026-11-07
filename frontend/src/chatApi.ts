import { healthUrl } from './health'

export type ChatEvent =
  | { kind: 'start'; requestId: string; messageId: string }
  | { kind: 'delta'; text: string }
  | { kind: 'plan'; plan: Record<string, unknown> }
  | { kind: 'complete'; messageId: string; status: 'complete' }
  | { kind: 'error'; code: string; message: string; status: 'failed' | 'interrupted' }

export interface Model { id: string; name: string }
export interface Provider { id: string; name: string; defaultModel: string; models: Model[] }
export interface ConversationSummary { id: string; title: string; createdAt: string; updatedAt: string }
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: 'pending' | 'complete' | 'failed' | 'interrupted'
  createdAt: string
}
export interface Conversation extends ConversationSummary { messages: ChatMessage[] }

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('The server returned an invalid response.')
  return value
}

function summary(value: unknown): ConversationSummary {
  if (!object(value)) throw new Error('The server returned an invalid conversation.')
  return { id: text(value.id), title: text(value.title), createdAt: text(value.createdAt), updatedAt: text(value.updatedAt) }
}

function message(value: unknown): ChatMessage {
  if (!object(value) || (value.role !== 'user' && value.role !== 'assistant')) {
    throw new Error('The server returned an invalid message.')
  }
  const status = value.status
  if (status !== 'pending' && status !== 'complete' && status !== 'failed' && status !== 'interrupted') {
    throw new Error('The server returned an invalid message.')
  }
  return { id: text(value.id), role: value.role, content: text(value.content),
    status, createdAt: text(value.createdAt) }
}

function parseEvent(frame: string): ChatEvent | undefined {
  let event = ''
  const data: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!event) return undefined
  let payload: unknown
  try { payload = JSON.parse(data.join('\n')) } catch { throw new Error('The chat stream was invalid.') }
  if (!object(payload)) throw new Error('The chat stream was invalid.')
  switch (event) {
    case 'start': return { kind: 'start', requestId: text(payload.requestId), messageId: text(payload.messageId) }
    case 'delta': return { kind: 'delta', text: text(payload.text) }
    case 'plan': return { kind: 'plan', plan: payload }
    case 'complete':
      if (payload.status !== 'complete') throw new Error('The chat stream was invalid.')
      return { kind: 'complete', messageId: text(payload.messageId), status: 'complete' }
    case 'error':
      if (payload.status !== 'failed' && payload.status !== 'interrupted') throw new Error('The chat stream was invalid.')
      return { kind: 'error', code: text(payload.code), message: text(payload.message), status: payload.status }
    default: throw new Error('The chat stream was invalid.')
  }
}

export async function readEvents(stream: ReadableStream<Uint8Array>, onEvent: (event: ChatEvent) => void): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  try {
    while (true) {
      const result = await reader.read()
      pending += result.done ? decoder.decode() : decoder.decode(result.value, { stream: true })
      pending = pending.replace(/\r\n/g, '\n')
      let boundary = pending.indexOf('\n\n')
      while (boundary >= 0) {
        const event = parseEvent(pending.slice(0, boundary))
        pending = pending.slice(boundary + 2)
        if (event) {
          onEvent(event)
          if (event.kind === 'complete' || event.kind === 'error') return
        }
        boundary = pending.indexOf('\n\n')
      }
      if (pending.length > 65_536) throw new Error('The chat stream was too large.')
      if (result.done) throw new Error('The chat stream ended before the response completed.')
    }
  } finally {
    reader.releaseLock()
  }
}

function url(path: string): string {
  return `${healthUrl().replace(/\/health$/, '')}${path}`
}

async function problem(response: Response): Promise<Error> {
  try {
    const value: unknown = await response.json()
    if (object(value) && typeof value.title === 'string') return new Error(value.title)
  } catch { /* The server may return an empty or non-JSON error. */ }
  return new Error(`Request failed (${response.status}).`)
}

async function jsonRequest(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(url(path), { credentials: 'same-origin', cache: 'no-store', ...init })
  if (!response.ok) throw await problem(response)
  return response.json()
}

export async function providers(): Promise<Provider[]> {
  const value = await jsonRequest('/chat/providers')
  if (!Array.isArray(value)) throw new Error('Could not load the provider list.')
  return value.map((entry: unknown) => {
    if (!object(entry) || !Array.isArray(entry.models)) throw new Error('Could not load the provider list.')
    return { id: text(entry.id), name: text(entry.name), defaultModel: text(entry.defaultModel),
      models: entry.models.map((item: unknown) => {
        if (!object(item)) throw new Error('Could not load the provider list.')
        return { id: text(item.id), name: text(item.name) }
      }) }
  })
}

export async function conversations(): Promise<ConversationSummary[]> {
  const value = await jsonRequest('/conversations')
  if (!Array.isArray(value)) throw new Error('Could not load conversations.')
  return value.map(summary)
}

export async function createConversation(): Promise<Conversation> {
  const value = await jsonRequest('/conversations', { method: 'POST' })
  return parseConversation(value)
}

export async function openConversation(id: string): Promise<Conversation> {
  return parseConversation(await jsonRequest(`/conversations/${encodeURIComponent(id)}`))
}

function parseConversation(value: unknown): Conversation {
  if (!object(value) || !Array.isArray(value.messages)) throw new Error('Could not load the conversation.')
  return { ...summary(value), messages: value.messages.map(message) }
}

export async function deleteConversation(id: string): Promise<void> {
  const response = await fetch(url(`/conversations/${encodeURIComponent(id)}`),
    { method: 'DELETE', credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw await problem(response)
}

export async function sendMessage(input: {
  conversationId: string
  requestId: string
  content: string
  provider: string
  model: string
  apiKey: string
  signal: AbortSignal
  useMemory?: boolean
  plan?: boolean
  onEvent: (event: ChatEvent) => void
}): Promise<void> {
  const response = await fetch(url(`/conversations/${encodeURIComponent(input.conversationId)}/messages/stream`), {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: input.signal,
    headers: { 'Content-Type': 'application/json', 'X-Gemini-Api-Key': input.apiKey,
      ...(input.useMemory === false ? { 'X-Chomiii-Use-Memory': 'false' } : {}) },
    body: JSON.stringify({ requestId: input.requestId, provider: input.provider, model: input.model,
      content: input.content, ...(input.plan ? { plan: true } : {}) }),
  })
  if (!response.ok) throw await problem(response)
  if (!response.body) throw new Error('The chat stream is unavailable.')
  await readEvents(response.body, input.onEvent)
}

export async function stopMessage(conversationId: string, requestId: string): Promise<void> {
  const response = await fetch(url(`/conversations/${encodeURIComponent(conversationId)}/messages/${encodeURIComponent(requestId)}/cancel`),
    { method: 'POST', credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok && response.status !== 409) throw await problem(response)
}
