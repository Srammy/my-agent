import { defineStore } from 'pinia'
import {
  applyEvolutionProposal,
  approveEvolutionProposal,
  listEvolutionProposals,
  rejectEvolutionProposal,
  type EvolutionProposal
} from '../api/evolution'

interface EvolutionState {
  proposals: EvolutionProposal[]
  loading: boolean
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function replaceProposal(items: EvolutionProposal[], proposal: EvolutionProposal) {
  return items.map((item) => (item.id === proposal.id ? proposal : item))
}

export const useEvolutionStore = defineStore('evolution', {
  state: (): EvolutionState => ({
    proposals: [],
    loading: false,
    error: ''
  }),
  actions: {
    async loadProposals() {
      this.loading = true
      this.error = ''

      try {
        this.proposals = await listEvolutionProposals()
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async approve(id: number) {
      this.error = ''
      const proposal = await approveEvolutionProposal(id)
      this.proposals = replaceProposal(this.proposals, proposal)
    },
    async reject(id: number) {
      this.error = ''
      const proposal = await rejectEvolutionProposal(id)
      this.proposals = replaceProposal(this.proposals, proposal)
    },
    async apply(id: number) {
      this.error = ''
      const proposal = await applyEvolutionProposal(id)
      this.proposals = replaceProposal(this.proposals, proposal)
    }
  }
})
