// src/app/routes/ChangesPage.tsx
import { useState } from 'react'
import { ChangeForm } from '@/features/changes/components/ChangeForm'
import { ChangeList } from '@/features/changes/components/ChangeList'
import { ChangeTimeline } from '@/features/changes/components/ChangeTimeline'
import { useChangesStore } from '@/features/changes/store/useChangesStore'

export function ChangesPage() {
  const [showForm, setShowForm] = useState(false)
  const [successMsg, setSuccessMsg] = useState<string | null>(null)
  const { selectedChangeId, setSelectedChangeId } = useChangesStore()

  const handleSuccess = (changeId: string) => {
    setShowForm(false)
    setSuccessMsg(`Change ${changeId.slice(0, 8)}… created successfully.`)
    setTimeout(() => setSuccessMsg(null), 5_000)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200 shadow-sm">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-blue-600 flex items-center justify-center">
              <span className="text-white text-xs font-bold">CO</span>
            </div>
            <h1 className="text-xl font-semibold text-gray-900">ChangeOps</h1>
            <span className="text-gray-300">|</span>
            <span className="text-sm text-gray-500">Change Management Dashboard</span>
          </div>
          <button
            onClick={() => setShowForm((v) => !v)}
            className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm
                       font-semibold text-white shadow-sm hover:bg-blue-500 transition-colors"
          >
            {showForm ? '✕ Cancel' : '+ New Change'}
          </button>
        </div>
      </header>

      <main className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">

        {/* Success banner */}
        {successMsg && (
          <div className="rounded-md bg-green-50 border border-green-300 px-4 py-3 text-sm text-green-800 flex items-center justify-between">
            ✅ {successMsg}
            <button onClick={() => setSuccessMsg(null)} className="text-green-600 hover:text-green-800">×</button>
          </div>
        )}

        {/* Create form */}
        {showForm && (
          <section className="rounded-lg border border-gray-200 bg-white shadow-sm p-6">
            <h2 className="text-base font-semibold text-gray-900 mb-5">New Change Request</h2>
            <ChangeForm onSuccess={handleSuccess} />
          </section>
        )}

        {/* Changes list */}
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-semibold text-gray-900">Changes</h2>
            <span className="text-xs text-gray-400">Auto-refreshes every 5 s</span>
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
    </div>
  )
}
