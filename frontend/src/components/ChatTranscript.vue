<script setup lang="ts">
import type { ChatMessage } from '../stores/chat'
import ToolEventCard from './ToolEventCard.vue'

defineProps<{
  messages: ChatMessage[]
  loading: boolean
  hasSession: boolean
}>()

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
</script>

<template>
  <section class="chat-transcript" aria-label="聊天记录">
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
        <div v-if="message.events.length" class="message-events">
          <ToolEventCard v-for="event in message.events" :key="event.id" :event="event" />
        </div>
      </div>
    </article>

    <div v-if="loading" class="chat-transcript__status">响应生成中</div>
  </section>
</template>
