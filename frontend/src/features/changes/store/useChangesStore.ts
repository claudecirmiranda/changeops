// src/features/changes/store/useChangesStore.ts
import { create } from 'zustand'
import type { Change, PageResponse } from '../types'

export type TimeRange = '5m' | '15m' | '1h' | '6h' | '24h' | 'all'

interface ChangesState {
  page: PageResponse<Change> | null
  currentPage: number
  selectedChangeId: string | null
  timeRange: TimeRange

  setPage: (page: PageResponse<Change>) => void
  setCurrentPage: (page: number) => void
  setSelectedChangeId: (id: string | null) => void
  setTimeRange: (timeRange: TimeRange) => void
  upsertChange: (change: Change) => void
}

export const useChangesStore = create<ChangesState>((set) => ({
  page: null,
  currentPage: 0,
  selectedChangeId: null,
  timeRange: '1h',

  setPage: (page) => set({ page }),
  setCurrentPage: (currentPage) => set({ currentPage }),
  setSelectedChangeId: (id) => set({ selectedChangeId: id }),
  setTimeRange: (timeRange) => set({ timeRange, currentPage: 0, page: null }),

  upsertChange: (change) =>
    set((state) => {
      if (!state.page) return {}
      const content = state.page.content.map((c) =>
        c.changeId === change.changeId ? change : c
      )
      return { page: { ...state.page, content } }
    }),
}))
