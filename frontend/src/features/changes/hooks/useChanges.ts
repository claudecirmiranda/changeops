// src/features/changes/hooks/useChanges.ts
import { useState, useCallback } from 'react'
import { useChangesStore } from '../store/useChangesStore'
import changeService, { type ListChangesParams } from '../services/changeService'
import type { ApiError } from '../types'
import { usePolling } from '@/shared/hooks/usePolling'

export function useChanges(params: ListChangesParams = {}) {
  const { page, setPage } = useChangesStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [pollError, setPollError] = useState(false)

  const fetch = useCallback(async () => {
    try {
      const data = await changeService.list(params)
      setPage(data)
      setPollError(false)
    } catch (e) {
      setError(e as ApiError)
      setPollError(true)
    }
  }, [JSON.stringify(params)]) // eslint-disable-line

  // Initial load
  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await fetch()
    } finally {
      setLoading(false)
    }
  }, [fetch])

  // Background poll (silent refresh, no loading spinner)
  usePolling(fetch, { interval: 5_000, enabled: !!page })

  return { changes: page?.content ?? [], page, loading, error, pollError, load }
}
