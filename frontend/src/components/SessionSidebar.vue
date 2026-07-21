<script setup lang="ts">
import type { ChatSession } from '../api/chat'

defineProps<{
  sessions: ChatSession[]
  currentSessionId: string
  loading: boolean
  deletingSessionId: string
  cancellingSessionIds: Record<string, true>
}>()

const emit = defineEmits<{
  create: []
  select: [sessionId: string]
  delete: [sessionId: string]
}>()

function formatDate(value: string) {
  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<template>
  <aside class="session-sidebar">
    <div class="session-sidebar__header">
      <div>
        <strong>会话</strong>
        <span>{{ sessions.length }} 个</span>
      </div>
      <el-button size="small" type="primary" :loading="loading" @click="emit('create')">
        新建
      </el-button>
    </div>

    <div v-if="loading && !sessions.length" class="session-sidebar__empty">正在加载会话</div>
    <div v-else-if="!sessions.length" class="session-sidebar__empty">暂无会话</div>

    <nav v-else class="session-list" aria-label="会话列表">
      <button
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ 'session-item--active': session.id === currentSessionId }"
        type="button"
        @click="emit('select', session.id)"
      >
        <span class="session-item__main">
          <strong>{{ session.title || '新会话' }}</strong>
          <small>{{ formatDate(session.updatedAt) }}</small>
        </span>
        <el-button
          class="session-item__delete"
          size="small"
          text
          type="danger"
          :disabled="deletingSessionId === session.id"
          :loading="deletingSessionId === session.id"
          @click.stop="emit('delete', session.id)"
        >
          {{ cancellingSessionIds[session.id] ? '重试删除' : '删除' }}
        </el-button>
      </button>
    </nav>
  </aside>
</template>
