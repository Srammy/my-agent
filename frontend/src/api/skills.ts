import { ApiError, apiDelete, apiGet, TOKEN_KEY } from './client'

export interface Skill {
  name: string
  description: string
}

export function listMySkills(): Promise<Skill[]> {
  return apiGet<Skill[]>('/api/skills/mine')
}

interface SkillUploadEntry {
  file: File
  path: string
}

function prepareSkillUpload(files: File[]): SkillUploadEntry[] {
  let rootDirectory = ''

  const entries = files.map((file) => {
    const segments = (file.webkitRelativePath || '').split('/')
    if (segments.length < 2 || segments.some((segment) => !segment)) {
      throw new Error('请选择一个完整的 Skill 目录')
    }

    const [currentRoot, ...relativeSegments] = segments
    if (!rootDirectory) {
      rootDirectory = currentRoot
    } else if (currentRoot !== rootDirectory) {
      throw new Error('一次只能上传一个 Skill 目录')
    }

    return { file, path: relativeSegments.join('/') }
  })

  if (!entries.some((entry) => entry.path === 'SKILL.md')) {
    throw new Error('所选目录根部必须包含 SKILL.md')
  }
  return entries
}

export async function uploadSkill(files: File[]): Promise<Skill> {
  const formData = new FormData()
  for (const entry of prepareSkillUpload(files)) {
    formData.append(entry.path, entry.file, entry.path)
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
