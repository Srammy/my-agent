import { defineStore } from 'pinia'
import { TOKEN_KEY } from '../api/client'
import {
  login as loginApi,
  me as meApi,
  register as registerApi,
  type CurrentUser,
  type LoginPayload,
  type RegisterPayload
} from '../api/auth'

interface AuthState {
  token: string
  user: CurrentUser | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) ?? '',
    user: null
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user)
  },
  actions: {
    setToken(token: string) {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },
    async login(payload: LoginPayload) {
      const response = await loginApi(payload)
      this.setToken(response.token)
      await this.loadMe()
    },
    async register(payload: RegisterPayload) {
      const response = await registerApi(payload)
      this.setToken(response.token)
      await this.loadMe()
    },
    async loadMe() {
      if (!this.token) {
        this.user = null
        return null
      }

      this.user = await meApi()
      return this.user
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
