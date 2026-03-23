// src/features/changes/components/ChangeForm.test.tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChangeForm } from './ChangeForm'
import changeService from '../services/changeService'

vi.mock('../services/changeService')

const mockCreate = vi.mocked(changeService.create)

describe('ChangeForm', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders all required fields', () => {
    render(<ChangeForm />)
    expect(screen.getByLabelText(/title/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/component id/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/scheduled at/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create change/i })).toBeInTheDocument()
  })

  it('calls onSuccess with changeId when form is valid', async () => {
    const onSuccess = vi.fn()
    mockCreate.mockResolvedValueOnce({
      changeId: 'abc-123',
      status: 'PREPARED',
      correlationId: 'corr-456',
      createdAt: new Date().toISOString(),
    })

    render(<ChangeForm onSuccess={onSuccess} />)

    await userEvent.type(screen.getByLabelText(/title/i), 'Deploy v2')
    await userEvent.type(screen.getByLabelText(/component id/i), 'payment-service')
    await userEvent.type(screen.getByLabelText(/scheduled at/i), '2099-12-01T10:00')

    await userEvent.click(screen.getByRole('button', { name: /create change/i }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith('abc-123'))
  })

  it('shows API field errors without clearing form data', async () => {
    mockCreate.mockRejectedValueOnce({
      title: 'Validation Error',
      detail: 'Validation failed',
      status: 400,
      fields: { title: 'title is required' },
      timestamp: new Date().toISOString(),
    })

    render(<ChangeForm />)
    await userEvent.type(screen.getByLabelText(/component id/i), 'payment-service')
    await userEvent.click(screen.getByRole('button', { name: /create change/i }))

    await waitFor(() =>
      expect(screen.getByText(/title is required/i)).toBeInTheDocument()
    )
    // Form data preserved
    expect(screen.getByLabelText(/component id/i)).toHaveValue('payment-service')
  })
})
