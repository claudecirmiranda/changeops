// src/shared/components/StatusBadge.tsx
import clsx from 'clsx'
import type { ChangeStatus } from '@/features/changes/types'

const variants: Record<ChangeStatus, string> = {
  DRAFT:      'bg-gray-100 text-gray-700 border-gray-300',
  PREPARED:   'bg-blue-100 text-blue-800 border-blue-300',
  COMPLETED:  'bg-green-100 text-green-800 border-green-300',
  FAILED:     'bg-red-100 text-red-800 border-red-300',
  CANCELLED:  'bg-yellow-100 text-yellow-800 border-yellow-300',
}

const labels: Record<ChangeStatus, string> = {
  DRAFT:      'Draft',
  PREPARED:   'Prepared',
  COMPLETED:  'Completed',
  FAILED:     'Failed',
  CANCELLED:  'Cancelled',
}

interface Props {
  status: ChangeStatus
  size?: 'sm' | 'md'
}

export function StatusBadge({ status, size = 'md' }: Props) {
  return (
    <span
      className={clsx(
        'inline-flex items-center rounded-full border font-medium',
        size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-sm',
        variants[status] ?? 'bg-gray-100 text-gray-600 border-gray-300'
      )}
    >
      {labels[status] ?? status}
    </span>
  )
}
