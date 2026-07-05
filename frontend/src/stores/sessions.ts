import { defineStore } from 'pinia'
import {
  createSession as createSessionApi,
  deleteSession as deleteSessionApi,
  listSessions,
  type ChatSession
} from '../api/chat'

interface SessionsState {
  sessions: ChatSession[]
  currentSessionId: string
  loading: boolean
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
    async createSession(title?: string) {
      this.error = ''
      const session = await createSessionApi(title)
      this.sessions = [session, ...this.sessions.filter((item) => item.id !== session.id)]
      this.currentSessionId = session.id
      return session
    },
    async deleteSession(sessionId: string) {
      this.error = ''
      await deleteSessionApi(sessionId)
      this.sessions = this.sessions.filter((session) => session.id !== sessionId)

      if (this.currentSessionId === sessionId) {
        this.currentSessionId = this.sessions[0]?.id ?? ''
      }
    }
  }
})
