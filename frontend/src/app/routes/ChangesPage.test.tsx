// src/app/routes/ChangesPage.test.tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChangesPage } from './ChangesPage'
import { useChangesStore } from '@/features/changes/store/useChangesStore'
import type { Change, PageResponse } from '@/features/changes/types'

// Stub heavy sub-components to isolate stat card rendering
vi.mock('@/features/changes/components/ChangeList', () => ({
  ChangeList: () => <div data-testid="change-list-stub" />,
}))
vi.mock('@/features/changes/components/ChangeTimeline', () => ({
  ChangeTimeline: () => <div data-testid="change-timeline-stub" />,
}))
vi.mock('@/shared/hooks/usePolling', () => ({
  usePolling: vi.fn(),
}))
vi.mock('@/features/changes/services/changeService', () => ({
  default: { list: vi.fn(), create: vi.fn(), getEvents: vi.fn() },
}))

const fakeChange = (status: Change['status']): Change => ({
  changeId: `id-${status}`,
  title: `Change ${status}`,
  componentId: 'svc',
  status,
  correlationId: 'corr',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
})

function makePageWithSummary(
  pageContent: Change[],
  totalElements: number,
  statusSummary: PageResponse<Change>['statusSummary'],
): PageResponse<Change> {
  return {
    content: pageContent,
    totalElements,
    totalPages: Math.ceil(totalElements / 20),
    number: 0,
    size: 20,
    first: true,
    last: true,
    statusSummary,
  }
}

describe('ChangesPage – stat cards', () => {
  beforeEach(() => {
    useChangesStore.setState({
      page: null,
      currentPage: 0,
      selectedChangeId: null,
    })
  })

  it('does not render stat cards when page is null', () => {
    render(<ChangesPage />)
    expect(screen.queryByText(/total changes/i)).not.toBeInTheDocument()
  })

  it('shows Total Changes using totalElements (correct even before fix)', () => {
    const page = makePageWithSummary([fakeChange('PREPARED')], 100, {
      PREPARED: 60,
      COMPLETED: 30,
      FAILED: 10,
      DRAFT: 0,
      CANCELLED: 0,
    })
    useChangesStore.setState({ page })
    render(<ChangesPage />)
    expect(screen.getByText('100')).toBeInTheDocument()
  })

  it('shows Prepared count from statusSummary (global total, not current page)', () => {
    // Page has 1 PREPARED item but statusSummary reports 30 globally
    const page = makePageWithSummary([fakeChange('PREPARED')], 100, {
      PREPARED: 30,
      COMPLETED: 20,
      FAILED: 5,
      DRAFT: 0,
      CANCELLED: 0,
    })
    useChangesStore.setState({ page })
    render(<ChangesPage />)

    const preparedCard = screen.getByText(/prepared/i).closest('div')
    expect(preparedCard).not.toBeNull()
    expect(preparedCard).toHaveTextContent('30')
  })

  it('shows Completed count from statusSummary (global total, not current page)', () => {
    // Page has 0 COMPLETED items but statusSummary reports 20 globally
    const page = makePageWithSummary([fakeChange('PREPARED')], 100, {
      PREPARED: 30,
      COMPLETED: 20,
      FAILED: 5,
      DRAFT: 0,
      CANCELLED: 0,
    })
    useChangesStore.setState({ page })
    render(<ChangesPage />)

    const completedCard = screen.getByText(/completed/i).closest('div')
    expect(completedCard).not.toBeNull()
    expect(completedCard).toHaveTextContent('20')
  })

  it('shows Failed count from statusSummary (global total, not current page)', () => {
    // Page has 0 FAILED items but statusSummary reports 5 globally
    const page = makePageWithSummary([fakeChange('PREPARED')], 100, {
      PREPARED: 30,
      COMPLETED: 20,
      FAILED: 5,
      DRAFT: 0,
      CANCELLED: 0,
    })
    useChangesStore.setState({ page })
    render(<ChangesPage />)

    const failedCard = screen.getByText(/failed/i).closest('div')
    expect(failedCard).not.toBeNull()
    expect(failedCard).toHaveTextContent('5')
  })

  it('shows zero for status counts when statusSummary has all zeros', () => {
    const page = makePageWithSummary([], 0, {
      PREPARED: 0,
      COMPLETED: 0,
      FAILED: 0,
      DRAFT: 0,
      CANCELLED: 0,
    })
    useChangesStore.setState({ page })
    render(<ChangesPage />)

    const cards = screen.getAllByText('0')
    // Total, Prepared, Completed, Failed — all 4 cards show 0
    expect(cards.length).toBeGreaterThanOrEqual(4)
  })

  it('falls back to 0 for status counts when statusSummary is absent', () => {
    // Backward-compatibility: API response before statusSummary was added
    const page: PageResponse<Change> = {
      content: [fakeChange('PREPARED')],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
      first: true,
      last: true,
      // statusSummary intentionally absent
    }
    useChangesStore.setState({ page })
    render(<ChangesPage />)

    const preparedCard = screen.getByText(/prepared/i).closest('div')
    expect(preparedCard).toHaveTextContent('0')

    const completedCard = screen.getByText(/completed/i).closest('div')
    expect(completedCard).toHaveTextContent('0')

    const failedCard = screen.getByText(/failed/i).closest('div')
    expect(failedCard).toHaveTextContent('0')
  })
})
