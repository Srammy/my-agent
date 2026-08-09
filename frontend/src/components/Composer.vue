<script setup lang="ts">
import { ref } from 'vue'
import PermissionPanel from './PermissionPanel.vue'
import type { SessionMode } from '../api/chat'

defineProps<{
  disabled: boolean
  hasSession: boolean
  sessionId: string
  mode?: SessionMode
}>()

const emit = defineEmits<{
  send: [message: string]
}>()

const draft = ref('')

function modeLabel(mode?: SessionMode) {
  return mode === 'KNOWLEDGE' ? '知识库问答' : '普通对话'
}

function send() {
  const message = draft.value.trim()

  if (!message) {
    return
  }

  emit('send', message)
  draft.value = ''
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) {
    return
  }

  event.preventDefault()
  send()
}
</script>

<template>
  <div class="composer-shell">
    <div class="composer__mode">{{ modeLabel(mode) }}</div>
    <form class="composer" @submit.prevent="send">
    <el-input
      v-model="draft"
      class="composer__input"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 5 }"
      :disabled="disabled || !hasSession"
      :placeholder="hasSession ? '输入消息，Enter 发送，Shift+Enter 换行' : '请先创建或选择会话'"
      resize="none"
      @keydown="onKeydown"
    />
    <PermissionPanel
      class="composer__permission"
      :session-id="sessionId"
      :compact="true"
    />
    <el-button
      class="composer__send"
      native-type="submit"
      type="primary"
      :disabled="disabled || !hasSession || !draft.trim()"
      :loading="disabled"
    >
      发送
    </el-button>
    </form>
  </div>
</template>
