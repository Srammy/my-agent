import { apiGet, apiPost } from './client'

export interface SkillReview {
  skillName: string
  description: string
  status: string          // 'PENDING' | 'APPROVED' | 'REJECTED'
  createdBy: string
  sourceSessionId?: string
  environments: string[]
  useCount: number
  viewCount: number
  patchCount: number
}

export interface ApproveRequest {
  environments: string[]
}

export interface RejectRequest {
  reason: string
}

export function listSkillReviews() {
  return apiGet<SkillReview[]>('/api/skill-reviews')
}

export function approveSkillReview(skillName: string, environments: string[]) {
  return apiPost<SkillReview>(`/api/skill-reviews/${encodeURIComponent(skillName)}/approve`, { environments } as ApproveRequest)
}

export function rejectSkillReview(skillName: string, reason: string) {
  return apiPost<SkillReview>(`/api/skill-reviews/${encodeURIComponent(skillName)}/reject`, { reason } as RejectRequest)
}
