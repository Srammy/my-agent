<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ChatMessage } from '../stores/chat'
import ToolEventCard from './ToolEventCard.vue'

const NEAR_BOTTOM_THRESHOLD = 80

const props = defineProps<{
  messages: ChatMessage[]
  loading: boolean
  hasSession: boolean
  sessionId: string
}>()

const transcript = ref<HTMLElement | null>(null)
const isNearBottom = ref(true)

const scrollSignature = computed(() =>
  props.messages
    .map((message) => [
      message.id,
      message.content.length,
      message.events.length,
      message.loading ? 1 : 0
    ].join(':'))
    .join('|')
)

function roleLabel(role: ChatMessage['role']) {
  if (role === 'user') {
    return '你'
  }

  if (role === 'assistant') {
    return 'Assistant'
  }

  if (role === 'tool') {
    return '工具'
  }

  return '系统'
}

function updateNearBottom() {
  const element = transcript.value

  if (!element) {
    isNearBottom.value = true
    return
  }

  const distance = element.scrollHeight - element.scrollTop - element.clientHeight
  isNearBottom.value = distance <= NEAR_BOTTOM_THRESHOLD
}

async function scrollToBottom() {
  await nextTick()
  const element = transcript.value

  if (element) {
    element.scrollTop = element.scrollHeight
    updateNearBottom()
  }
}

function handleScroll() {
  updateNearBottom()
}

function visibleEvents(message: ChatMessage) {
  return message.events.filter(
    (event) => event.type !== 'tool_call' && event.type !== 'tool_result'
  )
}

watch(
  () => props.sessionId,
  () => {
    isNearBottom.value = true
    scrollToBottom()
  }
)

watch(
  [scrollSignature, () => props.loading, () => props.hasSession],
  () => {
    if (isNearBottom.value) {
      scrollToBottom()
    }
  },
  { flush: 'post' }
)
</script>

<template>
  <section ref="transcript" class="chat-transcript" aria-label="聊天记录" @scroll="handleScroll">
    <div v-if="!hasSession" class="chat-empty">
      <h1>创建会话后开始聊天</h1>
      <p>左侧新建一个会话，消息会以流式方式显示在这里。</p>
    </div>

    <div v-else-if="!messages.length" class="chat-empty">
      <h1>这个会话还没有消息</h1>
      <p>从底部输入问题，Assistant 会边生成边更新回答。</p>
    </div>

    <article
      v-for="message in messages"
      v-else
      :key="message.id"
      class="message-row"
      :class="`message-row--${message.role}`"
    >
      <div class="message-bubble">
        <div class="message-bubble__meta">{{ roleLabel(message.role) }}</div>
        <div v-if="message.content" class="message-bubble__content">{{ message.content }}</div>
        <div v-else-if="message.loading" class="message-bubble__content message-bubble__muted">
          正在思考...
        </div>
        <div v-if="visibleEvents(message).length" class="message-events">
          <ToolEventCard
            v-for="event in visibleEvents(message)"
            :key="event.id"
            :event="event"
            :session-id="sessionId"
            :message-id="message.id"
          />
        </div>
      </div>
    </article>

  </section>
</template>
