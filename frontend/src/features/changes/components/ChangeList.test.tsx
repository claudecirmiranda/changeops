// src/features/changes/components/ChangeList.test.tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChangeList } from './ChangeList'
import changeService from '../services/changeService'
import { useChangesStore } from '../store/useChangesStore'
import type { Change, PageResponse } from '../types'

vi.mock('../services/changeService')
vi.mock('@/shared/hooks/usePolling', () => ({
  usePolling: vi.fn(),
}))

const mockList = vi.mocked(changeService.list)

const fakeChange: Change = {
  changeId: 'aaaa-bbbb-cccc-dddd',
  title: 'Deploy v2',
  componentId: 'payment-service',
  status: 'PREPARED',
  correlationId: 'corr-1',
  createdAt: '2024-06-15T10:30:00Z',
  updatedAt: '2024-06-15T10:30:00Z',
}

const fakePage: PageResponse<Change> = {
  content: [fakeChange],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
  first: true,
  last: true,
}

describe('ChangeList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useChangesStore.setState({
      page: null,
      currentPage: 0,
      selectedChangeId: null,
      isPolling: false,
    })
  })

  it('renders a table with changes after loading', async () => {
    mockList.mockResolvedValueOnce(fakePage)
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText('Deploy v2')).toBeInTheDocument()
    })
    expect(screen.getByText('payment-service')).toBeInTheDocument()
    expect(screen.getByText(/aaaa-bbb/)).toBeInTheDocument()
  })

  it('shows empty state when there are no changes', async () => {
    mockList.mockResolvedValueOnce({ ...fakePage, content: [], totalElements: 0 })
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText(/no changes registered yet/i)).toBeInTheDocument()
    })
  })

  it('shows error banner when load fails', async () => {
    mockList.mockRejectedValueOnce({
      title: 'Error',
      detail: 'Server unavailable',
      status: 500,
      timestamp: new Date().toISOString(),
    })
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText(/server unavailable/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/retry/i)).toBeInTheDocument()
  })

  it('selects a change when row is clicked', async () => {
    mockList.mockResolvedValueOnce(fakePage)
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText('Deploy v2')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Deploy v2'))
    expect(useChangesStore.getState().selectedChangeId).toBe('aaaa-bbbb-cccc-dddd')
  })

  it('deselects when the same row is clicked again', async () => {
    mockList.mockResolvedValueOnce(fakePage)
    useChangesStore.setState({ selectedChangeId: 'aaaa-bbbb-cccc-dddd' })
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText('Deploy v2')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Deploy v2'))
    expect(useChangesStore.getState().selectedChangeId).toBeNull()
  })

  it('renders pagination when multiple pages exist', async () => {
    mockList.mockResolvedValueOnce({
      ...fakePage,
      totalPages: 3,
      totalElements: 55,
      first: true,
      last: false,
    })
    render(<ChangeList />)

    await waitFor(() => {
      expect(screen.getByText(/page 1 of 3/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/55 changes/i)).toBeInTheDocument()
    expect(screen.getByText(/next/i)).toBeEnabled()
  })
})
