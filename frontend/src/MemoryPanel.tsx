import { useEffect, useState } from 'react'
import { addMemory, clearMemories, listMemories, removeMemory, updateMemory, type PersonalMemory } from './memoryApi'

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : 'Could not update saved memories.'
}

export function MemoryPanel({ onClose }: { onClose: () => void }) {
  const [items, setItems] = useState<PersonalMemory[]>([])
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [content, setContent] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [editText, setEditText] = useState('')

  useEffect(() => {
    let active = true
    listMemories().then(found => {
      if (active) setItems(found)
    }).catch(reason => {
      if (active) setError(errorText(reason))
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [])

  async function save() {
    if (working || !content.trim()) return
    setWorking(true)
    setError('')
    try {
      const saved = await addMemory(content.trim())
      setItems(previous => [saved, ...previous])
      setContent('')
    } catch (reason) { setError(errorText(reason)) }
    finally { setWorking(false) }
  }

  async function change(id: string) {
    if (working || !editText.trim()) return
    setWorking(true)
    setError('')
    try {
      const saved = await updateMemory(id, editText.trim())
      setItems(previous => previous.map(item => item.id === id ? saved : item))
      setEditing(null)
      setEditText('')
    } catch (reason) { setError(errorText(reason)) }
    finally { setWorking(false) }
  }

  async function remove(id: string) {
    if (working) return
    setWorking(true)
    setError('')
    try {
      await removeMemory(id)
      setItems(previous => previous.filter(item => item.id !== id))
    } catch (reason) { setError(errorText(reason)) }
    finally { setWorking(false) }
  }

  async function clear() {
    if (working || !window.confirm('Delete all saved memories? This cannot be undone.')) return
    setWorking(true)
    setError('')
    try {
      await clearMemories()
      setItems([])
    } catch (reason) { setError(errorText(reason)) }
    finally { setWorking(false) }
  }

  return <div className="memory-backdrop">
    <section id="memories-dialog" className="memory-dialog" role="dialog" aria-modal="true" aria-label="Saved memories">
      <div className="memory-header">
        <h2>Saved memories</h2>
        <button type="button" className="text-button" onClick={onClose} aria-label="Close memories">Close</button>
      </div>
      <p className="memory-intro">
        Save information you want Chomi to use in future conversations on this browser.
        Chomi uses saved memories only when relevant, and you can edit or delete them here.
        Relevant memories are sent to your selected AI provider as part of chat requests.
        Saving one does not let Chomi access your apps or spend money.
      </p>
      <p className="memory-intro">
        Memories belong to this browser session, not an account. If you lose its session cookie,
        you may lose access to these memories. Do not save passwords or payment details.
      </p>
      <form className="memory-form" onSubmit={event => { event.preventDefault(); void save() }}>
        <label htmlFor="new-memory">What should Chomi remember?</label>
        <textarea id="new-memory" maxLength={500} value={content}
          onChange={event => setContent(event.target.value)}
          placeholder="e.g. My usual grocery order includes oats and milk." disabled={working} />
        <div className="memory-actions">
          <small>{content.length}/500</small>
          <button type="submit" className="memory-save" disabled={working || !content.trim()}>Save memory</button>
        </div>
      </form>
      {error && <p role="alert" className="memory-error">{error}</p>}
      {loading ? <p>Loading memories…</p> : <>
        <div className="memory-header">
          <h3>{items.length} saved {items.length === 1 ? 'memory' : 'memories'}</h3>
          {items.length > 0 && <button type="button" className="text-button memory-danger"
            disabled={working} onClick={() => void clear()}>Delete all</button>}
        </div>
        {items.length === 0 ? <p className="memory-empty">Nothing saved yet. Chomi will not invent your preferences.</p>
          : <ul className="memory-list">{items.map(item => <li className="memory-item" key={item.id}>
            {editing === item.id ? <>
              <label className="visually-hidden" htmlFor={'edit-' + item.id}>Edit saved memory</label>
              <textarea id={'edit-' + item.id} value={editText} maxLength={500}
                disabled={working} onChange={event => setEditText(event.target.value)} />
              <div className="memory-actions">
                <button type="button" disabled={working}
                  onClick={() => { setEditing(null); setEditText('') }}>Cancel edit</button>
                <button type="button" className="memory-save" disabled={working || !editText.trim()}
                  onClick={() => void change(item.id)}>Save changes</button>
              </div>
            </> : <>
              <p>{item.content}</p>
              <span className="memory-meta">You saved this · updated {new Date(item.updatedAt).toLocaleDateString()}</span>
              <div className="memory-actions">
                <button type="button" disabled={working}
                  onClick={() => { setEditing(item.id); setEditText(item.content) }}>Edit</button>
                <button type="button" disabled={working} className="memory-danger"
                  onClick={() => void remove(item.id)}>Delete</button>
              </div>
            </>}
          </li>)}</ul>}
      </>}
    </section>
  </div>
}
