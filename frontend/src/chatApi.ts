import { healthUrl } from './health'

export type ChatEvent =
  | { kind: 'start'; requestId: string; messageId: string }
  | { kind: 'delta'; text: string }
  | { kind: 'plan'; plan: Record<string, unknown> }
  | { kind: 'tool'; trace: ToolTrace }
  | { kind: 'approval'; approval: Approval }
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
export interface ToolTrace {
  invocationId: string
  requestId: string
  toolName: string
  status: 'requested' | 'running' | 'awaiting_approval' | 'succeeded' | 'denied' | 'failed' | 'cancelled' | 'timeout'
}
export type ApprovalStatus = 'pending' | 'approved' | 'completed' | 'rejected' | 'failed' | 'expired' | 'cancelled'
export interface Approval {
  id: string; requestId: string; toolName: string; preview: string; expiresAt: string
  status: ApprovalStatus; result?: string; error?: string
}
export type PlanState = 'PROPOSED' | 'AWAITING_APPROVAL' | 'RUNNING' | 'BLOCKED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export interface TodoObservation { todos: { id: string; title: string; status: string }[]; truncated: boolean }
export interface PlanStep { id: string; title: string; state: string; dependsOn: string[]; pendingApprovalId?: string; observation?: TodoObservation }
export interface SavedPlan { id: string; goal: string; summary: string; state: PlanState; version: number; steps: PlanStep[]; updatedAt: string }

function approval(value: unknown): Approval {
  if (!object(value)) throw new Error('The server returned an invalid approval.')
  const status = value.status
  if (status !== 'pending' && status !== 'approved' && status !== 'completed' && status !== 'rejected'
    && status !== 'failed' && status !== 'expired' && status !== 'cancelled') throw new Error('The server returned an invalid approval.')
  return { id: text(value.id), requestId: text(value.requestId), toolName: text(value.toolName),
    preview: text(value.preview), expiresAt: text(value.expiresAt), status,
    ...(value.result == null ? {} : { result: text(value.result) }),
    ...(value.error == null ? {} : { error: text(value.error) }) }
}

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

function toolTrace(value: unknown): ToolTrace {
  if (!object(value)) throw new Error('The server returned an invalid tool activity.')
  const status = value.status
  if (status !== 'requested' && status !== 'running' && status !== 'awaiting_approval' && status !== 'succeeded' && status !== 'denied'
    && status !== 'failed' && status !== 'cancelled' && status !== 'timeout') {
    throw new Error('The server returned an invalid tool activity.')
  }
  return { invocationId: text(value.invocationId), requestId: text(value.requestId),
    toolName: text(value.toolName), status }
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
    case 'tool': return { kind: 'tool', trace: toolTrace(payload) }
    case 'approval': return { kind: 'approval', approval: approval(payload) }
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

export async function conversationToolTraces(id: string): Promise<ToolTrace[]> {
  const value = await jsonRequest(`/conversations/${encodeURIComponent(id)}/tool-traces`)
  if (!Array.isArray(value)) throw new Error('Could not load tool activity.')
  return value.map(toolTrace)
}

export async function conversationApprovals(id: string): Promise<Approval[]> {
  const value = await jsonRequest(`/conversations/${encodeURIComponent(id)}/approvals`)
  if (!Array.isArray(value)) throw new Error('Could not load approvals.')
  return value.map(approval)
}

export async function decideApproval(conversationId: string, approvalId: string, decision: 'approve' | 'reject'): Promise<Approval> {
  return approval(await jsonRequest(`/conversations/${encodeURIComponent(conversationId)}/approvals/${encodeURIComponent(approvalId)}/${decision}`, { method: 'POST' }))
}

function savedPlan(value: unknown): SavedPlan {
  const invalid = () => new Error('The server returned an invalid plan.')
  if (!object(value) || !Array.isArray(value.steps)) throw invalid()
  const states: PlanState[] = ['PROPOSED', 'AWAITING_APPROVAL', 'RUNNING', 'BLOCKED', 'COMPLETED', 'FAILED', 'CANCELLED']
  if (!states.includes(value.state as PlanState) || typeof value.version !== 'number' || !Number.isInteger(value.version)) throw invalid()
  const steps = value.steps.map((step): PlanStep => {
    if (!object(step) || !Array.isArray(step.dependsOn) || !step.dependsOn.every((id) => typeof id === 'string')) throw invalid()
    let observation: TodoObservation | undefined
    if (step.observation != null) {
      if (!object(step.observation) || !Array.isArray(step.observation.todos) || typeof step.observation.truncated !== 'boolean') throw invalid()
      const todos = step.observation.todos.map((todo) => {
        if (!object(todo)) throw invalid()
        return { id: text(todo.id), title: text(todo.title), status: text(todo.status) }
      })
      observation = { todos, truncated: step.observation.truncated }
    }
    return { id: text(step.id), title: text(step.title), state: text(step.state), dependsOn: step.dependsOn as string[],
      ...(step.pendingApprovalId != null ? { pendingApprovalId: text(step.pendingApprovalId) } : {}),
      ...(observation ? { observation } : {}) }
  })
  return { id: text(value.id), goal: text(value.goal), summary: text(value.summary), state: value.state as PlanState,
    version: value.version, steps, updatedAt: text(value.updatedAt) }
}

export async function conversationPlans(id: string): Promise<SavedPlan[]> {
  const value = await jsonRequest(`/conversations/${encodeURIComponent(id)}/plans`)
  if (!Array.isArray(value)) throw new Error('Could not load plans.')
  return value.map(savedPlan)
}

export async function runPlan(conversationId: string, planId: string): Promise<SavedPlan> {
  return savedPlan(await jsonRequest(`/conversations/${encodeURIComponent(conversationId)}/plans/${encodeURIComponent(planId)}/run`, { method: 'POST' }))
}

export async function cancelPlan(conversationId: string, planId: string): Promise<SavedPlan> {
  return savedPlan(await jsonRequest(`/conversations/${encodeURIComponent(conversationId)}/plans/${encodeURIComponent(planId)}/cancel`, { method: 'POST' }))
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
  revisePlan?: { revisePlanId: string; reviseVersion: number }
  onEvent: (event: ChatEvent) => void
}): Promise<void> {
  const response = await fetch(url(`/conversations/${encodeURIComponent(input.conversationId)}/messages/stream`), {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: input.signal,
    headers: { 'Content-Type': 'application/json', 'X-Gemini-Api-Key': input.apiKey,
      ...(input.useMemory === false ? { 'X-Chomiii-Use-Memory': 'false' } : {}) },
    body: JSON.stringify({ requestId: input.requestId, provider: input.provider, model: input.model,
      content: input.content, ...(input.plan || input.revisePlan ? { plan: true } : {}),
      ...(input.revisePlan ?? {}) }),
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
