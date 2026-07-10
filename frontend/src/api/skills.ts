import { ApiError, apiDelete, apiGet, TOKEN_KEY } from './client'

export interface Skill {
  name: string
  description: string
}

export function listMySkills(): Promise<Skill[]> {
  return apiGet<Skill[]>('/api/skills/mine')
}

export async function uploadSkill(files: File[]): Promise<Skill> {
  const formData = new FormData()
  for (const file of files) {
    formData.append(file.name, file)
  }
  const token = localStorage.getItem(TOKEN_KEY)
  const headers = new Headers()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch('/api/skills/mine', { method: 'POST', headers, body: formData })
  const data = await response.json()
  if (!response.ok) {
    const message =
      data && typeof data === 'object' && 'message' in data
        ? String((data as { message?: unknown }).message)
        : '上传失败，请稍后重试'
    throw new ApiError(message, response.status, data)
  }
  return data as Skill
}

export function deleteMySkill(skillName: string): Promise<null> {
  return apiDelete<null>(`/api/skills/mine/${encodeURIComponent(skillName)}`)
}
