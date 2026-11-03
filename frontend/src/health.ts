/**
 * Health-check application logic for the GET /api/health contract:
 * {status: 'UP', service: 'chomiii'} with HTTP 200. Anything else — wrong
 * status code, unparseable body, or wrong payload shape — is unavailable.
 */
export type HealthState =
  | { status: 'loading' }
  | { status: 'up' }
  | { status: 'unavailable' }

export type SettledHealthState = Exclude<HealthState, { status: 'loading' }>

interface HealthResponse {
  ok: boolean
  status: number
  json: () => Promise<unknown>
}

/**
 * Backend base URL. Overridable at build time with VITE_API_BASE_URL, e.g.
 * `VITE_API_BASE_URL=http://localhost:8080/api` for a cross-origin deployment;
 * the default `/api` relies on the Vite development proxy to the backend.
 */
export function healthUrl(): string {
  const base = import.meta.env.VITE_API_BASE_URL
  if (typeof base === 'string' && base.length > 0) {
    return `${base.replace(/\/$/, '')}/health`
  }
  return '/api/health'
}

function matchesContract(payload: unknown): payload is { status: 'UP'; service: 'chomiii' } {
  if (typeof payload !== 'object' || payload === null) {
    return false
  }
  const record = payload as Record<string, unknown>
  return record.status === 'UP' && record.service === 'chomiii'
}

export async function fetchHealth(): Promise<SettledHealthState> {
  try {
    const response: HealthResponse = await fetch(healthUrl())
    if (!response.ok) {
      return { status: 'unavailable' }
    }
    const payload = await response.json()
    if (!matchesContract(payload)) {
      return { status: 'unavailable' }
    }
    return { status: 'up' }
  } catch {
    // Covers network failure and malformed JSON alike; the shell must not crash.
    return { status: 'unavailable' }
  }
}
