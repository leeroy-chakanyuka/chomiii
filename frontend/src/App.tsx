import { useEffect, useRef, useState } from 'react'
import { fetchHealth, type HealthState } from './health'
import { useChat } from './useChat'
import { MarkdownReply } from './MarkdownReply'
import { MemoryPanel } from './MemoryPanel'

export default function App() {
  const [memoriesOpen, setMemoriesOpen] = useState(false)
  const [health, setHealth] = useState<HealthState>({ status: 'loading' })
  const chat = useChat()
  const scrollArea = useRef<HTMLDivElement>(null)
  const followOutput = useRef(true)

  useEffect(() => {
    let current = true
    fetchHealth().then((settled) => { if (current) setHealth(settled) })
    return () => { current = false }
  }, [])

  useEffect(() => {
    if (followOutput.current && scrollArea.current) {
      scrollArea.current.scrollTop = scrollArea.current.scrollHeight
    }
  }, [chat.messages, chat.streamed])

  const selectedProvider = chat.catalog.find((item) => item.id === chat.provider)

  return (
    <main className={chat.connected ? 'app chat-layout' : 'app setup-layout'}>
      <header className="topbar">
        <h1 className="wordmark">chom<span className="threes">iii</span></h1>
        <p className="status" data-testid="status" data-state={health.status}>
          {health.status === 'loading' ? 'checking…' : health.status}
        </p>
        <button type="button" className="text-button memories-shortcut"
          aria-expanded={memoriesOpen} aria-controls="memories-dialog"
          onClick={() => setMemoriesOpen(true)}>Memories</button>
        {chat.connected && <div className="topbar-actions">
          <span className="model-pill">Gemini · {chat.model}</span>
          <button type="button" className="text-button" onClick={chat.changeKey}>Change key or model</button>
          <button type="button" className="text-button" onClick={() => void chat.removeKey()}>Remove key</button>
        </div>}
      </header>

      {memoriesOpen && <MemoryPanel onClose={() => setMemoriesOpen(false)} />}
      {!chat.connected ? <section className="setup-panel">
        <p className="eyebrow">WELCOME TO CHOMI</p>
        <p className="setup-title">A little space to think out loud.</p>
        <p className="intro">Ask a question, work through an idea, or pick up a conversation where you left off.</p>
        <form onSubmit={(event) => { event.preventDefault(); void chat.connect() }}>
          <label htmlFor="provider">Provider</label>
          <select id="provider" value={chat.provider} disabled={chat.connecting}
            onChange={(event) => chat.setProvider(event.target.value)}>
            <option value="gemini">Gemini</option>
          </select>
          <label htmlFor="api-key">Gemini API key</label>
          <input id="api-key" type="password" autoComplete="off" spellCheck={false}
            disabled={chat.connecting} value={chat.key} onChange={(event) => chat.setKey(event.target.value)}
            placeholder="Paste your API key" />
          <label htmlFor="model">Model</label>
          <select id="model" value={chat.model} onChange={(event) => chat.setModel(event.target.value)}
            disabled={chat.loading || chat.connecting || !selectedProvider}>
            {selectedProvider?.models.map((model) =>
              <option key={model.id} value={model.id}>{model.name}</option>)}
          </select>
          <label className="remember-option" htmlFor="remember-key">
            <input id="remember-key" type="checkbox" checked={chat.remember} disabled={chat.connecting}
              onChange={(event) => chat.setRemember(event.target.checked)} />
            <span>Remember this key on this device</span>
          </label>
          {chat.error && <p role="alert" className="error">{chat.error}</p>}
          <button className="primary-button" type="submit" disabled={chat.loading || chat.connecting || !selectedProvider}>
            {chat.connecting ? 'Connecting…' : 'Start chatting'}
          </button>
        </form>
        <p className="small-print">Get a key from <a href="https://aistudio.google.com/apikey" target="_blank" rel="noopener noreferrer">Google AI Studio</a>. Without Remember, your key stays in memory until you close this tab. Remember saves an encrypted copy in this browser profile so you can return without re-entering it. This is convenience storage, not protection against a compromised browser; another app served from the same local address and port could also access it. Google account limits or charges may apply.</p>
        {chat.history.length > 0 && <p className="small-print">Your conversations will be here when you reconnect with a key.</p>}
        <p className="learning-note">Chomi learns automatically: an eligible message may be sent to your AI provider again for memory extraction after a completed reply, using extra model tokens on your key. Review, correct, or delete what Chomi learns in Memories.</p>
      </section> : <div className="workspace">
        <aside className="sidebar" aria-label="Conversations">
          <button type="button" className="new-chat" onClick={chat.newChat} disabled={chat.busy}>+ New chat</button>
          <nav aria-label="Previous conversations">
            {chat.history.map((conversation) => <div className="conversation-row" key={conversation.id}>
              <button type="button" className={conversation.id === chat.selectedId ? 'conversation-link selected' : 'conversation-link'}
                disabled={chat.busy} onClick={() => void chat.switchChat(conversation.id)}>
                {conversation.title}
              </button>
              <button type="button" className="delete-chat" aria-label={`Delete ${conversation.title}`}
                disabled={chat.busy} onClick={() => void chat.removeChat(conversation.id)}>×</button>
            </div>)}
          </nav>
        </aside>
        <section className="conversation-view" aria-label="Chat">
          <div className="message-scroll" ref={scrollArea} onScroll={(event) => {
            const area = event.currentTarget
            followOutput.current = area.scrollHeight - area.scrollTop - area.clientHeight < 120
          }}>
            <div className="message-column">
              {chat.messages.length === 0 && <div className="empty-state">
                <p className="eyebrow">JUST THE TWO OF US</p>
                <p className="empty-title">What’s on your mind?</p>
                <p>Ask Chomi to explain something, explore an idea, or help you write. Your chat stays here for this browser session.</p>
                <p className="learning-note">After a completed reply, Chomi may send your message to your AI provider again for memory extraction, using extra model tokens on your key. What it learns is kept in Memories, where you can correct or delete it.</p>
              </div>}
              {chat.messages.map((message) => <article className={`message ${message.role}`} key={message.id}>
                <span className="speaker">{message.role === 'user' ? 'You' : 'Chomi'}</span>
                {message.role === 'assistant' && message.status === 'complete'
                  ? <MarkdownReply content={message.content} />
                  : <p>{message.content || (message.status === 'pending' ? 'Thinking…' : '')}</p>}
                {message.status === 'failed' || message.status === 'interrupted'
                  ? <small className="message-note">{message.status === 'failed' ? 'Response failed' : 'Response stopped'}</small> : null}
              </article>)}
              {chat.busy && <article className="message assistant pending" aria-live="polite">
                <span className="speaker">Chomi</span>
                {chat.streamed ? <MarkdownReply content={chat.streamed} /> : <p>Thinking…</p>}
              </article>}
            </div>
          </div>
          <div className="composer-area">
            {chat.error && <p role="alert" className="error">{chat.error}</p>}
            <form className="composer" onSubmit={(event) => { event.preventDefault(); void chat.submit() }}>
              <label className="visually-hidden" htmlFor="message">Message</label>
              <textarea id="message" placeholder="Message Chomi…" rows={2} maxLength={8000}
                value={chat.input} onChange={(event) => chat.setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    void chat.submit()
                  }
                }} disabled={chat.busy} />
              {chat.busy ? <button type="button" className="stop-button" onClick={() => void chat.stop()}>
                Stop generation
              </button> : <>
                <button type="button" className="text-button" disabled={!chat.input.trim()}
                  onClick={() => void chat.submit(true)}>Make a plan</button>
                <button type="submit" className="primary-button" disabled={!chat.input.trim()}>Send</button>
              </>}
            </form>
            <div className="composer-footer">
              <p className="composer-hint">Enter to send · Shift+Enter for a new line</p>
              <label className="memory-use-toggle">
                <input type="checkbox" checked={chat.useMemory} disabled={chat.busy}
                  onChange={event => chat.setUseMemory(event.target.checked)} />
                Use saved memories
              </label>
            </div>
          </div>
        </section>
      </div>}
    </main>
  )
}
