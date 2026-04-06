// src/features/changes/types/index.ts

export type ChangeStatus =
  | 'DRAFT'
  | 'PREPARED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export interface Change {
  changeId: string
  title: string
  componentId: string
  status: ChangeStatus
  correlationId: string
  createdAt: string
  updatedAt: string
}

export interface ChangeEvent {
  eventId: string
  changeId: string
  eventType: string
  payload: string
  occurredAt: string
}

export interface CreateChangePayload {
  title: string
  description?: string
  componentId: string
  requestedBy: string
  scheduledAt: string
}

export interface CreateChangeResponse {
  changeId: string
  status: ChangeStatus
  correlationId: string
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
  statusSummary?: Partial<Record<ChangeStatus, number>>
}

export interface ApiError {
  title: string
  detail: string
  status: number
  fields?: Record<string, string>
  timestamp: string
}
