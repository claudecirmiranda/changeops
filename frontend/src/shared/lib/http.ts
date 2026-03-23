// src/shared/lib/http.ts
import axios, { AxiosError } from 'axios'
import type { ApiError } from '@/features/changes/types'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
})

// Inject auth token if present
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  // dev convenience: X-User-Id header
  const userId = localStorage.getItem('user_id') ?? 'dev-user-001'
  config.headers['X-User-Id'] = userId
  return config
})

// Normalise errors into ApiError shape
http.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    const data = err.response?.data as Partial<ApiError> | undefined
    const apiError: ApiError = {
      title: data?.title ?? 'Unexpected Error',
      detail: data?.detail ?? err.message,
      status: err.response?.status ?? 0,
      fields: data?.fields,
      timestamp: data?.timestamp ?? new Date().toISOString(),
    }
    return Promise.reject(apiError)
  }
)
