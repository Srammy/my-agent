import { defineStore } from 'pinia'
import {
  confirmToolCall,
  StreamRequestError,
  streamChat,
  type ConfirmationToolCall,
  type StreamEvent,
  type ToolConfirmationDecision
} from '../api/chat'

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
  confirmationId?: string
  replyId?: string
  toolCallId?: string
  toolName?: string
  toolInput?: unknown
  toolCalls?: ConfirmationToolCall[]
  decisions?: Record<string, boolean | undefined>
  kind?: 'USER_CONFIRM' | 'EXTERNAL_EXECUTION' | string
  confirming?: boolean
  consumed?: boolean
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

  const toolCalls = Array.isArray(event.toolCalls)
    ? event.toolCalls
        .filter((tool): tool is ConfirmationToolCall =>
          tool !== null &&
          typeof tool === 'object' &&
          typeof tool.toolCallId === 'string' &&
          typeof tool.toolName === 'string'
        )
        .map((tool) => ({
          toolCallId: tool.toolCallId,
          toolName: tool.toolName,
          toolInput: tool.toolInput
        }))
    : undefined

  return {
    id: makeId('event'),
    type: event.type,
    tool: typeof event.tool === 'string' ? event.tool : undefined,
    input: event.input,
    output: event.output,
    permission: typeof event.permission === 'string' ? event.permission : undefined,
    summary: typeof event.summary === 'string' ? event.summary : undefined,
    message: typeof event.message === 'string' ? event.message : undefined,
    confirmationId: typeof event.confirmationId === 'string' ? event.confirmationId : undefined,
    replyId: typeof event.replyId === 'string' ? event.replyId : undefined,
    toolCallId: typeof event.toolCallId === 'string' ? event.toolCallId : undefined,
    toolName: typeof event.toolName === 'string' ? event.toolName : undefined,
    toolInput: event.toolInput,
    toolCalls,
    decisions: event.type === 'permission_required' && event.kind === 'USER_CONFIRM' ? {} : undefined,
    kind: typeof event.kind === 'string' ? event.kind : undefined
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
    setToolDecision(event: ToolEvent, toolCallId: string, confirmed: boolean) {
      if (event.confirming || event.consumed || !event.toolCalls?.some(
        (tool) => tool.toolCallId === toolCallId
      )) return

      event.decisions ??= {}
      event.decisions[toolCallId] = confirmed
    },
    async confirmTool(sessionId: string, messageId: string, event: ToolEvent) {
      if (this.loadingSessionId || !event.confirmationId || event.confirming || event.consumed) {
        return
      }

      if (!event.toolCalls || event.toolCalls.some(
        (tool) => typeof event.decisions?.[tool.toolCallId] !== 'boolean'
      )) {
        return
      }

      const decisions: ToolConfirmationDecision[] = event.toolCalls.map((tool) => ({
        toolCallId: tool.toolCallId,
        confirmed: event.decisions?.[tool.toolCallId] as boolean
      }))

      this.loadingSessionId = sessionId
      event.confirming = true
      this.error = ''

      try {
        await confirmToolCall(sessionId, event.confirmationId, decisions, (streamEvent) => {
          if (streamEvent.type === 'text_delta') {
            const message = this.messagesBySession[sessionId]?.find((item) => item.id === messageId)

            if (message) {
              message.content += typeof streamEvent.delta === 'string' ? streamEvent.delta : ''
            }
            return
          }

          if (streamEvent.type === 'done') {
            return
          }

          const toolEvent = toToolEvent(streamEvent)

          if (toolEvent) {
            this.appendEvent(sessionId, messageId, toolEvent)

            if (toolEvent.type === 'error') {
              this.error = toolEvent.message ?? '流式响应返回错误'
            }
          }
        })
        event.consumed = true
      } catch (error) {
        const message = errorMessage(error)
        this.error = message
        if (error instanceof StreamRequestError && (error.status === 404 || error.status === 409)) {
          event.consumed = true
        } else {
          event.consumed = false
        }
        this.appendEvent(sessionId, messageId, {
          id: makeId('event'),
          type: 'error',
          message
        })
      } finally {
        event.confirming = false
        if (this.loadingSessionId === sessionId) {
          this.loadingSessionId = ''
        }
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
