import { describe, it, expect, beforeEach } from 'vitest'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { http } from './http'

describe('http', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  describe('request interceptor', () => {
    const runRequestInterceptor = (
      config: InternalAxiosRequestConfig,
    ): InternalAxiosRequestConfig => {
      // Axios stores interceptors internally; we grab the fulfilled handler
      // from the request interceptor manager.
      const manager = (
        http.interceptors.request as unknown as {
          handlers: {
            fulfilled: (
              c: InternalAxiosRequestConfig,
            ) => InternalAxiosRequestConfig
          }[]
        }
      ).handlers
      return manager[0].fulfilled(config)
    }

    const baseConfig = (): InternalAxiosRequestConfig =>
      ({
        headers: {},
      }) as unknown as InternalAxiosRequestConfig

    it('adds Bearer token when access_token exists in localStorage', () => {
      localStorage.setItem('access_token', 'my-jwt-token')
      const config = runRequestInterceptor(baseConfig())
      expect(config.headers.Authorization).toBe('Bearer my-jwt-token')
    })

    it('does not add Authorization header when no access_token', () => {
      const config = runRequestInterceptor(baseConfig())
      expect(config.headers.Authorization).toBeUndefined()
    })

    it('adds X-User-Id from localStorage when present', () => {
      localStorage.setItem('user_id', 'custom-user')
      const config = runRequestInterceptor(baseConfig())
      expect(config.headers['X-User-Id']).toBe('custom-user')
    })

    it('defaults X-User-Id to dev-user-001 when not in localStorage', () => {
      const config = runRequestInterceptor(baseConfig())
      expect(config.headers['X-User-Id']).toBe('dev-user-001')
    })
  })

  describe('response error interceptor', () => {
    const runErrorInterceptor = (error: unknown): Promise<never> => {
      const manager = (
        http.interceptors.response as unknown as {
          handlers: { rejected: (e: unknown) => Promise<never> }[]
        }
      ).handlers
      return manager[0].rejected(error)
    }

    it('normalises AxiosError with response data into ApiError', async () => {
      const axiosError = {
        response: {
          status: 422,
          data: {
            title: 'Validation Failed',
            detail: 'Title is required',
            fields: { title: 'must not be blank' },
            timestamp: '2026-03-30T10:00:00Z',
          },
        },
        message: 'Request failed',
      } as unknown as AxiosError

      await expect(runErrorInterceptor(axiosError)).rejects.toEqual({
        title: 'Validation Failed',
        detail: 'Title is required',
        status: 422,
        fields: { title: 'must not be blank' },
        timestamp: '2026-03-30T10:00:00Z',
      })
    })

    it('falls back to defaults when response has no data', async () => {
      const axiosError = {
        response: { status: 500, data: undefined },
        message: 'Network Error',
      } as unknown as AxiosError

      await expect(runErrorInterceptor(axiosError)).rejects.toMatchObject({
        title: 'Unexpected Error',
        detail: 'Network Error',
        status: 500,
      })
    })

    it('handles missing response (e.g. timeout)', async () => {
      const axiosError = {
        response: undefined,
        message: 'timeout of 10000ms exceeded',
      } as unknown as AxiosError

      await expect(runErrorInterceptor(axiosError)).rejects.toMatchObject({
        title: 'Unexpected Error',
        detail: 'timeout of 10000ms exceeded',
        status: 0,
      })
    })
  })
})
