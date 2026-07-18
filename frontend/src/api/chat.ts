import { apiDelete, apiGet, apiPost, TOKEN_KEY } from './client'

export interface ChatSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export type StreamEventType =
  | 'reply_start'
  | 'text_delta'
  | 'tool_call'
  | 'tool_result'
  | 'permission_required'
  | 'evolution_proposal'
  | 'done'
  | 'error'

export interface ConfirmationToolCall {
  toolCallId: string
  toolName: string
  toolInput: unknown
}

export interface ToolConfirmationDecision {
  toolCallId: string
  confirmed: boolean
}

export class StreamRequestError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'StreamRequestError'
  }
}

export interface StreamEvent {
  type: StreamEventType | string
  delta?: string
  tool?: string
  input?: unknown
  output?: unknown
  permission?: string
  summary?: string
  message?: string
  confirmationId?: string
  replyId?: string
  toolCalls?: ConfirmationToolCall[]
  kind?: 'USER_CONFIRM' | 'EXTERNAL_EXECUTION' | string
  [key: string]: unknown
}

export function listSessions() {
  return apiGet<ChatSession[]>('/api/chat/sessions')
}

export function createSession(title?: string) {
  const body = title?.trim() ? { title: title.trim() } : undefined
  return apiPost<ChatSession>('/api/chat/sessions', body)
}

export function deleteSession(sessionId: string) {
  return apiDelete<null>(`/api/chat/sessions/${encodeURIComponent(sessionId)}`)
}

export function createNdjsonParser(onEvent: (event: StreamEvent) => void) {
  let buffer = ''

  function parseLine(line: string) {
    const trimmed = line.trim()

    if (!trimmed) {
      return
    }

    try {
      const event = JSON.parse(trimmed) as StreamEvent

      if (!event || typeof event !== 'object' || typeof event.type !== 'string') {
        throw new Error('event must be an object with a string type')
      }

      onEvent(event)
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error)
      throw new Error(`Invalid NDJSON stream event: ${detail}. Line: ${trimmed}`)
    }
  }

  return {
    push(chunk: string) {
      buffer += chunk
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      lines.forEach(parseLine)
    },
    flush() {
      parseLine(buffer)
      buffer = ''
    }
  }
}

async function readError(response: Response) {
  const text = await response.text()

  if (!text) {
    return `Stream request failed with status ${response.status}`
  }

  try {
    const data = JSON.parse(text) as Record<string, unknown>
    const message = data.message ?? data.error ?? data.detail

    if (typeof message === 'string' && message.trim()) {
      return message
    }
  } catch {
    return text
  }

  return text
}

export async function streamNdjson(
  path: string,
  body: unknown,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const headers = new Headers({ 'Content-Type': 'application/json' })
  const token = localStorage.getItem(TOKEN_KEY)

  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal
  })

  if (!response.ok) {
    throw new StreamRequestError(await readError(response), response.status)
  }

  if (!response.body) {
    throw new Error('Stream response did not include a readable body')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const parser = createNdjsonParser(onEvent)

  while (true) {
    const { value, done } = await reader.read()

    if (done) {
      break
    }

    parser.push(decoder.decode(value, { stream: true }))
  }

  parser.push(decoder.decode())
  parser.flush()
}

export function streamChat(
  sessionId: string,
  message: string,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  return streamNdjson(`/api/chat/sessions/${encodeURIComponent(sessionId)}/stream`, { message }, onEvent, signal)
}

export function confirmToolCall(
  sessionId: string,
  confirmationId: string,
  decisions: ToolConfirmationDecision[],
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  return streamNdjson(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/tool-confirmations/${encodeURIComponent(confirmationId)}`,
    { decisions },
    onEvent,
    signal
  )
}
