<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { ChatSession } from '../api/chat'

defineProps<{
  sessions: ChatSession[]
  currentSessionId: string
  loading: boolean
  deletingSessionId: string
  renamingSessionId: string
  cancellingSessionIds: Record<string, true>
}>()

const emit = defineEmits<{
  create: []
  select: [sessionId: string]
  delete: [sessionId: string]
  rename: [sessionId: string, title: string]
}>()

const editingSessionId = ref('')
const draftTitle = ref('')
const titleInput = ref<HTMLInputElement | null>(null)

function setTitleInput(element: unknown) {
  titleInput.value = element instanceof HTMLInputElement ? element : null
}

function startRename(session: ChatSession) {
  editingSessionId.value = session.id
  draftTitle.value = session.title
  nextTick(() => titleInput.value?.focus())
}

function saveRename(sessionId: string) {
  emit('rename', sessionId, draftTitle.value)
  editingSessionId.value = ''
  draftTitle.value = ''
}

function cancelRename() {
  editingSessionId.value = ''
  draftTitle.value = ''
}

function selectFromKeyboard(event: KeyboardEvent, sessionId: string) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    emit('select', sessionId)
  }
}

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
      <el-button size="small" type="primary" text :loading="loading" @click="emit('create')">
        新建
      </el-button>
    </div>

    <div v-if="loading && !sessions.length" class="session-sidebar__empty">正在加载会话</div>
    <div v-else-if="!sessions.length" class="session-sidebar__empty">暂无会话</div>

    <nav v-else class="session-list" aria-label="会话列表">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ 'session-item--active': session.id === currentSessionId }"
        role="button"
        tabindex="0"
        @click="emit('select', session.id)"
        @keydown="selectFromKeyboard($event, session.id)"
      >
        <span class="session-item__main">
          <input
            v-if="editingSessionId === session.id"
            :ref="setTitleInput"
            v-model="draftTitle"
            class="session-item__rename-input"
            @click.stop
            @keyup.enter.stop.prevent="saveRename(session.id)"
            @keyup.esc.stop.prevent="cancelRename"
          />
          <strong v-else>{{ session.title || '新会话' }}</strong>
          <small>{{ formatDate(session.updatedAt) }}</small>
        </span>
        <span class="session-item__actions">
          <el-button
            :data-testid="`rename-session-${session.id}`"
            class="session-item__rename"
            size="small"
            text
            :disabled="Boolean(deletingSessionId) || Boolean(renamingSessionId)"
            :loading="renamingSessionId === session.id"
            @click.stop="startRename(session)"
          >
            重命名
          </el-button>
          <el-button
            :data-testid="`delete-session-${session.id}`"
            class="session-item__delete"
            size="small"
            text
            type="danger"
            :disabled="Boolean(deletingSessionId)"
            :loading="deletingSessionId === session.id"
            @click.stop="emit('delete', session.id)"
          >
            {{ cancellingSessionIds[session.id] ? '重试删除' : '删除' }}
          </el-button>
        </span>
      </div>
    </nav>
  </aside>
</template>
