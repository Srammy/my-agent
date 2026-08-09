import { defineStore } from 'pinia'
import {
  createSession as createSessionApi,
  deleteSession as deleteSessionApi,
  listSessions,
  renameSession as renameSessionApi,
  type ChatSession,
  type SessionMode
} from '../api/chat'
import { ApiError } from '../api/client'

interface SessionsState {
  sessions: ChatSession[]
  currentSessionId: string
  loading: boolean
  deletingSessionId: string
  renamingSessionId: string
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export const useSessionsStore = defineStore('sessions', {
  state: (): SessionsState => ({
    sessions: [],
    currentSessionId: '',
    loading: false,
    deletingSessionId: '',
    renamingSessionId: '',
    error: ''
  }),
  getters: {
    currentSession: (state) =>
      state.sessions.find((session) => session.id === state.currentSessionId) ?? null
  },
  actions: {
    selectSession(sessionId: string) {
      this.currentSessionId = sessionId
    },
    async loadSessions() {
      this.loading = true
      this.error = ''

      try {
        this.sessions = await listSessions()

        if (!this.sessions.some((session) => session.id === this.currentSessionId)) {
          this.currentSessionId = this.sessions[0]?.id ?? ''
        }
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      } finally {
        this.loading = false
      }
    },
    async createSession(title?: string, mode: SessionMode = 'NORMAL') {
      this.error = ''
      try {
        const session = await createSessionApi(title, mode)
        this.sessions = [session, ...this.sessions.filter((item) => item.id !== session.id)]
        this.currentSessionId = session.id
        return session
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async renameSession(sessionId: string, title: string) {
      if (this.renamingSessionId) {
        const error = new Error('Session rename is already in progress')
        this.error = error.message
        throw error
      }

      this.error = ''
      this.renamingSessionId = sessionId

      try {
        const session = await renameSessionApi(sessionId, title)
        this.sessions = [
          session,
          ...this.sessions.filter((item) => item.id !== session.id)
        ].sort((left, right) => {
          const updatedAt =
            new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()

          if (updatedAt !== 0) {
            return updatedAt
          }

          return new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime()
        })
        return session
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      } finally {
        if (this.renamingSessionId === sessionId) {
          this.renamingSessionId = ''
        }
      }
    },
    async deleteSession(sessionId: string) {
      if (this.deletingSessionId) {
        const error = new Error('Session deletion is already in progress')
        this.error = error.message
        throw error
      }

      this.error = ''
      this.deletingSessionId = sessionId
      const locallyKnown = this.sessions.some((session) => session.id === sessionId)

      try {
        try {
          await deleteSessionApi(sessionId)
        } catch (error) {
          if (!(locallyKnown && error instanceof ApiError && error.status === 404)) {
            this.error = errorMessage(error)
            throw error
          }
        }

        this.sessions = this.sessions.filter((session) => session.id !== sessionId)

        if (this.currentSessionId === sessionId) {
          this.currentSessionId = this.sessions[0]?.id ?? ''
        }
      } finally {
        if (this.deletingSessionId === sessionId) {
          this.deletingSessionId = ''
        }
      }
    }
  }
})
