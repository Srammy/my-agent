import { apiGet, apiPost } from './client'

export interface AuthResponse {
  token: string
}

export interface CurrentUser {
  id: number
  username: string
  role: string
}

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload extends LoginPayload {
  displayName?: string
}

export function login(payload: LoginPayload) {
  return apiPost<AuthResponse>('/api/auth/login', payload)
}

export function register(payload: RegisterPayload) {
  return apiPost<AuthResponse>('/api/auth/register', payload)
}

export function me() {
  return apiGet<CurrentUser>('/api/auth/me')
}
