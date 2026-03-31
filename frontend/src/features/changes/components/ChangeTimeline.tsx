// src/features/changes/components/ChangeTimeline.tsx
import { format } from 'date-fns'
import { useChangeEvents } from '../hooks/useChangeEvents'

const dotColor: Record<string, string> = {
  ChangePreparedEvent: 'bg-blue-500 ring-blue-200',
  ChangeCompletedEvent: 'bg-emerald-500 ring-emerald-200',
  ChangeFailedEvent: 'bg-red-500 ring-red-200',
}

const iconBg: Record<string, string> = {
  ChangePreparedEvent: 'bg-blue-100 text-blue-600',
  ChangeCompletedEvent: 'bg-emerald-100 text-emerald-600',
  ChangeFailedEvent: 'bg-red-100 text-red-600',
}

function EventIcon({ type }: { type: string }) {
  if (type === 'ChangeCompletedEvent') {
    return (
      <svg
        width="16"
        height="16"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth={2.5}
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
        />
      </svg>
    )
  }
  if (type === 'ChangeFailedEvent') {
    return (
      <svg
        width="16"
        height="16"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth={2.5}
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
        />
      </svg>
    )
  }
  return (
    <svg
      width="16"
      height="16"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z"
      />
    </svg>
  )
}

interface Props {
  changeId: string
  onClose: () => void
}

export function ChangeTimeline({ changeId, onClose }: Props) {
  const { events, loading, error, reload } = useChangeEvents(changeId)

  return (
    <div className="rounded-xl border border-slate-200 shadow-sm bg-white overflow-hidden">
      {/* Dark gradient header */}
      <div className="bg-gradient-to-r from-slate-800 to-indigo-800 px-5 py-4 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-white">Event Timeline</h3>
          <p className="text-xs text-indigo-300/80 font-mono mt-0.5 truncate max-w-xs">
            {changeId}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={reload}
            className="inline-flex items-center gap-1 text-xs text-indigo-300 hover:text-white transition-colors"
          >
            <svg
              width="14"
              height="14"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2.5}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99"
              />
            </svg>
            Refresh
          </button>
          <button
            onClick={onClose}
            className="text-indigo-300 hover:text-white transition-colors"
            aria-label="Close timeline"
          >
            <svg
              width="20"
              height="20"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>
      </div>

      <div className="px-6 py-6">
        {loading && (
          <div className="space-y-5 animate-pulse">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="flex gap-4">
                <div className="w-8 h-8 rounded-full bg-slate-100 shrink-0" />
                <div className="flex-1 space-y-2 pt-1">
                  <div className="h-3 bg-slate-100 rounded w-1/3" />
                  <div className="h-3 bg-slate-100 rounded w-2/3" />
                </div>
              </div>
            ))}
          </div>
        )}

        {error && (
          <div className="flex items-center gap-2 text-sm text-red-600">
            <svg
              width="16"
              height="16"
              style={{ flexShrink: 0 }}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z"
              />
            </svg>
            Failed to load timeline: {error.detail}
          </div>
        )}

        {!loading && !error && events.length === 0 && (
          <div className="text-center py-8">
            <svg
              width="40"
              height="40"
              style={{ margin: '0 auto 0.75rem', color: '#e2e8f0' }}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            <p className="text-sm text-slate-400">No events recorded yet.</p>
          </div>
        )}

        {!loading && events.length > 0 && (
          <ol className="relative border-l-2 border-slate-100 space-y-6 ml-3">
            {events.map((event) => {
              const dot =
                dotColor[event.eventType] ?? 'bg-slate-400 ring-slate-200'
              const icon =
                iconBg[event.eventType] ?? 'bg-slate-100 text-slate-500'
              let parsed: Record<string, unknown> | null = null
              try {
                parsed = JSON.parse(event.payload)
              } catch {
                /* raw */
              }

              return (
                <li key={event.eventId} className="ml-6">
                  {/* Timeline dot */}
                  <span
                    className={[
                      'absolute -left-2.5 flex h-5 w-5 items-center justify-center rounded-full ring-4 ring-white',
                      dot,
                    ].join(' ')}
                  />
                  {/* Event card */}
                  <div className="rounded-lg border border-slate-100 bg-slate-50/60 px-4 py-3">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-center gap-2.5 min-w-0">
                        <span
                          className={`inline-flex w-7 h-7 items-center justify-center rounded-full shrink-0 ${icon}`}
                        >
                          <EventIcon type={event.eventType} />
                        </span>
                        <p className="text-sm font-semibold text-slate-800">
                          {event.eventType}
                        </p>
                      </div>
                      <time className="shrink-0 text-xs text-slate-400 tabular-nums mt-0.5">
                        {format(new Date(event.occurredAt), 'dd MMM HH:mm:ss')}
                      </time>
                    </div>
                    {parsed && (
                      <details className="mt-2">
                        <summary className="text-xs text-slate-400 cursor-pointer hover:text-indigo-600 select-none pl-9">
                          Show payload
                        </summary>
                        <pre
                          className="mt-2 ml-9 overflow-x-auto rounded-lg bg-white border border-slate-100
                                       p-3 text-xs text-slate-600 leading-relaxed shadow-sm"
                        >
                          {JSON.stringify(parsed, null, 2)}
                        </pre>
                      </details>
                    )}
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
