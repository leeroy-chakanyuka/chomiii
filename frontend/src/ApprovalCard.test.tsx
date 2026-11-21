import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ApprovalCard, type ApprovalCardProps } from './ApprovalCard'

afterEach(() => { cleanup(); vi.useRealTimers() })

const base: ApprovalCardProps = {
  preview: 'Send a message to Alex: "I will be 10 minutes late."',
  expiresAt: new Date(Date.now() + 300_000).toISOString(),
  status: 'pending',
  onApprove: vi.fn(),
  onReject: vi.fn(),
}

it('shows the exact preview as text and offers explicit approve and reject actions', () => {
  const onApprove = vi.fn()
  const onReject = vi.fn()
  render(<ApprovalCard {...base} preview={'<img src=x onerror="alert(1)">'} onApprove={onApprove} onReject={onReject} />)
  expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeVisible()
  expect(document.querySelector('img')).toBeNull()
  const expiry = screen.getByText('Approval expires:').querySelector('time')
  expect(expiry).toHaveAttribute('dateTime', base.expiresAt)
  expect(expiry).not.toHaveTextContent(base.expiresAt)
  expect(expiry?.textContent).not.toBe('Unknown date')
  fireEvent.click(screen.getByRole('button', { name: 'Approve action' }))
  fireEvent.click(screen.getByRole('button', { name: 'Reject action' }))
  expect(onApprove).toHaveBeenCalledOnce()
  expect(onReject).toHaveBeenCalledOnce()
})

it('disables both choices while submitting', () => {
  render(<ApprovalCard {...base} status="submitting" />)
  expect(screen.getByRole('button', { name: 'Approve action' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Reject action' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('Sending your decision')
})

it('distinguishes approval from actual completion and shows the confirmed result', () => {
  const { rerender } = render(<ApprovalCard {...base} status="approved" />)
  expect(screen.getByRole('status')).toHaveTextContent('not yet completed')
  expect(screen.queryByText('Completed')).not.toBeInTheDocument()
  rerender(<ApprovalCard {...base} status="completed" confirmedResult="Message sent to Alex." />)
  expect(screen.getByRole('status')).toHaveTextContent('Completed')
  expect(screen.getByText('Message sent to Alex.')).toBeVisible()
})

it.each([
  ['rejected', 'Rejected'],
  ['cancelled', 'Cancelled'],
  ['failed', 'Action failed'],
  ['expired', 'Approval expired'],
] as const)('clearly presents %s status', (status, label) => {
  render(<ApprovalCard {...base} status={status} error="Could not send." />)
  expect(screen.getByRole('status')).toHaveTextContent(label)
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  if (status === 'failed') expect(screen.getByRole('alert')).toHaveTextContent('Could not send.')
})

it('shows a safe fallback for an invalid expiry while preserving its original metadata', () => {
  render(<ApprovalCard {...base} expiresAt="not-a-date" />)
  const expiry = screen.getByText('Unknown date')
  expect(expiry.tagName).toBe('TIME')
  expect(expiry).toHaveAttribute('dateTime', 'not-a-date')
})

it('withdraws approve and reject when the pending approval expires without another render', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-17T09:59:00Z'))
  render(<ApprovalCard {...base} expiresAt="2026-11-17T10:00:00Z" />)
  expect(screen.getByRole('button', { name: 'Approve action' })).toBeEnabled()
  act(() => { vi.advanceTimersByTime(60_000) })
  expect(screen.queryByRole('button', { name: 'Approve action' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reject action' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('status').some((status) => status.textContent === 'Approval expired')).toBe(true)
})
