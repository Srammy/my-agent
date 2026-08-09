import { defineStore } from 'pinia'
import {
  listDocuments,
  deleteDocument as deleteDocumentApi,
  uploadDocument as uploadDocumentApi,
  type KnowledgeDocument
} from '../api/knowledge'

interface PollingOptions {
  intervalMs?: number
  maxAttempts?: number
}

interface KnowledgeState {
  documents: KnowledgeDocument[]
  loading: boolean
  uploading: boolean
  deletingId: string
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function wait(intervalMs: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, intervalMs))
}

export const useKnowledgeStore = defineStore('knowledge', {
  state: (): KnowledgeState => ({
    documents: [],
    loading: false,
    uploading: false,
    deletingId: '',
    error: ''
  }),
  getters: {
    processing: (state) => state.documents.some((document) => document.status === 'PROCESSING')
  },
  actions: {
    async loadDocuments() {
      this.loading = true
      this.error = ''

      try {
        this.documents = await listDocuments()
        return this.documents
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      } finally {
        this.loading = false
      }
    },
    async pollProcessing({ intervalMs = 1000, maxAttempts = 30 }: PollingOptions = {}) {
      let attempts = 0

      while (this.processing && attempts < maxAttempts) {
        if (intervalMs > 0) {
          await wait(intervalMs)
        }
        await this.loadDocuments()
        attempts += 1
      }

      return this.documents
    },
    async uploadDocument(file: File, options: PollingOptions = {}) {
      this.uploading = true
      this.error = ''

      try {
        const document = await uploadDocumentApi(file)
        this.documents = [
          document,
          ...this.documents.filter((item) => item.id !== document.id)
        ]
        await this.pollProcessing(options)
        return document
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      } finally {
        this.uploading = false
      }
    },
    async deleteDocument(documentId: string) {
      this.deletingId = documentId
      this.error = ''

      try {
        await deleteDocumentApi(documentId)
        this.documents = this.documents.filter((document) => document.id !== documentId)
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      } finally {
        this.deletingId = ''
      }
    }
  }
})
