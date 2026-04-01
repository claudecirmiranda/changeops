// src/shared/lib/http.ts
import axios, { AxiosError } from 'axios'
import type { ApiError } from '@/features/changes/types'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
})

// PLACEHOLDER DE SEGURANÇA: auth baseada em localStorage foi intencionalmente simplificada para este POC.
// Em produção, substituir por sessão com cookie httpOnly gerenciada pelo fluxo OAuth2 PKCE
// descrito em ROADMAP.md § 2.2 (Full JWT / OAuth2 Integration).
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // X-User-Id é um fallback exclusivo para dev, resolvido pelo backend quando nenhum JWT está presente (perfil local).
  const userId = localStorage.getItem('user_id') ?? 'dev-user-001'
  config.headers['X-User-Id'] = userId
  return config
})

// Normaliza erros no formato ApiError
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
  },
)
