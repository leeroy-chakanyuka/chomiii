import { useEffect, useState, type ReactNode } from 'react'

export type ApprovalCardStatus = 'pending' | 'submitting' | 'approved' | 'completed' | 'rejected' | 'cancelled' | 'failed' | 'expired'

export type ApprovalCardProps = {
  preview: string
  expiresAt: string
  status: ApprovalCardStatus
  confirmedResult?: string
  error?: string
  onApprove: () => void | Promise<void>
  onReject: () => void | Promise<void>
}

const statusLabels: Record<ApprovalCardStatus, string> = {
  pending: 'Waiting for your decision',
  submitting: 'Sending your decision',
  approved: 'Approved — not yet completed',
  completed: 'Completed',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
  failed: 'Action failed',
  expired: 'Approval expired',
}

export function ApprovalCard({ preview, expiresAt, status, confirmedResult, error, onApprove, onReject }: ApprovalCardProps) {
  const [clock, setClock] = useState(() => Date.now())
  const parsedExpiry = new Date(expiresAt).getTime()
  useEffect(() => {
    if (status !== 'pending' || Number.isNaN(parsedExpiry) || parsedExpiry <= Date.now()) return
    let timer: ReturnType<typeof setTimeout>
    const schedule = () => {
      const remaining = parsedExpiry - Date.now()
      if (remaining <= 0) { setClock(Date.now()); return }
      timer = setTimeout(schedule, Math.min(remaining, 2_147_000_000))
    }
    timer = setTimeout(schedule, Math.min(parsedExpiry - Date.now(), 2_147_000_000))
    return () => clearTimeout(timer)
  }, [status, parsedExpiry])
  const expiredPending = status === 'pending' && (Number.isNaN(parsedExpiry) || parsedExpiry <= clock)
  const pending = status === 'pending' && !expiredPending
  const busy = status === 'submitting'
  const expiryDate = new Date(expiresAt)
  const expiryLabel = Number.isNaN(expiryDate.getTime())
    ? 'Unknown date'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(expiryDate)
  let detail: ReactNode = null
  if (status === 'approved') detail = <p>The action has been approved. It has not been completed yet.</p>
  if (status === 'completed' && confirmedResult) detail = <p>{confirmedResult}</p>
  if (status === 'failed') detail = <p role="alert">{error || 'The action could not be completed.'}</p>

  return <section aria-label="Action approval" className="approval-card">
    <h2>Review this action</h2>
    <p className="approval-preview">{preview}</p>
    <p>Approval expires: <time dateTime={expiresAt}>{expiryLabel}</time></p>
    <p role="status" aria-live="polite">{expiredPending ? statusLabels.expired : statusLabels[status]}</p>
    {detail}
    {pending && <div>
      <button type="button" onClick={() => void onApprove()}>Approve action</button>
      <button type="button" onClick={() => void onReject()}>Reject action</button>
    </div>}
    {busy && <div>
      <button type="button" disabled>Approve action</button>
      <button type="button" disabled>Reject action</button>
    </div>}
  </section>
}
