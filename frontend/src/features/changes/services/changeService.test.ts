// src/features/changes/services/changeService.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import changeService from './changeService'
import { http } from '@/shared/lib/http'
import type { Change, ChangeEvent, CreateChangeResponse, PageResponse } from '../types'

vi.mock('@/shared/lib/http', () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockGet = vi.mocked(http.get)
const mockPost = vi.mocked(http.post)

describe('changeService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('create', () => {
    it('posts to /changes and returns the response', async () => {
      const response: CreateChangeResponse = {
        changeId: 'abc-123',
        status: 'PREPARED',
        correlationId: 'corr-1',
        createdAt: '2024-06-15T10:00:00Z',
      }
      mockPost.mockResolvedValueOnce({ data: response })

      const result = await changeService.create({
        title: 'Deploy v2',
        componentId: 'payment-service',
        requestedBy: 'user-1',
        scheduledAt: '2024-12-01T10:00:00Z',
      })

      expect(mockPost).toHaveBeenCalledWith('/changes', {
        title: 'Deploy v2',
        componentId: 'payment-service',
        requestedBy: 'user-1',
        scheduledAt: '2024-12-01T10:00:00Z',
      })
      expect(result).toEqual(response)
    })
  })

  describe('list', () => {
    it('gets /changes with params and returns page data', async () => {
      const page: PageResponse<Change> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 20,
        first: true,
        last: true,
      }
      mockGet.mockResolvedValueOnce({ data: page })

      const result = await changeService.list({ page: 0, size: 20 })

      expect(mockGet).toHaveBeenCalledWith('/changes', { params: { page: 0, size: 20 } })
      expect(result).toEqual(page)
    })

    it('sends no params when called without arguments', async () => {
      mockGet.mockResolvedValueOnce({ data: { content: [] } })
      await changeService.list()
      expect(mockGet).toHaveBeenCalledWith('/changes', { params: {} })
    })
  })

  describe('getEvents', () => {
    it('gets /changes/:id/events and returns events array', async () => {
      const events: ChangeEvent[] = [
        {
          eventId: 'evt-1',
          changeId: 'change-1',
          eventType: 'ChangePreparedEvent',
          payload: '{}',
          occurredAt: '2024-06-15T10:30:00Z',
        },
      ]
      mockGet.mockResolvedValueOnce({ data: events })

      const result = await changeService.getEvents('change-1')

      expect(mockGet).toHaveBeenCalledWith('/changes/change-1/events')
      expect(result).toEqual(events)
    })
  })
})
