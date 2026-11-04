import { healthUrl } from './health'

export interface TaskProposal {
  id: string
  title: string
  status: 'pending' | 'approved' | 'rejected'
  createdAt: string
}
export interface PersonalTask {
  id: string
  title: string
  status: 'open' | 'completed'
  createdAt: string
}
function url(path: string): string {
  return healthUrl().replace(/\/health$/, '') + '/actions/tasks' + path
}
async function request(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(url(path), { credentials: 'same-origin', cache: 'no-store', ...init })
  if (!response.ok) {
    let message = 'Task operation failed.'
    try {
      const payload: unknown = await response.json()
      if (typeof payload === 'object' && payload && 'title' in payload
        && typeof payload.title === 'string') message = payload.title
    } catch { /* no error JSON */ }
    throw new Error(message)
  }
  if (response.status === 204) return undefined
  return response.json()
}
function entry(input: unknown): { id: string; title: string; status: string; createdAt: string } {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    throw new Error('Invalid task response.')
  }
  const value = input as Record<string, unknown>
  if (typeof value.id !== 'string' || typeof value.title !== 'string'
    || typeof value.status !== 'string' || typeof value.createdAt !== 'string') {
    throw new Error('Invalid task response.')
  }
  return { id: value.id, title: value.title, status: value.status, createdAt: value.createdAt }
}
function proposal(v: unknown): TaskProposal {
  const obj = entry(v)
  if (!['pending', 'approved', 'rejected'].includes(obj.status)) throw new Error('Invalid task proposal.')
  return obj as TaskProposal
}
function task(v: unknown): PersonalTask {
  const obj = entry(v)
  if (!['open', 'completed'].includes(obj.status)) throw new Error('Invalid task status.')
  return obj as PersonalTask
}
export async function listTasks(): Promise<PersonalTask[]> {
  const data = await request('')
  if (!Array.isArray(data)) throw new Error('Invalid task list.')
  return data.map(task)
}
export async function listProposals(): Promise<TaskProposal[]> {
  const data = await request('/proposals')
  if (!Array.isArray(data)) throw new Error('Invalid proposal list.')
  return data.map(proposal)
}
export async function proposeTask(title: string): Promise<TaskProposal> {
  return proposal(await request('/proposals', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  }))
}
export async function approveTaskProposal(id: string): Promise<PersonalTask> {
  return task(await request('/proposals/' + encodeURIComponent(id) + '/approve', { method: 'POST' }))
}
export async function rejectTaskProposal(id: string): Promise<void> {
  await request('/proposals/' + encodeURIComponent(id), { method: 'DELETE' })
}
export async function completeTask(id: string): Promise<PersonalTask> {
  return task(await request('/' + encodeURIComponent(id) + '/complete', { method: 'POST' }))
}
