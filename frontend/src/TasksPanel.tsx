import { useEffect, useState } from 'react'
import {
  approveTaskProposal, completeTask, listProposals, listTasks, proposeTask,
  rejectTaskProposal, type PersonalTask, type TaskProposal,
} from './taskApi'

function message(reason: unknown) {
  return reason instanceof Error ? reason.message : 'Could not manage tasks.'
}

export function TasksPanel({ onClose }: { onClose: () => void }) {
  const [tasks, setTasks] = useState<PersonalTask[]>([])
  const [proposals, setProposals] = useState<TaskProposal[]>([])
  const [title, setTitle] = useState('')
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    Promise.all([listTasks(), listProposals()]).then(([current, pending]) => {
      if (!active) return
      setTasks(current)
      setProposals(pending)
    }).catch(reason => { if (active) setError(message(reason)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  async function draft() {
    if (working || !title.trim()) return
    setWorking(true)
    setError('')
    try {
      const next = await proposeTask(title.trim())
      setProposals(existing => [next, ...existing])
      setTitle('')
    } catch (reason) { setError(message(reason)) }
    finally { setWorking(false) }
  }

  async function approve(id: string) {
    if (working) return
    setWorking(true)
    setError('')
    try {
      const created = await approveTaskProposal(id)
      setTasks(existing => existing.some(task => task.id === created.id)
        ? existing : [created, ...existing])
      setProposals(existing => existing.filter(item => item.id !== id))
    } catch (reason) { setError(message(reason)) }
    finally { setWorking(false) }
  }

  async function reject(id: string) {
    if (working) return
    setWorking(true)
    setError('')
    try {
      await rejectTaskProposal(id)
      setProposals(existing => existing.filter(item => item.id !== id))
    } catch (reason) { setError(message(reason)) }
    finally { setWorking(false) }
  }

  async function complete(id: string) {
    if (working) return
    setWorking(true)
    setError('')
    try {
      const result = await completeTask(id)
      setTasks(existing => existing.map(item => item.id === id ? result : item))
    } catch (reason) { setError(message(reason)) }
    finally { setWorking(false) }
  }

  return <div className="memory-backdrop">
    <section id="tasks-dialog" role="dialog" aria-modal="true" aria-label="Personal tasks"
      className="memory-dialog">
      <div className="memory-header">
        <h2>Tasks</h2>
        <button type="button" className="text-button" onClick={onClose}>Close</button>
      </div>
      <p className="memory-intro">
        First M3 action: prepare a task and explicitly approve it before Chomi saves it.
        Nothing is emailed, booked, scheduled, or sent elsewhere.
      </p>
      <form className="memory-form" onSubmit={event => { event.preventDefault(); void draft() }}>
        <label htmlFor="new-task">Task to add</label>
        <input id="new-task" type="text" maxLength={200} value={title}
          disabled={working} onChange={event => setTitle(event.target.value)}
          placeholder="e.g. Follow up about my appointment" />
        <div className="memory-actions">
          <button type="submit" className="memory-save" disabled={working || !title.trim()}>Prepare task</button>
        </div>
      </form>
      {error && <p role="alert" className="memory-error">{error}</p>}
      {loading ? <p>Loading tasks…</p> : <>
        <h3>Awaiting your approval</h3>
        {proposals.length === 0 ? <p className="memory-empty">No pending tasks.</p>
          : <ul className="memory-list">{proposals.map(item => <li className="memory-item" key={item.id}>
            <p>{item.title}</p>
            <div className="memory-actions">
              <button type="button" disabled={working} onClick={() => void reject(item.id)}>Reject</button>
              <button type="button" className="memory-save" disabled={working}
                onClick={() => void approve(item.id)}>Approve task</button>
            </div>
          </li>)}</ul>}
        <h3>My tasks</h3>
        {tasks.length === 0 ? <p className="memory-empty">No tasks created yet.</p>
          : <ul className="memory-list">{tasks.map(item => <li className="memory-item" key={item.id}>
            <p>{item.title}</p>
            <span className="memory-meta">{item.status === 'completed' ? 'Completed' : 'Open'}</span>
            {item.status === 'open' && <div className="memory-actions">
              <button type="button" disabled={working}
                onClick={() => void complete(item.id)}>Mark complete</button>
            </div>}
          </li>)}</ul>}
      </>}
    </section>
  </div>
}
