// src/features/changes/components/ChangeForm.tsx
import { useState, type FormEvent } from 'react'
import { useCreateChange } from '../hooks/useCreateChange'
import type { CreateChangePayload } from '../types'

interface Props {
  onSuccess?: (changeId: string) => void
}

// requestedBy is pre-filled from localStorage in local/dev mode.
// In production this will be populated from the authenticated JWT subject (see ROADMAP.md § 2.2).
const EMPTY: CreateChangePayload = {
  title: '',
  description: '',
  componentId: '',
  requestedBy: localStorage.getItem('user_id') ?? 'dev-user-001',
  scheduledAt: '',
}

export function ChangeForm({ onSuccess }: Props) {
  const [form, setForm] = useState<CreateChangePayload>(EMPTY)
  const { create, loading, error } = useCreateChange()

  const set = (field: keyof CreateChangePayload) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, [field]: e.target.value }))

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    // Convert local datetime to ISO-8601 with timezone
    const payload: CreateChangePayload = {
      ...form,
      scheduledAt: new Date(form.scheduledAt).toISOString(),
    }
    const result = await create(payload)
    if (result) {
      setForm(EMPTY)
      onSuccess?.(result.changeId)
    }
  }

  const fieldError = (name: string) => error?.fields?.[name]

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5">
      {/* Global API error (non-field) */}
      {error && !error.fields && (
        <div className="rounded-md bg-red-50 border border-red-300 px-4 py-3 text-sm text-red-700">
          <strong>{error.title}:</strong> {error.detail}
        </div>
      )}

      {/* Title */}
      <div>
        <label htmlFor="title" className="block text-sm font-medium text-gray-700 mb-1">
          Title <span className="text-red-500">*</span>
        </label>
        <input
          id="title"
          type="text"
          value={form.title}
          onChange={set('title')}
          required
          maxLength={255}
          placeholder="Deploy payment-service v3.0"
          className={inputClass(!!fieldError('title'))}
        />
        {fieldError('title') && <FieldError msg={fieldError('title')!} />}
      </div>

      {/* Description */}
      <div>
        <label htmlFor="description" className="block text-sm font-medium text-gray-700 mb-1">
          Description
        </label>
        <textarea
          id="description"
          value={form.description}
          onChange={set('description')}
          rows={3}
          maxLength={2000}
          placeholder="Describe the change..."
          className={inputClass(false)}
        />
      </div>

      {/* Component ID */}
      <div>
        <label htmlFor="componentId" className="block text-sm font-medium text-gray-700 mb-1">
          Component ID <span className="text-red-500">*</span>
        </label>
        <input
          id="componentId"
          type="text"
          value={form.componentId}
          onChange={set('componentId')}
          required
          maxLength={100}
          placeholder="payment-service"
          className={inputClass(!!fieldError('componentId'))}
        />
        {fieldError('componentId') && <FieldError msg={fieldError('componentId')!} />}
      </div>

      {/* Scheduled At */}
      <div>
        <label htmlFor="scheduledAt" className="block text-sm font-medium text-gray-700 mb-1">
          Scheduled At <span className="text-red-500">*</span>
        </label>
        <input
          id="scheduledAt"
          type="datetime-local"
          value={form.scheduledAt}
          onChange={set('scheduledAt')}
          required
          min={new Date().toISOString().slice(0, 16)}
          className={inputClass(!!fieldError('scheduledAt'))}
        />
        {fieldError('scheduledAt') && <FieldError msg={fieldError('scheduledAt')!} />}
      </div>

      {/* Submit */}
      <div className="flex justify-end pt-2">
        <button
          type="submit"
          disabled={loading}
          className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-5 py-2.5 text-sm
                     font-semibold text-white shadow-sm hover:bg-blue-500 focus-visible:outline
                     focus-visible:outline-2 focus-visible:outline-offset-2
                     focus-visible:outline-blue-600 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading && <Spinner />}
          {loading ? 'Creating…' : 'Create Change'}
        </button>
      </div>
    </form>
  )
}

function FieldError({ msg }: { msg: string }) {
  return <p className="mt-1 text-xs text-red-600">{msg}</p>
}

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
      <path className="opacity-75" fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
    </svg>
  )
}

function inputClass(hasError: boolean) {
  return [
    'block w-full rounded-md border px-3 py-2 text-sm shadow-sm',
    'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500',
    hasError ? 'border-red-400 bg-red-50' : 'border-gray-300 bg-white',
  ].join(' ')
}
