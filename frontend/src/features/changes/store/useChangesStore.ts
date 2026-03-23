// src/features/changes/store/useChangesStore.ts
import { create } from 'zustand'
import type { Change, PageResponse } from '../types'

interface ChangesState {
  page: PageResponse<Change> | null
  selectedChangeId: string | null
  isPolling: boolean

  setPage: (page: PageResponse<Change>) => void
  setSelectedChangeId: (id: string | null) => void
  setIsPolling: (v: boolean) => void
  upsertChange: (change: Change) => void
}

export const useChangesStore = create<ChangesState>((set) => ({
  page: null,
  selectedChangeId: null,
  isPolling: false,

  setPage: (page) => set({ page }),
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
