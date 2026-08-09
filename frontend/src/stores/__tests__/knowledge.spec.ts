import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as knowledgeApi from '../../api/knowledge'
import { useKnowledgeStore } from '../knowledge'

function document(status: knowledgeApi.KnowledgeDocumentStatus): knowledgeApi.KnowledgeDocument {
  return {
    id: 'doc-1',
    originalFilename: 'guide.md',
    createdAt: '2026-08-09T12:18:00',
    contentType: 'text/markdown',
    sizeBytes: 12,
    status,
    parentCount: status === 'READY' ? 1 : 0,
    childCount: status === 'READY' ? 2 : 0,
    errorMessage: status === 'FAILED' ? 'parse failed' : null
  }
}

describe('knowledge store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('refreshes processing documents until they reach a terminal status after upload', async () => {
    vi.spyOn(knowledgeApi, 'uploadDocument').mockResolvedValue(document('PROCESSING'))
    vi.spyOn(knowledgeApi, 'listDocuments')
      .mockResolvedValueOnce([document('PROCESSING')])
      .mockResolvedValueOnce([document('READY')])
    const store = useKnowledgeStore()
    const file = new File(['# guide'], 'guide.md', { type: 'text/markdown' })

    await store.uploadDocument(file, { intervalMs: 0 })

    expect(knowledgeApi.uploadDocument).toHaveBeenCalledWith(file)
    expect(knowledgeApi.listDocuments).toHaveBeenCalledTimes(2)
    expect(store.documents).toEqual([document('READY')])
    expect(store.uploading).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('removes a deleted document from the list', async () => {
    vi.spyOn(knowledgeApi, 'listDocuments').mockResolvedValue([document('READY')])
    vi.spyOn(knowledgeApi, 'deleteDocument').mockResolvedValue(null)
    const store = useKnowledgeStore()
    await store.loadDocuments()

    await store.deleteDocument('doc-1')

    expect(knowledgeApi.deleteDocument).toHaveBeenCalledWith('doc-1')
    expect(store.documents).toEqual([])
  })
})
