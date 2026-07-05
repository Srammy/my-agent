<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMemorySummary, listDailyMemory } from '../api/memory'

const summary = ref('')
const dailyItems = ref<string[]>([])
const loading = ref(false)
const error = ref('')

onMounted(loadMemory)

async function loadMemory() {
  loading.value = true
  error.value = ''

  try {
    const [summaryResult, dailyResult] = await Promise.all([getMemorySummary(), listDailyMemory()])
    summary.value = summaryResult.content
    dailyItems.value = dailyResult.items
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '记忆加载失败'
  } finally {
    loading.value = false
  }
}

</script>

<template>
  <section class="assistant-panel-section" v-loading="loading">
    <div class="panel-block">
      <strong>长期记忆</strong>
      <p class="panel-pre">{{ summary || '暂无长期记忆。' }}</p>
    </div>

    <div class="panel-block">
      <strong>每日记忆</strong>
      <div v-if="dailyItems.length" class="memory-date-list">
        <p
          v-for="(item, index) in dailyItems"
          :key="`${index}-${item}`"
          class="memory-date-item"
        >
          {{ item }}
        </p>
      </div>
      <p v-else class="panel-muted">暂无每日记忆。</p>
    </div>

    <p v-if="error" class="panel-error">{{ error }}</p>
  </section>
</template>
