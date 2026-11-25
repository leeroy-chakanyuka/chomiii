import { useEffect, useRef, useState } from 'react'
import {
  conversations, conversationToolTraces, conversationApprovals, conversationPlans, runPlan, cancelPlan, decideApproval, createConversation, deleteConversation, openConversation, providers,
  sendMessage, stopMessage,
  type Approval, type ChatMessage, type ConversationSummary, type Provider, type ToolTrace, type SavedPlan,
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
  const [useMemory, setUseMemory] = useState(() => {
    try { return window.localStorage.getItem('chomiii:use-memory') !== 'false' } catch { return true }
  })
  const [key, setKey] = useState('')
  const [remember, setRemember] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [provider, setProvider] = useState('gemini')
  const [model, setModel] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streamed, setStreamed] = useState('')
  const [toolActivity, setToolActivity] = useState<ToolTrace[]>([])
  const [toolActivityError, setToolActivityError] = useState(false)
  const [approvals, setApprovals] = useState<Approval[]>([])
  const [approvalsError, setApprovalsError] = useState(false)
  const [approvalBusy, setApprovalBusy] = useState<string | null>(null)
  const [plans, setPlans] = useState<SavedPlan[]>([])
  const [plansError, setPlansError] = useState(false)
  const [planBusy, setPlanBusy] = useState<string | null>(null)
  const [revisingPlan, setRevisingPlan] = useState<SavedPlan | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)
  const running = useRef<{ conversationId: string; requestId: string; abort: AbortController } | null>(null)
  const stopTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)
  const startedAt = useRef<number | null>(null)
  const firstVisibleMeasured = useRef(false)
  const hadSavedKey = useRef(false)
  const connectionInFlight = useRef(false)
  const selectedIdRef = useRef<string | null>(null)
  const traceRequest = useRef(0)
  const traceActivityVersion = useRef(0)
  const approvalRequest = useRef(0)
  const approvalRevision = useRef(new Map<string, number>())
  const approvalMutation = useRef(new Map<string, number>())
  const approvalBusyToken = useRef(0)
  const approvalScope = useRef(new Map<string, string>())
  const planRequest = useRef(0)
  const planBusyToken = useRef(0)

  async function refreshPlans(id: string) {
    const request = ++planRequest.current
    try {
      const items = await conversationPlans(id)
      if (request !== planRequest.current || selectedIdRef.current !== id) return
      setPlans(items); setPlansError(false)
    } catch {
      if (request === planRequest.current && selectedIdRef.current === id) setPlansError(true)
    }
  }

  function updateApproval(conversationId: string, value: Approval) {
    approvalRevision.current.set(value.id, (approvalRevision.current.get(value.id) ?? 0) + 1)
    approvalMutation.current.set(value.id, (approvalMutation.current.get(value.id) ?? 0) + 1)
    approvalScope.current.set(value.id, conversationId)
    setApprovals((previous) => [...previous.filter((item) => item.id !== value.id), value])
  }

  function selectConversation(id: string | null) {
    selectedIdRef.current = id
    setSelectedId(id)
  }

  useEffect(() => {
    try { window.localStorage.setItem('chomiii:use-memory', String(useMemory)) } catch { /* storage may be unavailable */ }
  }, [useMemory])

  useEffect(() => {
    if (!streamed || startedAt.current === null || firstVisibleMeasured.current
      || typeof requestAnimationFrame !== 'function') return
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
    ++traceRequest.current
    ++traceActivityVersion.current
    ++approvalRequest.current
    ++planRequest.current
    ++planBusyToken.current
    setPlans([]); setPlansError(false); setPlanBusy(null)
    setRevisingPlan(null)
    ++approvalBusyToken.current
    setApprovals([]); setApprovalsError(false); setApprovalBusy(null)
    selectConversation(null)
    setMessages([])
    setToolActivity([])
    setToolActivityError(false)
    setStreamed('')
    setInput('')
    setError('')
  }

  async function switchChat(id: string) {
    if (sending.current) return
    setError('')
    const request = ++traceRequest.current
    const approvalsRequest = ++approvalRequest.current
    const plansRequest = ++planRequest.current
    ++approvalBusyToken.current
    const activityVersion = traceActivityVersion.current
    const traceResult: Promise<{ traces: ToolTrace[]; failed: boolean }> = conversationToolTraces(id).then(
      (traces) => ({ traces, failed: false }),
      () => ({ traces: [], failed: true }),
    )
    setToolActivity([])
    setToolActivityError(false)
    setApprovals([]); setApprovalsError(false); setApprovalBusy(null)
    setPlans([]); setPlansError(false); setPlanBusy(null)
    setRevisingPlan(null)
    const plansResult = conversationPlans(id).then((items) => ({ items, failed: false as const }),
      () => ({ items: [] as SavedPlan[], failed: true as const }))
    const approvalVersions = new Map(approvalRevision.current)
    const approvalsResult = conversationApprovals(id).then(
      (items) => ({ items, failed: false as const }),
      () => ({ items: [] as Approval[], failed: true as const }),
    )
    try {
      const conversation = await openConversation(id)
      if (request !== traceRequest.current) return
      selectConversation(conversation.id)
      setMessages(conversation.messages)
      setStreamed('')
      void plansResult.then(({ items, failed }) => {
        if (plansRequest !== planRequest.current || selectedIdRef.current !== conversation.id) return
        setPlans(items); setPlansError(failed)
      })
      void approvalsResult.then(({ items, failed }) => {
        if (approvalsRequest !== approvalRequest.current || selectedIdRef.current !== conversation.id) return
        if (failed) { setApprovalsError(true); return }
        setApprovals((previous) => {
          const newer = previous.filter((item) => approvalScope.current.get(item.id) === conversation.id
            && (approvalRevision.current.get(item.id) ?? 0)
            !== (approvalVersions.get(item.id) ?? 0))
          for (const item of items) approvalScope.current.set(item.id, conversation.id)
          return [...items.filter((item) => !newer.some((current) => current.id === item.id)), ...newer]
        })
        setApprovalsError(false)
      })
      void traceResult.then((result) => {
        if (request !== traceRequest.current || traceActivityVersion.current !== activityVersion
          || selectedIdRef.current !== conversation.id) return
        setToolActivity(result.traces)
        setToolActivityError(result.failed)
      })
    } catch (cause) { if (request === traceRequest.current) setError(errorText(cause)) }
  }

  async function retryApprovals() {
    const id = selectedIdRef.current
    if (!id) return
    const request = ++approvalRequest.current
    const versions = new Map(approvalRevision.current)
    try {
      const items = await conversationApprovals(id)
      if (request !== approvalRequest.current || selectedIdRef.current !== id) return
      setApprovals((previous) => {
        const newer = previous.filter((item) => approvalScope.current.get(item.id) === id
          && (approvalRevision.current.get(item.id) ?? 0) !== (versions.get(item.id) ?? 0))
        for (const item of items) approvalScope.current.set(item.id, id)
        return [...items.filter((item) => !newer.some((current) => current.id === item.id)), ...newer]
      })
      setApprovalsError(false)
    } catch { if (request === approvalRequest.current && selectedIdRef.current === id) setApprovalsError(true) }
  }

  async function actOnPlan(id: string, planId: string, action: 'run' | 'cancel') {
    if (selectedIdRef.current !== id || planBusy) return
    const token = ++planBusyToken.current
    setPlanBusy(planId)
    try {
      const result = action === 'run' ? await runPlan(id, planId) : await cancelPlan(id, planId)
      if (selectedIdRef.current !== id || token !== planBusyToken.current) return
      setPlans((previous) => previous.map((plan) => plan.id === planId ? result : plan))
      void retryApprovals()
    } catch (cause) {
      if (selectedIdRef.current === id && token === planBusyToken.current) setError(errorText(cause))
    } finally {
      if (token === planBusyToken.current) setPlanBusy(null)
    }
  }

  async function decide(conversationId: string, item: Approval, decision: 'approve' | 'reject') {
    if (selectedIdRef.current !== conversationId || item.status !== 'pending' || approvalBusy) return
    const mutation = (approvalMutation.current.get(item.id) ?? 0) + 1
    approvalMutation.current.set(item.id, mutation)
    const request = approvalRequest.current
    const busyToken = ++approvalBusyToken.current
    setApprovalBusy(item.id)
    try {
      const result = await decideApproval(conversationId, item.id, decision)
      if (selectedIdRef.current !== conversationId || request !== approvalRequest.current
        || approvalMutation.current.get(item.id) !== mutation) return
      setApprovalBusy(null)
      updateApproval(conversationId, result)
      void retryApprovals()
      void refreshPlans(conversationId)
    } catch (cause) {
      if (selectedIdRef.current === conversationId && request === approvalRequest.current
        && approvalMutation.current.get(item.id) === mutation) {
        setError(errorText(cause))
        void retryApprovals()
      }
    } finally {
      if (approvalBusyToken.current === busyToken) setApprovalBusy(null)
    }
  }

  async function retryToolActivity() {
    const id = selectedIdRef.current
    if (!id) return
    const request = ++traceRequest.current
    const activityVersion = ++traceActivityVersion.current
    try {
      const traces = await conversationToolTraces(id)
      if (request !== traceRequest.current || selectedIdRef.current !== id
        || traceActivityVersion.current !== activityVersion) return
      setToolActivity(traces)
      setToolActivityError(false)
    } catch {
      if (request === traceRequest.current && selectedIdRef.current === id
        && traceActivityVersion.current === activityVersion) setToolActivityError(true)
    }
  }

  async function removeChat(id: string) {
    if (sending.current) return
    try {
      await deleteConversation(id)
      setHistory(await conversations())
      if (selectedId === id) newChat()
    } catch (cause) { setError(errorText(cause)) }
  }

  async function submit(plan = false) {
    if (sending.current) return
    const content = input.trim()
    if (!content) { setError('Write a message first.'); return }
    if (content.length > 8000) { setError('Messages can be at most 8,000 characters.'); return }
    if (!key.trim()) { setError('Enter a Gemini API key.'); setConnected(false); return }

    sending.current = true
    ++approvalRequest.current
    ++approvalBusyToken.current
    setApprovalBusy(null)
    ++traceRequest.current
    ++traceActivityVersion.current
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
        selectConversation(created.id)
        ++traceRequest.current
        ++traceActivityVersion.current
        setHistory((previous) => [{ id: created.id, title: created.title,
          createdAt: created.createdAt, updatedAt: created.updatedAt }, ...previous])
      }
      const abort = new AbortController()
      const streamConversationId = conversationId
      running.current = { conversationId, requestId, abort }
      await sendMessage({ conversationId: streamConversationId, requestId, content, provider, model, apiKey: key,
        signal: abort.signal, useMemory, plan: plan || revisingPlan !== null,
        ...(revisingPlan ? { revisePlan: { revisePlanId: revisingPlan.id, reviseVersion: revisingPlan.version } } : {}), onEvent(event) {
          if (event.kind === 'delta') setStreamed((previous) => previous + event.text)
          if (event.kind === 'tool') {
            ++traceActivityVersion.current
            setToolActivity((previous) => {
              const existing = previous.find((trace) => trace.invocationId === event.trace.invocationId)
              if (existing && existing.status !== 'requested' && existing.status !== 'running'
                && existing.status !== 'awaiting_approval'
                && (event.trace.status === 'requested' || event.trace.status === 'running'
                  || event.trace.status === 'awaiting_approval')) return previous
              if (existing?.status === 'awaiting_approval'
                && (event.trace.status === 'requested' || event.trace.status === 'running')) return previous
              const remaining = previous.filter((trace) => trace.invocationId !== event.trace.invocationId)
              return [...remaining, event.trace]
            })
          }
          if (event.kind === 'approval' && selectedIdRef.current === streamConversationId) updateApproval(streamConversationId, event.approval)
          if (event.kind === 'error') streamError = event.message
          if (event.kind === 'complete') setRevisingPlan(null)
          if ((event.kind === 'complete' || event.kind === 'error') && startedAt.current !== null
            && typeof performance.measure === 'function') {
            performance.measure('chomiii:response-complete', { start: startedAt.current, end: performance.now() })
          }
        },
      })
      if (streamError) setError(streamError)
      // Both refreshes are independent. Do not serialize network round trips after streaming.
      const [loaded, refreshed] = await Promise.all([openConversation(conversationId), conversations()])
      setMessages(loaded.messages)
      setStreamed('')
      setHistory(refreshed)
      if (selectedIdRef.current === conversationId) {
        await Promise.all([refreshPlans(conversationId), retryApprovals(), retryToolActivity()])
      }
    } catch (cause) {
      setError(errorText(cause).includes('aborted') || (cause instanceof DOMException && cause.name === 'AbortError')
        ? 'Generation stopped.' : errorText(cause))
      if (conversationId) {
        try {
          const loaded = await openConversation(conversationId)
          setMessages(loaded.messages)
          setStreamed('')
          setHistory(await conversations())
          if (selectedIdRef.current === conversationId) {
            await Promise.all([refreshPlans(conversationId), retryApprovals(), retryToolActivity()])
          }
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

  function revisePlan(plan: SavedPlan | null) { setRevisingPlan(plan) }

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
    selectedId, messages, input, setInput, streamed, toolActivity, toolActivityError, approvals, approvalsError, approvalBusy, plans, plansError, planBusy, revisingPlan, busy, error, useMemory, setUseMemory,
    connect, changeKey, removeKey, newChat, switchChat, retryToolActivity, retryApprovals,
    approve: (id: string, item: Approval) => decide(id, item, 'approve'),
    reject: (id: string, item: Approval) => decide(id, item, 'reject'), runPlan: (id: string, planId: string) => actOnPlan(id, planId, 'run'),
    cancelPlan: (id: string, planId: string) => actOnPlan(id, planId, 'cancel'), retryPlans: () => selectedIdRef.current && refreshPlans(selectedIdRef.current), revisePlan, removeChat, submit, stop,
  }
}
