// src/features/changes/utils/timeRange.ts
import type { TimeRange } from '../store/useChangesStore'

const OFFSETS_MS: Record<Exclude<TimeRange, 'all'>, number> = {
  '5m': 5 * 60 * 1000,
  '15m': 15 * 60 * 1000,
  '1h': 60 * 60 * 1000,
  '6h': 6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
}

/**
 * Converts a TimeRange value to an ISO-8601 string suitable for the `since`
 * query parameter, or undefined when the range is 'all'.
 */
export function toSinceParam(timeRange: TimeRange): string | undefined {
  if (timeRange === 'all') return undefined
  return new Date(Date.now() - OFFSETS_MS[timeRange as Exclude<TimeRange, 'all'>]).toISOString()
}
