import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { healthUrl } from './health'

const jsonResponse = (body: unknown, status: number): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })

const nonOkResponse = (status: number): Response =>
  new Response('{"status":"DOWN"}', {
    status,
    headers: { 'content-type': 'application/json' },
  })

function stubFetchTo(handler: (...args: unknown[]) => Promise<Response>): void {
  vi.stubGlobal('fetch', vi.fn(handler))
}

function expectsWordmarkStillRendered(): void {
  expect(screen.getByRole('heading').textContent).toBe('chomiii')
}

describe('the chomiii shell', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('starts checking the backend before the health check resolves', () => {
    stubFetchTo(() => new Promise<Response>(() => undefined))

    render(<App />)

    const status = screen.getByTestId('status')
    expect(status).toHaveAttribute('data-state', 'loading')
    expect(status.textContent).toBe('checking…')
    expectsWordmarkStillRendered()
  })

  it('shows the service is up when the endpoint answers the documented contract', async () => {
    stubFetchTo(() => Promise.resolve(jsonResponse(
      { status: 'UP', service: 'chomiii' },
      200,
    )))

    render(<App />)

    await waitFor(() => expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'up'))
    expect(screen.getByTestId('status').textContent).toBe('up')
    expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'up')
    expect(vi.mocked(fetch)).toHaveBeenCalledWith(healthUrl())
  })

  it('shows unavailable without crashing when the endpoint answers non-OK', async () => {
    stubFetchTo(() => Promise.resolve(nonOkResponse(503)))

    render(<App />)

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'unavailable'),
    )
    expect(screen.getByTestId('status').textContent).toBe('unavailable')
    expectsWordmarkStillRendered()
  })

  it('shows unavailable without crashing when the endpoint answers malformed JSON', async () => {
    stubFetchTo(() =>
      Promise.resolve(new Response('not-json-at-all', {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })),
    )

    render(<App />)

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'unavailable'),
    )
    expectsWordmarkStillRendered()
  })

  it('shows unavailable without crashing when the payload does not match the contract', async () => {
    stubFetchTo(() => Promise.resolve(jsonResponse({ status: 'UP' }, 200)))

    render(<App />)

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'unavailable'),
    )
    expectsWordmarkStillRendered()
  })

  it('shows unavailable without crashing when the request never reaches the backend', async () => {
    stubFetchTo(() => Promise.reject(new TypeError('fetch failed')))

    render(<App />)

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'unavailable'),
    )
    expectsWordmarkStillRendered()
  })

  it('requests the documented configurable backend URL', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:9000')
    stubFetchTo(() => Promise.resolve(jsonResponse(
      { status: 'UP', service: 'chomiii' },
      200,
    )))

    render(<App />)

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveAttribute('data-state', 'up'),
    )
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('http://localhost:9000/health')
  })
})
