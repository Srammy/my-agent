import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteDocument, listDocuments, retryDocument, uploadDocument, type KnowledgeDocument } from '../knowledge'

const document: KnowledgeDocument = {
  id: 'doc-1',
  originalFilename: 'guide.md',
  createdAt: '2026-08-09T12:18:00',
  contentType: 'text/markdown',
  sizeBytes: 12,
  status: 'PROCESSING',
  parentCount: 0,
  childCount: 0,
  errorMessage: null
}

describe('knowledge document API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('uploads a document as multipart form data with the auth token', async () => {
    localStorage.setItem('myagent.token', 'token-1')
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(document), {
        status: 202,
        headers: { 'Content-Type': 'application/json' }
      })
    )
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['# guide'], 'guide.md', { type: 'text/markdown' })

    await expect(uploadDocument(file)).resolves.toEqual(document)

    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/knowledge/documents')
    expect(options.method).toBe('POST')
    expect(options.headers.get('Authorization')).toBe('Bearer token-1')
    expect(options.headers.get('Content-Type')).toBeNull()
    expect((options.body as FormData).get('file')).toBeInstanceOf(File)
  })

  it('lists the current user knowledge documents', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([document]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listDocuments()).resolves.toEqual([document])
    expect(fetchMock).toHaveBeenCalledWith('/api/knowledge/documents', expect.objectContaining({ method: 'GET' }))
  })

  it('deletes a knowledge document', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(deleteDocument('doc-1')).resolves.toBeNull()

    expect(fetchMock).toHaveBeenCalledWith('/api/knowledge/documents/doc-1', expect.objectContaining({ method: 'DELETE' }))
  })

  it('retries a failed knowledge document', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...document, status: 'PROCESSING' }), {
        status: 202,
        headers: { 'Content-Type': 'application/json' }
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(retryDocument('doc-1')).resolves.toMatchObject({ status: 'PROCESSING' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/knowledge/documents/doc-1/retry',
      expect.objectContaining({ method: 'POST' })
    )
  })
})
