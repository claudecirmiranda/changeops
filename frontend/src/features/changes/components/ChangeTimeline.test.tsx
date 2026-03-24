// src/features/changes/components/ChangeTimeline.test.tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChangeTimeline } from './ChangeTimeline'
import changeService from '../services/changeService'
import type { ChangeEvent } from '../types'

vi.mock('../services/changeService')

const mockGetEvents = vi.mocked(changeService.getEvents)

const fakeEvents: ChangeEvent[] = [
  {
    eventId: 'evt-1',
    changeId: 'change-1',
    eventType: 'ChangePreparedEvent',
    payload: JSON.stringify({ title: 'Deploy v2' }),
    occurredAt: '2024-06-15T10:30:00Z',
  },
  {
    eventId: 'evt-2',
    changeId: 'change-1',
    eventType: 'ChangeCompletedEvent',
    payload: JSON.stringify({ result: 'ok' }),
    occurredAt: '2024-06-15T10:35:00Z',
  },
]

describe('ChangeTimeline', () => {
  const onClose = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders events with correct event types', async () => {
    mockGetEvents.mockResolvedValueOnce(fakeEvents)
    render(<ChangeTimeline changeId="change-1" onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText(/ChangePreparedEvent/)).toBeInTheDocument()
    })
    expect(screen.getByText(/ChangeCompletedEvent/)).toBeInTheDocument()
  })

  it('shows empty state when no events exist', async () => {
    mockGetEvents.mockResolvedValueOnce([])
    render(<ChangeTimeline changeId="change-1" onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText(/no events recorded yet/i)).toBeInTheDocument()
    })
  })

  it('shows error message when loading fails', async () => {
    mockGetEvents.mockRejectedValueOnce({
      title: 'Error',
      detail: 'Timeline unavailable',
      status: 500,
      timestamp: new Date().toISOString(),
    })
    render(<ChangeTimeline changeId="change-1" onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText(/timeline unavailable/i)).toBeInTheDocument()
    })
  })

  it('calls onClose when close button is clicked', async () => {
    mockGetEvents.mockResolvedValueOnce(fakeEvents)
    render(<ChangeTimeline changeId="change-1" onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText(/ChangePreparedEvent/)).toBeInTheDocument()
    })

    await userEvent.click(screen.getByLabelText(/close timeline/i))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('displays the changeId in the header', async () => {
    mockGetEvents.mockResolvedValueOnce([])
    render(<ChangeTimeline changeId="change-1" onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText('change-1')).toBeInTheDocument()
    })
  })
})
