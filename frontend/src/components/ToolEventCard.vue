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

const confirmationLocked = computed(
  () =>
    props.event.confirming ||
    props.event.consumed ||
    !props.sessionId ||
    !props.messageId
)

const allDecided = computed(
  () =>
    Boolean(props.event.toolCalls?.length) &&
    props.event.toolCalls!.every(
      (tool) => typeof props.event.decisions?.[tool.toolCallId] === 'boolean'
    )
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

function formatValue(value: unknown) {
  if (value === null || value === undefined) {
    return ''
  }

  if (typeof value === 'string') {
    return value
  }

  return JSON.stringify(value, null, 2)
}

const formattedPayload = computed(() => formatValue(payload.value))
</script>

<template>
  <div class="tool-event" :class="`tool-event--${event.type}`">
    <div class="tool-event__title">{{ title }}</div>
    <p v-if="text" class="tool-event__text">{{ text }}</p>
    <pre v-if="formattedPayload" class="tool-event__payload">{{ formattedPayload }}</pre>
    <div v-if="isUserConfirmation" class="tool-event__confirmation-list">
      <div
        v-for="tool in event.toolCalls"
        :key="tool.toolCallId"
        class="tool-event__confirmation-item"
      >
        <div class="tool-event__tool-name">{{ tool.toolName }}</div>
        <pre class="tool-event__payload">{{ formatValue(tool.toolInput) }}</pre>
        <el-button
          :type="event.decisions?.[tool.toolCallId] === true ? 'primary' : 'default'"
          :disabled="confirmationLocked"
          @click="chat.setToolDecision(event, tool.toolCallId, true)"
        >允许</el-button>
        <el-button
          :type="event.decisions?.[tool.toolCallId] === false ? 'danger' : 'default'"
          :disabled="confirmationLocked"
          @click="chat.setToolDecision(event, tool.toolCallId, false)"
        >拒绝</el-button>
      </div>
      <el-button
        :disabled="confirmationLocked || !allDecided"
        @click="chat.confirmTool(sessionId, messageId, event)"
      >提交本组决策</el-button>
    </div>
  </div>
</template>

<style scoped>
.tool-event__confirmation-list {
  display: grid;
  gap: 12px;
}

.tool-event__confirmation-item .tool-event__payload {
  margin: 4px 0 8px;
}
</style>
