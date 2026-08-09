import { ApiError, apiDelete, apiGet, apiPost, TOKEN_KEY } from './client'

export type KnowledgeDocumentStatus = 'PROCESSING' | 'READY' | 'FAILED'

export interface KnowledgeDocument {
  id: string
  originalFilename: string
  createdAt: string
  contentType: string
  sizeBytes: number | null
  status: KnowledgeDocumentStatus
  parentCount: number
  childCount: number
  errorMessage: string | null
}

export type KnowledgeDocumentDto = KnowledgeDocument

function resolveUploadErrorMessage(data: unknown, fallback: string) {
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>
    const message = record.message ?? record.error ?? record.detail
    if (typeof message === 'string' && message.trim()) {
      return message
    }
  }
  return fallback
}

async function parseUploadResponse(response: Response) {
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    try {
      return await response.json()
    } catch {
      return null
    }
  }

  const text = await response.text()
  if (!text) {
    return null
  }

  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

export function listDocuments(): Promise<KnowledgeDocument[]> {
  return apiGet<KnowledgeDocument[]>('/api/knowledge/documents')
}

export function deleteDocument(documentId: string): Promise<null> {
  return apiDelete<null>(`/api/knowledge/documents/${encodeURIComponent(documentId)}`)
}

export function retryDocument(documentId: string): Promise<KnowledgeDocument> {
  return apiPost<KnowledgeDocument>(`/api/knowledge/documents/${encodeURIComponent(documentId)}/retry`)
}

export async function uploadDocument(file: File): Promise<KnowledgeDocument> {
  const formData = new FormData()
  formData.append('file', file, file.name)

  const headers = new Headers()
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch('/api/knowledge/documents', {
    method: 'POST',
    headers,
    body: formData
  })
  const data = await parseUploadResponse(response)

  if (!response.ok) {
    throw new ApiError(
      resolveUploadErrorMessage(data, '上传失败，请稍后重试'),
      response.status,
      data
    )
  }

  return data as KnowledgeDocument
}
