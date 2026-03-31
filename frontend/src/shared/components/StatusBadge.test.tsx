import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { StatusBadge } from './StatusBadge'
import type { ChangeStatus } from '@/features/changes/types'

const statuses: { status: ChangeStatus; label: string }[] = [
  { status: 'DRAFT', label: 'Draft' },
  { status: 'PREPARED', label: 'Prepared' },
  { status: 'COMPLETED', label: 'Completed' },
  { status: 'FAILED', label: 'Failed' },
  { status: 'CANCELLED', label: 'Cancelled' },
]

describe('StatusBadge', () => {
  it.each(statuses)(
    'renders "$label" for status $status',
    ({ status, label }) => {
      render(<StatusBadge status={status} />)
      expect(screen.getByText(label)).toBeInTheDocument()
    },
  )

  it('renders with sm size classes', () => {
    const { container } = render(<StatusBadge status="PREPARED" size="sm" />)
    const badge = container.firstElementChild!
    expect(badge.className).toContain('text-xs')
  })

  it('renders with md size classes by default', () => {
    const { container } = render(<StatusBadge status="COMPLETED" />)
    const badge = container.firstElementChild!
    expect(badge.className).toContain('text-sm')
  })
})
