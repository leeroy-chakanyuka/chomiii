import { useEffect, useState } from 'react'
import { fetchHealth, type HealthState } from './health'

export default function App() {
  const [state, setState] = useState<HealthState>({ status: 'loading' })

  useEffect(() => {
    let current = true
    fetchHealth().then((settled) => {
      if (current) {
        setState(settled)
      }
    })
    return () => {
      current = false
    }
  }, [])

  return (
    <main className="shell">
      <h1 className="wordmark">
        chom<span className="threes">iii</span>
      </h1>
      <p className="status" data-testid="status" data-state={state.status}>
        {state.status === 'loading' ? 'checking…' : state.status}
      </p>
    </main>
  )
}
