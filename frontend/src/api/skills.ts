import { ApiError, apiDelete, apiGet, apiPost, apiPut, TOKEN_KEY } from './client'

export interface Skill {
  name: string
  description: string
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
        : '璇锋眰澶辫触锛岃绋嶅悗閲嶈瘯'
    throw new ApiError(message, response.status, data)
  }

  return data as T
}

export function listMySkills() {
  return apiGet<Skill[]>('/api/skills/mine')
}

export function createMySkill(payload: SkillCreatePayload) {
  return apiPost<Skill>('/api/skills/mine', payload)
}

export function updateMySkill(skillName: string, payload: SkillCreatePayload) {
  return apiPut<Skill>(`/api/skills/mine/${encodeURIComponent(skillName)}`, payload)
}

export function deleteMySkill(skillName: string) {
  return apiDelete<null>(`/api/skills/mine/${encodeURIComponent(skillName)}`)
}

export function listSkillFiles(skillName: string) {
  return apiGet<SkillFile[]>(`/api/skills/${encodeURIComponent(skillName)}/files`)
}

export function upsertSkillFile(skillName: string, path: string, content: string) {
  return textRequest<SkillFile>(
    'PUT',
    `/api/skills/${encodeURIComponent(skillName)}/files/${encodeFilePath(path)}`,
    content
  )
}

export function deleteSkillFile(skillName: string, path: string) {
  return textRequest<null>(
    'DELETE',
    `/api/skills/${encodeURIComponent(skillName)}/files/${encodeFilePath(path)}`
  )
}
