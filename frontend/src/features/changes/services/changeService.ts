// src/features/changes/services/changeService.ts
import { http } from '@/shared/lib/http'
import type {
  Change,
  ChangeEvent,
  CreateChangePayload,
  CreateChangeResponse,
  PageResponse,
  ChangeStatus,
} from '../types'

export interface ListChangesParams {
  status?: ChangeStatus
  componentId?: string
  page?: number
  size?: number
  sort?: string
}

const changeService = {
  async create(payload: CreateChangePayload): Promise<CreateChangeResponse> {
    const { data } = await http.post<CreateChangeResponse>('/changes', payload)
    return data
  },

  async list(params: ListChangesParams = {}): Promise<PageResponse<Change>> {
    const { data } = await http.get<PageResponse<Change>>('/changes', { params })
    return data
  },

  async getEvents(changeId: string): Promise<ChangeEvent[]> {
    const { data } = await http.get<ChangeEvent[]>(`/changes/${changeId}/events`)
    return data
  },
}

export default changeService
