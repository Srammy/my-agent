import { apiGet, apiPut } from './client'

export const permissionModes = [
  'DEFAULT',
  'EXPLORE',
  'ACCEPT_EDITS',
  'DONT_ASK',
  'BYPASS'
] as const

export type PermissionMode = (typeof permissionModes)[number]

export interface PermissionModeDto {
  mode: PermissionMode
}

export function getPermissionMode(sessionId: string) {
  return apiGet<PermissionModeDto>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/permission-mode`
  )
}

export function setPermissionMode(sessionId: string, mode: PermissionMode) {
  return apiPut<PermissionModeDto>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/permission-mode`,
    { mode }
  )
}
