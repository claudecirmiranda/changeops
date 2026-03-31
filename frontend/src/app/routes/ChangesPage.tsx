// src/app/routes/ChangesPage.tsx
import { useState } from 'react'
import { ChangeForm } from '@/features/changes/components/ChangeForm'
import { ChangeList } from '@/features/changes/components/ChangeList'
import { ChangeTimeline } from '@/features/changes/components/ChangeTimeline'
import { useChangesStore } from '@/features/changes/store/useChangesStore'

export function ChangesPage() {
  const [showForm, setShowForm] = useState(false)
  const [successMsg, setSuccessMsg] = useState<string | null>(null)
  const { selectedChangeId, setSelectedChangeId, page } = useChangesStore()

  const handleSuccess = (changeId: string) => {
    setShowForm(false)
    setSuccessMsg(`Change ${changeId.slice(0, 8)}… created successfully.`)
    setTimeout(() => setSuccessMsg(null), 5_000)
  }

  const stats = page
    ? {
        total: page.totalElements,
        prepared: page.content.filter((c) => c.status === 'PREPARED').length,
        completed: page.content.filter((c) => c.status === 'COMPLETED').length,
        failed: page.content.filter((c) => c.status === 'FAILED').length,
      }
    : null

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      {/* Header */}
      <header className="bg-gradient-to-r from-slate-900 via-slate-800 to-indigo-900 shadow-lg">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-indigo-500 flex items-center justify-center shadow-lg">
              <span className="text-white text-xs font-bold tracking-wider">
                CO
              </span>
            </div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-tight">
                ChangeOps
              </h1>
              <p className="text-xs text-indigo-300/80 mt-0.5">
                Change Management Dashboard
              </p>
            </div>
          </div>
          <button
            onClick={() => setShowForm((v) => !v)}
            className="inline-flex items-center gap-2 rounded-lg bg-indigo-500 px-4 py-2 text-sm
                       font-semibold text-white shadow-md hover:bg-indigo-400 active:bg-indigo-600
                       transition-all duration-150 ring-1 ring-indigo-400/50"
          >
            {showForm ? (
              <>
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
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
                Cancel
              </>
            ) : (
              <>
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
                    d="M12 4.5v15m7.5-7.5h-15"
                  />
                </svg>
                New Change
              </>
            )}
          </button>
        </div>
      </header>

      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        {/* Success banner */}
        {successMsg && (
          <div className="rounded-xl bg-emerald-50 border border-emerald-200 px-4 py-3 text-sm text-emerald-800 flex items-center justify-between shadow-sm">
            <span className="flex items-center gap-2">
              <svg
                width="16"
                height="16"
                style={{ color: '#10b981', flexShrink: 0 }}
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
              {successMsg}
            </span>
            <button
              onClick={() => setSuccessMsg(null)}
              className="text-emerald-400 hover:text-emerald-700 text-xl leading-none ml-4"
            >
              ×
            </button>
          </div>
        )}

        {/* Stats cards */}
        {stats && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard
              label="Total Changes"
              value={stats.total}
              valueClass="text-slate-700"
              bg="bg-white"
              border="border-slate-200"
            />
            <StatCard
              label="Prepared"
              value={stats.prepared}
              valueClass="text-blue-600"
              bg="bg-blue-50"
              border="border-blue-100"
            />
            <StatCard
              label="Completed"
              value={stats.completed}
              valueClass="text-emerald-600"
              bg="bg-emerald-50"
              border="border-emerald-100"
            />
            <StatCard
              label="Failed"
              value={stats.failed}
              valueClass="text-red-500"
              bg="bg-red-50"
              border="border-red-100"
            />
          </div>
        )}

        {/* Create form */}
        {showForm && (
          <section className="rounded-xl border border-slate-200 bg-white shadow-sm p-6">
            <h2 className="text-base font-semibold text-slate-800 mb-5">
              New Change Request
            </h2>
            <ChangeForm onSuccess={handleSuccess} />
          </section>
        )}

        {/* Changes list */}
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-semibold text-slate-800">Changes</h2>
            <span className="text-xs text-slate-400 flex items-center gap-1.5">
              <span className="inline-block w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              Auto-refreshes every 5 s
            </span>
          </div>
          <ChangeList />
        </section>

        {/* Timeline panel */}
        {selectedChangeId && (
          <section>
            <ChangeTimeline
              changeId={selectedChangeId}
              onClose={() => setSelectedChangeId(null)}
            />
          </section>
        )}
      </main>

      <footer className="border-t border-slate-200 bg-white mt-auto">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between text-xs text-slate-400">
          <span>ChangeOps — Change Management Platform</span>
          <span>&copy; {new Date().getFullYear()}</span>
        </div>
      </footer>
    </div>
  )
}

function StatCard({
  label,
  value,
  valueClass,
  bg,
  border,
}: {
  label: string
  value: number
  valueClass: string
  bg: string
  border: string
}) {
  return (
    <div className={`rounded-xl border ${border} ${bg} px-5 py-4 shadow-sm`}>
      <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
        {label}
      </p>
      <p className={`text-3xl font-bold mt-1 ${valueClass}`}>{value}</p>
    </div>
  )
}
