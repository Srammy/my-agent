import { ApiError, apiDelete, apiGet, apiPost, apiPut, TOKEN_KEY } from './client'

export interface Skill {
  id: number
  name: string
  description: string
  ownerType: string
  enabled: boolean
  editable: boolean
  updatedAt: string
}

export interface SkillFile {
  path: string
  content: string
  contentType: string
  executable: boolean
  updatedAt: string
}

export interface SkillCreatePayload {
  name: string
  description: string
}

function encodeFilePath(path: string) {
  return path
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')
}

async function textRequest<T>(method: 'PUT' | 'DELETE', path: string, body?: string): Promise<T> {
  const headers = new Headers()
  const token = localStorage.getItem(TOKEN_KEY)

  if (body !== undefined) {
    headers.set('Content-Type', 'text/plain')
  }

  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, { method, headers, body })
  const text = await response.text()
  let data: unknown = null

  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text || null
  }

  if (!response.ok) {
    const message =
      data && typeof data === 'object' && 'message' in data
        ? String((data as { message?: unknown }).message)
        : '请求失败，请稍后重试'
    throw new ApiError(message, response.status, data)
  }

  return data as T
}

export function listSystemSkills() {
  return apiGet<Skill[]>('/api/skills/system')
}

export function listMySkills() {
  return apiGet<Skill[]>('/api/skills/mine')
}

export function createMySkill(payload: SkillCreatePayload) {
  return apiPost<Skill>('/api/skills/mine', payload)
}

export function updateMySkill(skillId: number, payload: SkillCreatePayload) {
  return apiPut<Skill>(`/api/skills/mine/${skillId}`, payload)
}

export function deleteMySkill(skillId: number) {
  return apiDelete<null>(`/api/skills/mine/${skillId}`)
}

export function listSkillFiles(skillId: number) {
  return apiGet<SkillFile[]>(`/api/skills/${skillId}/files`)
}

export function upsertSkillFile(skillId: number, path: string, content: string) {
  return textRequest<SkillFile>(
    'PUT',
    `/api/skills/${skillId}/files/${encodeFilePath(path)}`,
    content
  )
}

export function deleteSkillFile(skillId: number, path: string) {
  return textRequest<null>('DELETE', `/api/skills/${skillId}/files/${encodeFilePath(path)}`)
}

export function setSkillEnabled(skillId: number, enabled: boolean) {
  return apiPut<Skill>(`/api/skills/${skillId}/enabled`, { enabled })
}
