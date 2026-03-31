import { describe, it, expect, beforeEach } from 'vitest'
import { useChangesStore } from './useChangesStore'
import type { Change, PageResponse } from '../types'

const fakeChange: Change = {
  changeId: 'id-1',
  title: 'Deploy v1',
  componentId: 'payment-service',
  status: 'PREPARED',
  correlationId: 'corr-1',
  createdAt: '2024-06-15T10:00:00Z',
  updatedAt: '2024-06-15T10:00:00Z',
}

const fakePage: PageResponse<Change> = {
  content: [fakeChange],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
  first: true,
  last: true,
}

describe('useChangesStore', () => {
  beforeEach(() => {
    useChangesStore.setState({
      page: null,
      currentPage: 0,
      selectedChangeId: null,
    })
  })

  it('setPage stores the page response', () => {
    useChangesStore.getState().setPage(fakePage)
    expect(useChangesStore.getState().page).toEqual(fakePage)
  })

  it('setCurrentPage updates the current page number', () => {
    useChangesStore.getState().setCurrentPage(3)
    expect(useChangesStore.getState().currentPage).toBe(3)
  })

  it('setSelectedChangeId sets and clears the selection', () => {
    useChangesStore.getState().setSelectedChangeId('id-1')
    expect(useChangesStore.getState().selectedChangeId).toBe('id-1')

    useChangesStore.getState().setSelectedChangeId(null)
    expect(useChangesStore.getState().selectedChangeId).toBeNull()
  })

  it('upsertChange replaces an existing change in the page', () => {
    useChangesStore.setState({ page: fakePage })
    const updated: Change = { ...fakeChange, status: 'COMPLETED' }

    useChangesStore.getState().upsertChange(updated)

    const content = useChangesStore.getState().page!.content
    expect(content[0].status).toBe('COMPLETED')
  })

  it('upsertChange keeps other changes unchanged', () => {
    const otherChange: Change = {
      ...fakeChange,
      changeId: 'id-2',
      title: 'Deploy v2',
    }
    useChangesStore.setState({
      page: { ...fakePage, content: [fakeChange, otherChange] },
    })

    useChangesStore.getState().upsertChange({ ...fakeChange, status: 'FAILED' })

    const content = useChangesStore.getState().page!.content
    expect(content[0].status).toBe('FAILED')
    expect(content[1].title).toBe('Deploy v2')
    expect(content[1].status).toBe('PREPARED')
  })

  it('upsertChange is a no-op when page is null', () => {
    useChangesStore.getState().upsertChange(fakeChange)
    expect(useChangesStore.getState().page).toBeNull()
  })
})
