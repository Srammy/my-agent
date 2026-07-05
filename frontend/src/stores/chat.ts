import { defineStore } from 'pinia'
import { streamChat, type StreamEvent } from '../api/chat'

export type ChatRole = 'user' | 'assistant' | 'system' | 'tool'

export type ToolEventType =
  | 'tool_call'
  | 'tool_result'
  | 'permission_required'
  | 'evolution_proposal'
  | 'error'

export interface ToolEvent {
  id: string
  type: ToolEventType
  tool?: string
  input?: unknown
  output?: unknown
  permission?: string
  summary?: string
  message?: string
}

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  events: ToolEvent[]
  loading?: boolean
}

interface ChatState {
  messagesBySession: Record<string, ChatMessage[]>
  loadingSessionId: string
  error: string
}

let nextId = 0

function makeId(prefix: string) {
  nextId += 1
  return `${prefix}-${Date.now()}-${nextId}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '发送失败，请稍后重试'
}

function toToolEvent(event: StreamEvent): ToolEvent | null {
  if (
    event.type !== 'tool_call' &&
    event.type !== 'tool_result' &&
    event.type !== 'permission_required' &&
    event.type !== 'evolution_proposal' &&
    event.type !== 'error'
  ) {
    return null
  }

  return {
    id: makeId('event'),
    type: event.type,
    tool: typeof event.tool === 'string' ? event.tool : undefined,
    input: event.input,
    output: event.output,
    permission: typeof event.permission === 'string' ? event.permission : undefined,
    summary: typeof event.summary === 'string' ? event.summary : undefined,
    message: typeof event.message === 'string' ? event.message : undefined
  }
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    messagesBySession: {},
    loadingSessionId: '',
    error: ''
  }),
  getters: {
    isLoading: (state) => Boolean(state.loadingSessionId),
    messages:
      (state) =>
      (sessionId: string): ChatMessage[] =>
        state.messagesBySession[sessionId] ?? []
  },
  actions: {
    useSession(sessionId: string) {
      if (sessionId && !this.messagesBySession[sessionId]) {
        this.messagesBySession[sessionId] = []
      }
    },
    appendEvent(sessionId: string, messageId: string, event: ToolEvent) {
      const message = this.messagesBySession[sessionId]?.find((item) => item.id === messageId)

      if (message) {
        message.events.push(event)
      }
    },
    async sendMessage(sessionId: string, content: string) {
      const text = content.trim()

      if (!text || this.loadingSessionId) {
        return
      }

      this.useSession(sessionId)
      this.error = ''
      this.loadingSessionId = sessionId

      const assistant: ChatMessage = {
        id: makeId('assistant'),
        role: 'assistant',
        content: '',
        events: [],
        loading: true
      }

      this.messagesBySession[sessionId].push(
        {
          id: makeId('user'),
          role: 'user',
          content: text,
          events: []
        },
        assistant
      )

      try {
        await streamChat(sessionId, text, (event) => {
          if (event.type === 'reply_start') {
            return
          }

          if (event.type === 'text_delta') {
            assistant.content += typeof event.delta === 'string' ? event.delta : ''
            return
          }

          if (event.type === 'done') {
            assistant.loading = false
            this.loadingSessionId = ''
            return
          }

          const toolEvent = toToolEvent(event)

          if (toolEvent) {
            this.appendEvent(sessionId, assistant.id, toolEvent)

            if (toolEvent.type === 'error') {
              this.error = toolEvent.message ?? '流式响应返回错误'
              assistant.loading = false
              this.loadingSessionId = ''
            }
          }
        })
      } catch (error) {
        const message = errorMessage(error)
        this.error = message
        this.appendEvent(sessionId, assistant.id, {
          id: makeId('event'),
          type: 'error',
          message
        })
      } finally {
        assistant.loading = false
        this.loadingSessionId = ''
      }
    },
    clearSession(sessionId: string) {
      delete this.messagesBySession[sessionId]
    }
  }
})
