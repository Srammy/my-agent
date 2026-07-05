import { apiGet, apiPost } from './client'

export type EvolutionProposalType = 'SKILL' | 'MEMORY' | 'TOOL_POLICY' | 'PROMPT' | 'CODE_PATCH'
export type EvolutionProposalStatus = 'DRAFT' | 'APPROVED' | 'REJECTED' | 'APPLIED'

export interface EvolutionProposal {
  id: number
  sessionId: string
  type: EvolutionProposalType
  title: string
  summary: string
  content: string
  status: EvolutionProposalStatus
  createdAt: string
  updatedAt: string
  appliedAt: string | null
}

export interface EvolutionCreateRequest {
  sessionId: string
  type: EvolutionProposalType
  title: string
  summary: string
  content: string
}

export function listEvolutionProposals() {
  return apiGet<EvolutionProposal[]>('/api/evolution/proposals')
}

export function createEvolutionProposal(payload: EvolutionCreateRequest) {
  return apiPost<EvolutionProposal>('/api/evolution/proposals', payload)
}

export function approveEvolutionProposal(id: number) {
  return apiPost<EvolutionProposal>(`/api/evolution/proposals/${id}/approve`)
}

export function rejectEvolutionProposal(id: number) {
  return apiPost<EvolutionProposal>(`/api/evolution/proposals/${id}/reject`)
}

export function applyEvolutionProposal(id: number) {
  return apiPost<EvolutionProposal>(`/api/evolution/proposals/${id}/apply`)
}
