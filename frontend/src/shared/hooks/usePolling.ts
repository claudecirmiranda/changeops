// src/shared/hooks/usePolling.ts
import { useEffect, useRef, useCallback } from 'react'

interface UsePollingOptions {
  interval?: number
  enabled?: boolean
}

export function usePolling(
  callback: () => void | Promise<void>,
  { interval = 5_000, enabled = true }: UsePollingOptions = {},
) {
  const savedCallback = useRef(callback)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    savedCallback.current = callback
  }, [callback])

  const stop = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])

  useEffect(() => {
    if (!enabled) return

    const tick = async () => {
      try {
        await savedCallback.current()
      } finally {
        timerRef.current = setTimeout(tick, interval)
      }
    }

    timerRef.current = setTimeout(tick, interval)
    return stop
  }, [enabled, interval, stop])

  return { stop }
}
