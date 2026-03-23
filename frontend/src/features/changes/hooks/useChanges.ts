// src/features/changes/hooks/useChanges.ts
import { useState, useCallback } from 'react'
import { useChangesStore } from '../store/useChangesStore'
import changeService from '../services/changeService'
import type { ApiError } from '../types'
import { usePolling } from '@/shared/hooks/usePolling'

export function useChanges() {
  const { page, currentPage, setPage, setCurrentPage } = useChangesStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [pollError, setPollError] = useState(false)

  const fetch = useCallback(async (pageNum: number = currentPage) => {
    try {
      const data = await changeService.list({ page: pageNum, size: 20 })
      setPage(data)
      setPollError(false)
    } catch (e) {
      setError(e as ApiError)
      setPollError(true)
    }
  }, [currentPage]) // eslint-disable-line

  // Initial load
  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await fetch(currentPage)
    } finally {
      setLoading(false)
    }
  }, [fetch, currentPage])

  const goToPage = useCallback(async (pageNum: number) => {
    setCurrentPage(pageNum)
    setLoading(true)
    setError(null)
    try {
      const data = await changeService.list({ page: pageNum, size: 20 })
      setPage(data)
    } catch (e) {
      setError(e as ApiError)
    } finally {
      setLoading(false)
    }
  }, [setCurrentPage, setPage])

  // Background poll (silent refresh, no loading spinner)
  usePolling(() => fetch(currentPage), { interval: 5_000, enabled: !!page })

  return { changes: page?.content ?? [], page, loading, error, pollError, load, goToPage }
}
