<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getDailyMemory, getMemorySummary, listDailyMemory, type MemoryDaily } from '../api/memory'

const summary = ref('')
const dates = ref<string[]>([])
const selectedDaily = ref<MemoryDaily | null>(null)
const loading = ref(false)
const error = ref('')

onMounted(loadMemory)

async function loadMemory() {
  loading.value = true
  error.value = ''

  try {
    const [summaryResult, dailyResult] = await Promise.all([getMemorySummary(), listDailyMemory()])
    summary.value = summaryResult.content
    dates.value = dailyResult.items

    if (dailyResult.items[0]) {
      selectedDaily.value = await getDailyMemory(dailyResult.items[0])
    }
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '记忆加载失败'
  } finally {
    loading.value = false
  }
}

async function selectDate(date: string) {
  loading.value = true
  error.value = ''

  try {
    selectedDaily.value = await getDailyMemory(date)
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '每日记忆加载失败'
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
      <div v-if="dates.length" class="memory-date-list">
        <button
          v-for="date in dates"
          :key="date"
          class="memory-date-item"
          type="button"
          @click="selectDate(date)"
        >
          {{ date }}
        </button>
      </div>
      <p v-else class="panel-muted">暂无每日记忆。</p>
      <p v-if="selectedDaily" class="panel-pre">{{ selectedDaily.date }}
{{ selectedDaily.content || '无内容。' }}</p>
    </div>

    <p v-if="error" class="panel-error">{{ error }}</p>
  </section>
</template>
