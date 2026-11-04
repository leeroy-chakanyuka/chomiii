import { useEffect, useRef, useState } from 'react'
import {
  conversations, createConversation, deleteConversation, openConversation, providers,
  sendMessage, stopMessage,
  type ChatMessage, type ConversationSummary, type Provider,
} from './chatApi'

function errorText(cause: unknown): string {
  return cause instanceof Error ? cause.message : 'Something went wrong. Try again.'
}

export function useChat() {
  const [catalog, setCatalog] = useState<Provider[]>([])
  const [history, setHistory] = useState<ConversationSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [connected, setConnected] = useState(false)
  const [key, setKey] = useState('')
  const [provider, setProvider] = useState('gemini')
  const [model, setModel] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streamed, setStreamed] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)
  const running = useRef<{ conversationId: string; requestId: string; abort: AbortController } | null>(null)
  const stopTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    let mounted = true
    Promise.all([providers(), conversations()]).then(([available, previous]) => {
      if (!mounted) return
      setCatalog(available)
      setHistory(previous)
      setModel(available.find((item) => item.id === 'gemini')?.defaultModel ?? '')
      setLoading(false)
    }).catch((cause: unknown) => {
      if (mounted) { setError(errorText(cause)); setLoading(false) }
    })
    return () => { mounted = false; const current = running.current
      if (current) { void stopMessage(current.conversationId, current.requestId).catch(() => undefined); current.abort.abort() }
    }
  }, [])

  function connect() {
    if (!key.trim()) { setError('Enter a Gemini API key to start chatting.'); return }
    if (!catalog.some((item) => item.id === provider && item.models.some((entry) => entry.id === model))) {
      setError('Choose an available Gemini model.'); return
    }
    setKey(key.trim())
    setConnected(true)
    setError('')
  }

  function changeKey() { setConnected(false); setError('') }
  function removeKey() { setKey(''); setConnected(false); setError(''); if (running.current) void stop() }

  function newChat() {
    if (sending.current) return
    setSelectedId(null)
    setMessages([])
    setStreamed('')
    setInput('')
    setError('')
  }

  async function switchChat(id: string) {
    if (sending.current) return
    setError('')
    try {
      const conversation = await openConversation(id)
      setSelectedId(conversation.id)
      setMessages(conversation.messages)
      setStreamed('')
    } catch (cause) { setError(errorText(cause)) }
  }

  async function removeChat(id: string) {
    if (sending.current) return
    try {
      await deleteConversation(id)
      setHistory(await conversations())
      if (selectedId === id) newChat()
    } catch (cause) { setError(errorText(cause)) }
  }

  async function submit() {
    if (sending.current) return
    const content = input.trim()
    if (!content) { setError('Write a message first.'); return }
    if (content.length > 8000) { setError('Messages can be at most 8,000 characters.'); return }
    if (!key.trim()) { setError('Enter a Gemini API key.'); setConnected(false); return }

    sending.current = true
    setBusy(true)
    setError('')
    setInput('')
    setStreamed('')
    setMessages((existing) => [...existing, {
      id: crypto.randomUUID(), role: 'user', content, status: 'complete', createdAt: new Date().toISOString(),
    }])
    let conversationId = selectedId
    const requestId = crypto.randomUUID()
    let streamError = ''
    try {
      if (!conversationId) {
        const created = await createConversation()
        conversationId = created.id
        setSelectedId(created.id)
        setHistory(await conversations())
      }
      const abort = new AbortController()
      running.current = { conversationId, requestId, abort }
      await sendMessage({ conversationId, requestId, content, provider, model, apiKey: key,
        signal: abort.signal, onEvent(event) {
          if (event.kind === 'delta') setStreamed((previous) => previous + event.text)
          if (event.kind === 'error') streamError = event.message
        },
      })
      if (streamError) setError(streamError)
      const loaded = await openConversation(conversationId)
      setMessages(loaded.messages)
      setStreamed('')
      setHistory(await conversations())
    } catch (cause) {
      setError(errorText(cause).includes('aborted') || (cause instanceof DOMException && cause.name === 'AbortError')
        ? 'Generation stopped.' : errorText(cause))
      if (conversationId) {
        try {
          const loaded = await openConversation(conversationId)
          setMessages(loaded.messages)
          setStreamed('')
          setHistory(await conversations())
        } catch { /* Keep the visible message and show the original error. */ }
      } else {
        setInput(content)
      }
    } finally {
      if (stopTimeout.current) clearTimeout(stopTimeout.current)
      stopTimeout.current = null
      running.current = null
      sending.current = false
      setBusy(false)
    }
  }

  async function stop() {
    const current = running.current
    if (!current) return
    try {
      await stopMessage(current.conversationId, current.requestId)
      if (running.current === current) {
        stopTimeout.current = setTimeout(() => current.abort.abort(), 6000)
      }
    } catch (cause) {
      setError(errorText(cause))
      current.abort.abort()
    }
  }

  return {
    catalog, history, loading, connected, key, setKey, provider, setProvider, model, setModel,
    selectedId, messages, input, setInput, streamed, busy, error,
    connect, changeKey, removeKey, newChat, switchChat, removeChat, submit, stop,
  }
}
