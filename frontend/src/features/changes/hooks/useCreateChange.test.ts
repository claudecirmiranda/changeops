import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useCreateChange } from './useCreateChange'
import changeService from '../services/changeService'
import type { CreateChangeResponse, ApiError } from '../types'

vi.mock('../services/changeService')

const mockCreate = vi.mocked(changeService.create)

describe('useCreateChange', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns result on successful creation', async () => {
    const response: CreateChangeResponse = {
      changeId: 'abc-123',
      status: 'PREPARED',
      correlationId: 'corr-1',
      createdAt: '2024-06-15T10:00:00Z',
    }
    mockCreate.mockResolvedValueOnce(response)

    const { result } = renderHook(() => useCreateChange())

    await act(async () => {
      const data = await result.current.create({
        title: 'Deploy v2',
        componentId: 'payment-service',
        requestedBy: 'user-1',
        scheduledAt: '2024-12-01T10:00:00Z',
      })
      expect(data).toEqual(response)
    })

    expect(result.current.result).toEqual(response)
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('returns null and sets error on failure', async () => {
    const apiError: ApiError = {
      title: 'Validation Failed',
      detail: 'Title is required',
      status: 422,
      timestamp: '2024-06-15T10:00:00Z',
    }
    mockCreate.mockRejectedValueOnce(apiError)

    const { result } = renderHook(() => useCreateChange())

    await act(async () => {
      const data = await result.current.create({
        title: '',
        componentId: 'payment-service',
        requestedBy: 'user-1',
        scheduledAt: '2024-12-01T10:00:00Z',
      })
      expect(data).toBeNull()
    })

    expect(result.current.error).toEqual(apiError)
    expect(result.current.loading).toBe(false)
  })

  it('reset() clears error and result', async () => {
    const response: CreateChangeResponse = {
      changeId: 'abc-123',
      status: 'PREPARED',
      correlationId: 'corr-1',
      createdAt: '2024-06-15T10:00:00Z',
    }
    mockCreate.mockResolvedValueOnce(response)

    const { result } = renderHook(() => useCreateChange())

    await act(async () => {
      await result.current.create({
        title: 'Deploy v2',
        componentId: 'payment-service',
        requestedBy: 'user-1',
        scheduledAt: '2024-12-01T10:00:00Z',
      })
    })

    expect(result.current.result).not.toBeNull()

    act(() => {
      result.current.reset()
    })

    expect(result.current.error).toBeNull()
    expect(result.current.result).toBeNull()
  })
})
