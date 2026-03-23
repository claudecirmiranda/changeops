// src/features/changes/components/ChangeTimeline.tsx
import { format } from 'date-fns'
import { useChangeEvents } from '../hooks/useChangeEvents'

const eventColors: Record<string, string> = {
  ChangePreparedEvent:  'bg-blue-500',
  ChangeCompletedEvent: 'bg-green-500',
  ChangeFailedEvent:    'bg-red-500',
}

const eventIcons: Record<string, string> = {
  ChangePreparedEvent:  '📋',
  ChangeCompletedEvent: '✅',
  ChangeFailedEvent:    '❌',
}

interface Props {
  changeId: string
  onClose: () => void
}

export function ChangeTimeline({ changeId, onClose }: Props) {
  const { events, loading, error, reload } = useChangeEvents(changeId)

  return (
    <div className="rounded-lg border border-gray-200 shadow-sm bg-white">
      <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
        <div>
          <h3 className="text-sm font-semibold text-gray-900">Event Timeline</h3>
          <p className="text-xs text-gray-400 font-mono mt-0.5">{changeId}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={reload}
            className="text-xs text-gray-500 hover:text-blue-600 transition-colors"
          >
            ↻ Refresh
          </button>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
            aria-label="Close timeline"
          >
            ×
          </button>
        </div>
      </div>

      <div className="px-5 py-5">
        {loading && (
          <div className="space-y-4 animate-pulse">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="flex gap-3">
                <div className="w-3 h-3 rounded-full bg-gray-200 mt-1 shrink-0" />
                <div className="flex-1 space-y-1.5">
                  <div className="h-3 bg-gray-100 rounded w-1/3" />
                  <div className="h-3 bg-gray-100 rounded w-2/3" />
                </div>
              </div>
            ))}
          </div>
        )}

        {error && (
          <p className="text-sm text-red-600">Failed to load timeline: {error.detail}</p>
        )}

        {!loading && !error && events.length === 0 && (
          <p className="text-sm text-gray-400 text-center py-6">No events recorded yet.</p>
        )}

        {!loading && events.length > 0 && (
          <ol className="relative border-l border-gray-200 space-y-6 ml-2">
            {events.map((event, idx) => {
              const dotColor = eventColors[event.eventType] ?? 'bg-gray-400'
              const icon = eventIcons[event.eventType] ?? '🔵'
              let parsed: Record<string, unknown> | null = null
              try { parsed = JSON.parse(event.payload) } catch { /* raw */ }

              return (
                <li key={event.eventId} className="ml-5">
                  {/* Dot on the timeline */}
                  <span
                    className={[
                      'absolute -left-1.5 flex h-3 w-3 items-center justify-center rounded-full ring-2 ring-white',
                      dotColor,
                    ].join(' ')}
                  />
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-gray-900">
                        {icon} {event.eventType}
                      </p>
                      {parsed && (
                        <details className="mt-1">
                          <summary className="text-xs text-gray-400 cursor-pointer hover:text-blue-600">
                            Payload
                          </summary>
                          <pre className="mt-1.5 overflow-x-auto rounded bg-gray-50 border border-gray-100
                                         p-2 text-xs text-gray-700 leading-relaxed">
                            {JSON.stringify(parsed, null, 2)}
                          </pre>
                        </details>
                      )}
                    </div>
                    <time className="shrink-0 text-xs text-gray-400 mt-0.5">
                      {format(new Date(event.occurredAt), 'dd MMM HH:mm:ss')}
                    </time>
                  </div>
                </li>
              )
            })}
          </ol>
        )}
      </div>
    </div>
  )
}
