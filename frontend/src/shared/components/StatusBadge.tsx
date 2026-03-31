// src/shared/components/StatusBadge.tsx
import clsx from 'clsx'
import type { ChangeStatus } from '@/features/changes/types'

const variants: Record<ChangeStatus, string> = {
  DRAFT: 'bg-slate-100 text-slate-600 border-slate-200',
  PREPARED: 'bg-blue-50 text-blue-700 border-blue-200',
  COMPLETED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  FAILED: 'bg-red-50 text-red-700 border-red-200',
  CANCELLED: 'bg-amber-50 text-amber-700 border-amber-200',
}

const dotColors: Record<ChangeStatus, string> = {
  DRAFT: 'bg-slate-400',
  PREPARED: 'bg-blue-500',
  COMPLETED: 'bg-emerald-500',
  FAILED: 'bg-red-500',
  CANCELLED: 'bg-amber-500',
}

const labels: Record<ChangeStatus, string> = {
  DRAFT: 'Draft',
  PREPARED: 'Prepared',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

interface Props {
  status: ChangeStatus
  size?: 'sm' | 'md'
}

export function StatusBadge({ status, size = 'md' }: Props) {
  const dot = dotColors[status] ?? 'bg-slate-400'
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 rounded-full border font-medium',
        size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-sm',
        variants[status] ?? 'bg-slate-100 text-slate-600 border-slate-200',
      )}
    >
      <span
        className={clsx(
          'rounded-full shrink-0',
          dot,
          size === 'sm' ? 'w-1.5 h-1.5' : 'w-2 h-2',
        )}
      />
      {labels[status] ?? status}
    </span>
  )
}
