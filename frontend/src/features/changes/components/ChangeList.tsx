// src/features/changes/components/ChangeList.tsx
import { useEffect } from 'react'
import { format } from 'date-fns'
import { StatusBadge } from '@/shared/components/StatusBadge'
import { useChanges } from '../hooks/useChanges'
import { useChangesStore } from '../store/useChangesStore'

export function ChangeList() {
  const { changes, page, loading, error, pollError, load, goToPage } =
    useChanges()
  const { selectedChangeId, setSelectedChangeId } = useChangesStore()

  useEffect(() => {
    load()
  }, []) // eslint-disable-line

  if (loading && changes.length === 0) {
    return <LoadingSkeleton />
  }

  if (error && changes.length === 0) {
    return (
      <div className="rounded-xl bg-red-50 border border-red-200 px-4 py-4 text-sm text-red-700 flex items-center gap-3 shadow-sm">
        <svg
          width="20"
          height="20"
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
        <span>Failed to load changes: {error.detail}</span>
        <button
          onClick={load}
          className="ml-auto text-red-600 underline underline-offset-2 hover:text-red-800 font-medium text-xs"
        >
          Retry
        </button>
      </div>
    )
  }

  return (
    <div>
      {/* Poll failure banner */}
      {pollError && (
        <div className="mb-3 rounded-lg bg-amber-50 border border-amber-200 px-3 py-2.5 text-xs text-amber-800 flex items-center gap-2">
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
              d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z"
            />
          </svg>
          Live updates paused — connection issue. Retrying…
        </div>
      )}

      {/* Empty state */}
      {changes.length === 0 ? (
        <div className="text-center py-20 rounded-xl border border-dashed border-slate-300 bg-white">
          <svg
            width="48"
            height="48"
            style={{ margin: '0 auto 1rem', color: '#cbd5e1' }}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25zM6.75 12h.008v.008H6.75V12zm0 3h.008v.008H6.75V15zm0 3h.008v.008H6.75V18z"
            />
          </svg>
          <p className="text-base font-medium text-slate-500">
            No changes registered yet
          </p>
          <p className="text-sm text-slate-400 mt-1">
            Create your first change using the button above.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 shadow-sm">
          <table className="min-w-full divide-y divide-slate-100 text-sm">
            <thead>
              <tr className="bg-slate-50/80">
                {[
                  'Change ID',
                  'Title',
                  'Component',
                  'Status',
                  'Created At',
                  '',
                ].map((h) => (
                  <th
                    key={h}
                    className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-slate-50">
              {changes.map((change, idx) => (
                <tr
                  key={change.changeId}
                  className={[
                    'hover:bg-indigo-50/60 transition-colors cursor-pointer',
                    selectedChangeId === change.changeId
                      ? 'bg-indigo-50 ring-2 ring-inset ring-indigo-300'
                      : idx % 2 !== 0
                        ? 'bg-slate-50/40'
                        : 'bg-white',
                  ].join(' ')}
                  onClick={() =>
                    setSelectedChangeId(
                      selectedChangeId === change.changeId
                        ? null
                        : change.changeId,
                    )
                  }
                >
                  <td className="px-4 py-3.5 font-mono text-xs text-slate-400 whitespace-nowrap">
                    {change.changeId.slice(0, 8)}&hellip;
                  </td>
                  <td className="px-4 py-3.5 text-slate-800 font-medium max-w-xs truncate">
                    {change.title}
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <span className="inline-flex items-center rounded-md bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 ring-1 ring-inset ring-slate-200">
                      {change.componentId}
                    </span>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <StatusBadge status={change.status} size="sm" />
                  </td>
                  <td className="px-4 py-3.5 text-slate-400 whitespace-nowrap text-xs tabular-nums">
                    {format(new Date(change.createdAt), 'dd MMM yyyy HH:mm')}
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap text-right">
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        setSelectedChangeId(change.changeId)
                      }}
                      className="inline-flex items-center gap-1 text-xs text-indigo-500 hover:text-indigo-700 font-medium hover:underline underline-offset-2 transition-colors"
                    >
                      Timeline
                      <svg
                        width="12"
                        height="12"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2.5}
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M13.5 4.5L21 12m0 0l-7.5 7.5M21 12H3"
                        />
                      </svg>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Pagination */}
      {page && page.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <span className="text-xs">
            {page.totalElements} changes &mdash; Page {page.number + 1} of{' '}
            {page.totalPages}
          </span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => goToPage(page.number - 1)}
              disabled={page.first || loading}
              className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border border-slate-200 text-xs font-medium hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <svg
                width="12"
                height="12"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2.5}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M15.75 19.5L8.25 12l7.5-7.5"
                />
              </svg>
              Prev
            </button>
            <button
              onClick={() => goToPage(page.number + 1)}
              disabled={page.last || loading}
              className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border border-slate-200 text-xs font-medium hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              Next
              <svg
                width="12"
                height="12"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2.5}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M8.25 4.5l7.5 7.5-7.5 7.5"
                />
              </svg>
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function LoadingSkeleton() {
  return (
    <div className="rounded-xl border border-slate-200 overflow-hidden shadow-sm animate-pulse">
      <div className="h-10 bg-slate-50 border-b border-slate-100" />
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className={`h-14 border-b border-slate-50 flex items-center px-4 gap-4 ${i % 2 !== 0 ? 'bg-slate-50/40' : 'bg-white'}`}
        >
          <div className="h-3 bg-slate-100 rounded w-20" />
          <div className="h-3 bg-slate-100 rounded w-48" />
          <div className="h-5 bg-slate-100 rounded-md w-24 ml-auto" />
          <div className="h-5 bg-slate-100 rounded-full w-20" />
          <div className="h-3 bg-slate-100 rounded w-28" />
        </div>
      ))}
    </div>
  )
}
