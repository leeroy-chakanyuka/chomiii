import { healthUrl } from './health'

export interface PersonalMemory {
  id: string
  content: string
  source: 'user_explicit' | 'conversation_fact' | 'inferred'
  status: 'active' | 'suggested'
  confidence: number
  sourceConversationId: string | null
  createdAt: string
  updatedAt: string
}

const root = () => healthUrl().replace(/\/health$/, '')

function parseMemory(value: unknown): PersonalMemory {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('Invalid memory response.')
  }
  const v = value as Record<string, unknown>
  if (typeof v.id !== 'string' || typeof v.content !== 'string'
    || !['user_explicit', 'conversation_fact', 'inferred'].includes(String(v.source))
    || typeof v.createdAt !== 'string'
    || typeof v.updatedAt !== 'string') {
    throw new Error('Invalid memory response.')
  }
  return { id: v.id as string, content: v.content as string,
    source: v.source as PersonalMemory['source'],
    status: v.status === 'suggested' ? 'suggested' : 'active',
    confidence: typeof v.confidence === 'number' && v.confidence >= 0 && v.confidence <= 1 ? v.confidence : 1,
    sourceConversationId: typeof v.sourceConversationId === 'string' ? v.sourceConversationId : null,
    createdAt: v.createdAt as string, updatedAt: v.updatedAt as string }
}

async function request(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(root() + '/memories' + path, {
    credentials: 'same-origin', cache: 'no-store', ...init,
  })
  if (!response.ok) {
    let title: unknown
    try { title = (await response.json() as { title?: unknown }).title } catch { /* empty response */ }
    throw new Error(typeof title === 'string' ? title : 'Could not update memories.')
  }
  return response
}

export async function listMemories(): Promise<PersonalMemory[]> {
  const body: unknown = await (await request('')).json()
  if (!Array.isArray(body)) throw new Error('Invalid memory list.')
  return body.map(parseMemory)
}

export async function addMemory(content: string): Promise<PersonalMemory> {
  const body: unknown = await (await request('', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })).json()
  return parseMemory(body)
}

export async function updateMemory(id: string, content: string): Promise<PersonalMemory> {
  const body: unknown = await (await request('/' + encodeURIComponent(id), {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })).json()
  return parseMemory(body)
}

export async function removeMemory(id: string): Promise<void> {
  await request('/' + encodeURIComponent(id), { method: 'DELETE' })
}

export async function clearMemories(): Promise<void> {
  await request('', { method: 'DELETE' })
}

export async function approveMemory(id: string): Promise<PersonalMemory> {
  const body: unknown = await (await request('/' + encodeURIComponent(id) + '/approve',
    { method: 'POST' })).json()
  return parseMemory(body)
}
