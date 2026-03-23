// src/features/changes/store/useChangesStore.ts
import { create } from 'zustand'
import type { Change, PageResponse } from '../types'

interface ChangesState {
  page: PageResponse<Change> | null
  currentPage: number
  selectedChangeId: string | null
  isPolling: boolean

  setPage: (page: PageResponse<Change>) => void
  setCurrentPage: (page: number) => void
  setSelectedChangeId: (id: string | null) => void
  setIsPolling: (v: boolean) => void
  upsertChange: (change: Change) => void
}

export const useChangesStore = create<ChangesState>((set) => ({
  page: null,
  currentPage: 0,
  selectedChangeId: null,
  isPolling: false,

  setPage: (page) => set({ page }),
  setCurrentPage: (currentPage) => set({ currentPage }),
  setSelectedChangeId: (id) => set({ selectedChangeId: id }),
  setIsPolling: (v) => set({ isPolling: v }),

  upsertChange: (change) =>
    set((state) => {
      if (!state.page) return {}
      const content = state.page.content.map((c) =>
        c.changeId === change.changeId ? change : c
      )
      return { page: { ...state.page, content } }
    }),
}))
