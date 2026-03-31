// src/features/changes/hooks/useChanges.ts
import { useState, useCallback } from 'react'
import { useChangesStore } from '../store/useChangesStore'
import changeService from '../services/changeService'
import type { ApiError, ChangeStats } from '../types'
import { toSinceParam } from '../utils/timeRange'
import { usePolling } from '@/shared/hooks/usePolling'

export function useChanges() {
  const { page, currentPage, timeRange, setPage, setCurrentPage } = useChangesStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [pollError, setPollError] = useState(false)
  const [stats, setStats] = useState<ChangeStats | null>(null)

  const fetchStats = useCallback(async () => {
    try {
      const since = toSinceParam(timeRange)
      const data = await changeService.getStats(since)
      setStats(data)
    } catch {
      // stats are non-critical; keep previous value on error
    }
  }, [timeRange])

  const fetch = useCallback(async (pageNum: number) => {
    try {
      const since = toSinceParam(timeRange)
      const data = await changeService.list({ page: pageNum, size: 20, since })
      setPage(data)
      setPollError(false)
    } catch (e) {
      setError(e as ApiError)
      setPollError(true)
    }
  }, [timeRange, setPage])

  // Initial load
  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await Promise.all([fetch(currentPage), fetchStats()])
    } finally {
      setLoading(false)
    }
  }, [fetch, fetchStats, currentPage])

  const goToPage = useCallback(async (pageNum: number) => {
    setCurrentPage(pageNum)
    setLoading(true)
    setError(null)
    try {
      const since = toSinceParam(timeRange)
      const data = await changeService.list({ page: pageNum, size: 20, since })
      setPage(data)
    } catch (e) {
      setError(e as ApiError)
    } finally {
      setLoading(false)
    }
  }, [timeRange, setCurrentPage, setPage])

  // Background poll: refresh both list and stats
  usePolling(() => Promise.all([fetch(currentPage), fetchStats()]), { interval: 5_000, enabled: !!page })

  return { changes: page?.content ?? [], page, loading, error, pollError, stats, load, goToPage }
}
