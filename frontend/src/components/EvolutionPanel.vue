<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { EvolutionProposal, EvolutionProposalStatus } from '../api/evolution'
import { useEvolutionStore } from '../stores/evolution'

const evolution = useEvolutionStore()
const proposals = computed(() => evolution.proposals)

onMounted(() => {
  evolution.loadProposals()
})

function tagType(status: EvolutionProposalStatus) {
  if (status === 'APPLIED') {
    return 'success'
  }

  if (status === 'APPROVED') {
    return 'warning'
  }

  if (status === 'REJECTED') {
    return 'danger'
  }

  return 'info'
}

function canApprove(proposal: EvolutionProposal) {
  return proposal.status === 'DRAFT'
}

function canApply(proposal: EvolutionProposal) {
  return proposal.status === 'APPROVED'
}
</script>

<template>
  <section class="assistant-panel-section" v-loading="evolution.loading">
    <div class="panel-row">
      <div>
        <strong>进化提案</strong>
        <p>查看聊天流产生的改进提案，并执行审核动作。</p>
      </div>
      <el-button size="small" @click="evolution.loadProposals">刷新</el-button>
    </div>

    <div v-if="!proposals.length" class="panel-muted">暂无提案。</div>
    <article v-for="proposal in proposals" :key="proposal.id" class="proposal-item">
      <div class="panel-row">
        <strong>{{ proposal.title || proposal.type }}</strong>
        <el-tag size="small" :type="tagType(proposal.status)">{{ proposal.status }}</el-tag>
      </div>
      <p>{{ proposal.summary || proposal.content || '无摘要。' }}</p>
      <div class="proposal-actions">
        <el-button size="small" :disabled="!canApprove(proposal)" @click="evolution.approve(proposal.id)">
          approve
        </el-button>
        <el-button size="small" :disabled="!canApprove(proposal)" @click="evolution.reject(proposal.id)">
          reject
        </el-button>
        <el-button
          size="small"
          type="primary"
          :disabled="!canApply(proposal)"
          @click="evolution.apply(proposal.id)"
        >
          apply
        </el-button>
      </div>
    </article>

    <p v-if="evolution.error" class="panel-error">{{ evolution.error }}</p>
  </section>
</template>
