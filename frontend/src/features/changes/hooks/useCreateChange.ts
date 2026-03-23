// src/features/changes/hooks/useCreateChange.ts
import { useState } from 'react'
import changeService from '../services/changeService'
import type { CreateChangePayload, CreateChangeResponse, ApiError } from '../types'

export function useCreateChange() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [result, setResult] = useState<CreateChangeResponse | null>(null)

  const create = async (payload: CreateChangePayload): Promise<CreateChangeResponse | null> => {
    setLoading(true)
    setError(null)
    try {
      const data = await changeService.create(payload)
      setResult(data)
      return data
    } catch (e) {
      setError(e as ApiError)
      return null
    } finally {
      setLoading(false)
    }
  }

  return { create, loading, error, result, reset: () => { setError(null); setResult(null) } }
}
