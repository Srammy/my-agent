<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore, type ToolEvent } from '../stores/chat'

const props = defineProps<{
  event: ToolEvent
  sessionId: string
  messageId: string
}>()

const chat = useChatStore()

const isUserConfirmation = computed(
  () =>
    props.event.type === 'permission_required' &&
    props.event.kind === 'USER_CONFIRM' &&
    Boolean(props.event.confirmationId)
)

const confirmationToolName = computed(() =>
  isUserConfirmation.value ? props.event.toolName || props.event.permission : ''
)

const confirmationInput = computed(() =>
  isUserConfirmation.value ? props.event.toolInput : undefined
)

const confirmationDisabled = computed(
  () =>
    props.event.confirming ||
    props.event.consumed ||
    !props.sessionId ||
    !props.messageId
)

const title = computed(() => {
  switch (props.event.type) {
    case 'tool_call':
      return `调用工具${props.event.tool ? `：${props.event.tool}` : ''}`
    case 'tool_result':
      return `工具结果${props.event.tool ? `：${props.event.tool}` : ''}`
    case 'permission_required':
      return '需要权限'
    case 'evolution_proposal':
      return '进化建议'
    case 'error':
      return '错误'
    default:
      return '事件'
  }
})

const payload = computed(() => {
  if (props.event.type === 'tool_call') {
    return props.event.input
  }

  if (props.event.type === 'tool_result') {
    return props.event.output
  }

  return null
})

const text = computed(() => {
  if (props.event.type === 'permission_required') {
    return props.event.permission || '需要额外权限才能继续'
  }

  if (props.event.type === 'evolution_proposal') {
    return props.event.summary || '收到一条进化建议'
  }

  if (props.event.type === 'error') {
    return props.event.message || '处理过程中出现错误'
  }

  return ''
})

const formattedPayload = computed(() => {
  const value = payload.value ?? confirmationInput.value

  if (value === null || value === undefined) {
    return ''
  }

  if (typeof value === 'string') {
    return value
  }

  return JSON.stringify(value, null, 2)
})

function confirm(confirmed: boolean) {
  void chat.confirmTool(props.sessionId, props.messageId, props.event, confirmed)
}
</script>

<template>
  <div class="tool-event" :class="`tool-event--${event.type}`">
    <div class="tool-event__title">{{ title }}</div>
    <p v-if="text" class="tool-event__text">{{ text }}</p>
    <div v-if="confirmationToolName" class="tool-event__tool-name">{{ confirmationToolName }}</div>
    <pre v-if="formattedPayload" class="tool-event__payload">{{ formattedPayload }}</pre>
    <div v-if="isUserConfirmation" class="tool-event__actions">
      <el-button :disabled="confirmationDisabled" @click="confirm(true)">允许一次</el-button>
      <el-button :disabled="confirmationDisabled" @click="confirm(false)">拒绝一次</el-button>
    </div>
  </div>
</template>
