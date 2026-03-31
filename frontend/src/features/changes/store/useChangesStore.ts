// src/features/changes/store/useChangesStore.ts
import { create } from 'zustand'
import type { Change, PageResponse } from '../types'

interface ChangesState {
  page: PageResponse<Change> | null
  currentPage: number
  selectedChangeId: string | null

  setPage: (page: PageResponse<Change>) => void
  setCurrentPage: (page: number) => void
  setSelectedChangeId: (id: string | null) => void
  upsertChange: (change: Change) => void
}

export const useChangesStore = create<ChangesState>((set) => ({
  page: null,
  currentPage: 0,
  selectedChangeId: null,

  setPage: (page) => set({ page }),
  setCurrentPage: (currentPage) => set({ currentPage }),
  setSelectedChangeId: (id) => set({ selectedChangeId: id }),

  upsertChange: (change) =>
    set((state) => {
      if (!state.page) return {}
      const content = state.page.content.map((c) =>
        c.changeId === change.changeId ? change : c,
      )
      return { page: { ...state.page, content } }
    }),
}))
