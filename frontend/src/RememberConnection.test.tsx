import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

const savedCredentials = vi.hoisted(() => ({
  rememberKey: vi.fn(),
  loadRememberedKey: vi.fn(async () => null),
  forgetRememberedKey: vi.fn(async () => undefined),
}))
vi.mock('./rememberKey', () => savedCredentials)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  savedCredentials.rememberKey.mockReset()
})

it('does not race duplicate connection submissions while a key is being remembered', async () => {
  let finishSaving: (() => void) | undefined
  savedCredentials.rememberKey.mockReturnValue(new Promise<void>((resolve) => {
    finishSaving = resolve
  }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.endsWith('/health')) return new Response(JSON.stringify({ status: 'UP', service: 'chomiii' }))
    if (path.endsWith('/chat/providers')) return new Response(JSON.stringify([{
      id: 'gemini', name: 'Gemini', defaultModel: 'gemini-3.5-flash-lite',
      models: [{ id: 'gemini-3.5-flash-lite', name: 'Gemini Flash-Lite' }],
    }]))
    if (path.endsWith('/conversations')) return new Response('[]')
    throw new Error(`Unexpected request ${path}`)
  }))

  render(<App />)
  fireEvent.change(await screen.findByLabelText('Gemini API key'), { target: { value: 'stable-test-key' } })
  fireEvent.click(screen.getByRole('checkbox', { name: /Remember this key on this device/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Start chatting' }))

  const connecting = screen.getByRole('button', { name: 'Connecting…' })
  expect(connecting).toBeDisabled()
  expect(screen.getByLabelText('Gemini API key')).toBeDisabled()
  expect(screen.getByLabelText('Model')).toBeDisabled()
  expect(screen.getByRole('checkbox', { name: /Remember this key on this device/ })).toBeDisabled()

  fireEvent.submit(connecting.closest('form')!)
  expect(savedCredentials.rememberKey).toHaveBeenCalledTimes(1)
  expect(savedCredentials.rememberKey).toHaveBeenCalledWith({
    apiKey: 'stable-test-key', model: 'gemini-3.5-flash-lite',
  })

  finishSaving?.()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Remove key' })).toBeVisible())
  expect(savedCredentials.rememberKey).toHaveBeenCalledTimes(1)
})
