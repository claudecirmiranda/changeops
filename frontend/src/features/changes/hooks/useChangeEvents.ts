// src/features/changes/hooks/useChangeEvents.ts
import { useState, useEffect, useCallback } from 'react'
import changeService from '../services/changeService'
import type { ChangeEvent, ApiError } from '../types'

export function useChangeEvents(changeId: string | null) {
  const [events, setEvents] = useState<ChangeEvent[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const load = useCallback(async () => {
    if (!changeId) return
    setLoading(true)
    setError(null)
    try {
      const data = await changeService.getEvents(changeId)
      setEvents(data)
    } catch (e) {
      setError(e as ApiError)
    } finally {
      setLoading(false)
    }
  }, [changeId])

  useEffect(() => {
    load()
  }, [load])

  return { events, loading, error, reload: load }
}
