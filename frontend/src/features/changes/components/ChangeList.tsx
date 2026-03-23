// src/features/changes/components/ChangeList.tsx
import { useEffect } from 'react'
import { format } from 'date-fns'
import { StatusBadge } from '@/shared/components/StatusBadge'
import { useChanges } from '../hooks/useChanges'
import { useChangesStore } from '../store/useChangesStore'

export function ChangeList() {
  const { changes, page, loading, error, pollError, load } = useChanges()
  const { selectedChangeId, setSelectedChangeId } = useChangesStore()

  useEffect(() => {
    load()
  }, []) // eslint-disable-line

  if (loading && changes.length === 0) {
    return <LoadingSkeleton />
  }

  if (error && changes.length === 0) {
    return (
      <div className="rounded-md bg-red-50 border border-red-300 px-4 py-3 text-sm text-red-700">
        Failed to load changes: {error.detail}
        <button onClick={load} className="ml-3 underline">Retry</button>
      </div>
    )
  }

  return (
    <div>
      {/* Poll failure banner */}
      {pollError && (
        <div className="mb-3 rounded-md bg-yellow-50 border border-yellow-300 px-3 py-2 text-xs text-yellow-800">
          ⚠ Live updates paused — connection issue. Retrying…
        </div>
      )}

      {/* Empty state */}
      {changes.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <p className="text-lg">No changes registered yet.</p>
          <p className="text-sm mt-1">Create your first change using the form above.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 shadow-sm">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {['Change ID', 'Title', 'Component', 'Status', 'Created At', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {changes.map((change) => (
                <tr
                  key={change.changeId}
                  className={[
                    'hover:bg-blue-50 transition-colors cursor-pointer',
                    selectedChangeId === change.changeId ? 'bg-blue-50 ring-1 ring-inset ring-blue-300' : '',
                  ].join(' ')}
                  onClick={() =>
                    setSelectedChangeId(
                      selectedChangeId === change.changeId ? null : change.changeId
                    )
                  }
                >
                  <td className="px-4 py-3 font-mono text-xs text-gray-500 whitespace-nowrap">
                    {change.changeId.slice(0, 8)}…
                  </td>
                  <td className="px-4 py-3 text-gray-900 font-medium max-w-xs truncate">
                    {change.title}
                  </td>
                  <td className="px-4 py-3 text-gray-600 whitespace-nowrap">
                    <code className="bg-gray-100 rounded px-1.5 py-0.5 text-xs">
                      {change.componentId}
                    </code>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <StatusBadge status={change.status} size="sm" />
                  </td>
                  <td className="px-4 py-3 text-gray-500 whitespace-nowrap text-xs">
                    {format(new Date(change.createdAt), 'dd MMM yyyy HH:mm')}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-right">
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        setSelectedChangeId(change.changeId)
                      }}
                      className="text-xs text-blue-600 hover:underline"
                    >
                      Timeline →
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
        <div className="mt-4 flex items-center justify-between text-sm text-gray-600">
          <span>
            {page.totalElements} changes — Page {page.number + 1} of {page.totalPages}
          </span>
        </div>
      )}
    </div>
  )
}

function LoadingSkeleton() {
  return (
    <div className="space-y-2 animate-pulse">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-10 bg-gray-100 rounded" />
      ))}
    </div>
  )
}
