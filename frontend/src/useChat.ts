import { useEffect, useRef, useState } from 'react'
import {
  conversations, createConversation, deleteConversation, openConversation, providers,
  sendMessage, stopMessage,
  type ChatMessage, type ConversationSummary, type Provider,
} from './chatApi'
import { forgetRememberedKey, loadRememberedKey, rememberKey } from './rememberKey'

function errorText(cause: unknown): string {
  return cause instanceof Error ? cause.message : 'Something went wrong. Try again.'
}

export function useChat() {
  const [catalog, setCatalog] = useState<Provider[]>([])
  const [history, setHistory] = useState<ConversationSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [connected, setConnected] = useState(false)
  const [key, setKey] = useState('')
  const [remember, setRemember] = useState(false)
  const [connecting, setConnecting] = useState(false)
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
  const startedAt = useRef<number | null>(null)
  const firstVisibleMeasured = useRef(false)
  const hadSavedKey = useRef(false)
  const connectionInFlight = useRef(false)

  useEffect(() => {
    if (!streamed || startedAt.current === null || firstVisibleMeasured.current) return
    firstVisibleMeasured.current = true
    const start = startedAt.current
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (startedAt.current === start && typeof performance.measure === 'function') {
        performance.measure('chomiii:first-visible-token', { start, end: performance.now() })
      }
    }))
  }, [streamed])

  useEffect(() => {
    let mounted = true
    Promise.all([providers(), conversations(), loadRememberedKey()]).then(([available, previous, saved]) => {
      if (!mounted) return
      setCatalog(available)
      setHistory(previous)
      if (saved) {
        hadSavedKey.current = true
        setKey(saved.apiKey)
        setRemember(true)
        setModel(saved.model)
        if (available.some((item) => item.id === 'gemini'
          && item.models.some((entry) => entry.id === saved.model))) setConnected(true)
        else setError('Your saved model is no longer offered. Choose a model before chatting.')
      } else {
        setModel(available.find((item) => item.id === 'gemini')?.defaultModel ?? '')
      }
      setLoading(false)
    }).catch((cause: unknown) => {
      if (mounted) { setError(errorText(cause)); setLoading(false) }
    })
    return () => { mounted = false; const current = running.current
      if (current) { void stopMessage(current.conversationId, current.requestId).catch(() => undefined); current.abort.abort() }
    }
  }, [])

  async function connect() {
    if (connectionInFlight.current) return
    const selectedKey = key.trim()
    if (!selectedKey) { setError('Enter a Gemini API key to start chatting.'); return }
    if (!catalog.some((item) => item.id === provider && item.models.some((entry) => entry.id === model))) {
      setError('Choose an available Gemini model.'); return
    }
    connectionInFlight.current = true
    setConnecting(true)
    try {
      if (remember) {
        await rememberKey({ apiKey: selectedKey, model })
        hadSavedKey.current = true
      } else if (hadSavedKey.current) {
        await forgetRememberedKey()
        hadSavedKey.current = false
      }
      setKey(selectedKey)
      setConnected(true)
      setError('')
    } catch (cause) {
      setError(errorText(cause))
    } finally {
      connectionInFlight.current = false
      setConnecting(false)
    }
  }

  function changeKey() { setConnected(false); setError('') }
  async function removeKey() {
    if (running.current) void stop()
    setKey('')
    setConnected(false)
    setRemember(false)
    try {
      await forgetRememberedKey()
      hadSavedKey.current = false
      setError('')
    } catch (cause) { setError(errorText(cause)) }
  }

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
    startedAt.current = performance.now()
    firstVisibleMeasured.current = false
    if (typeof performance.clearMeasures === 'function') {
      performance.clearMeasures('chomiii:first-visible-token')
      performance.clearMeasures('chomiii:response-complete')
    }
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
        setHistory((previous) => [{ id: created.id, title: created.title,
          createdAt: created.createdAt, updatedAt: created.updatedAt }, ...previous])
      }
      const abort = new AbortController()
      running.current = { conversationId, requestId, abort }
      await sendMessage({ conversationId, requestId, content, provider, model, apiKey: key,
        signal: abort.signal, onEvent(event) {
          if (event.kind === 'delta') setStreamed((previous) => previous + event.text)
          if (event.kind === 'error') streamError = event.message
          if ((event.kind === 'complete' || event.kind === 'error') && startedAt.current !== null
            && typeof performance.measure === 'function') {
            performance.measure('chomiii:response-complete', { start: startedAt.current, end: performance.now() })
          }
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
    catalog, history, loading, connected, connecting, key, setKey, remember, setRemember, provider, setProvider, model, setModel,
    selectedId, messages, input, setInput, streamed, busy, error,
    connect, changeKey, removeKey, newChat, switchChat, removeChat, submit, stop,
  }
}
