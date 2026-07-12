import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as chatApi from '../../api/chat'
import { useChatStore, type ToolEvent } from '../chat'

function toolEvent(): ToolEvent {
  return {
    id: 'permission-1',
    type: 'permission_required',
    confirmationId: 'confirm / 1',
    replyId: 'reply-1',
    toolCallId: 'call-1',
    toolName: 'shell',
    toolInput: { command: 'pwd' },
    kind: 'USER_CONFIRM'
  }
}

describe('chat confirmation streams', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('confirmToolCall sends encoded path, bearer token, body, and parsed NDJSON events', async () => {
    localStorage.setItem('myagent.token', 'token-1')
    const events: unknown[] = []
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        '{"type":"text_delta","delta":"one"}\n{"type":"tool_result","tool":"shell"}\n',
        { status: 200 }
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    await chatApi.confirmToolCall('session / 1', 'confirm / 1', false, (event) => events.push(event))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/chat/sessions/session%20%2F%201/tool-confirmations/confirm%20%2F%201',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ confirmed: false }) })
    )
    const headers = fetchMock.mock.calls[0][1].headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(events).toEqual([
      { type: 'text_delta', delta: 'one' },
      { type: 'tool_result', tool: 'shell' }
    ])
  })

  it('appends confirmation text and result to the original assistant message then consumes the event', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(async (_sessionId, _confirmationId, _confirmed, onEvent) => {
      onEvent({ type: 'text_delta', delta: 'Done.' })
      onEvent({ type: 'tool_result', tool: 'shell', output: 'ok' })
      onEvent({ type: 'done' })
    })
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: 'Before ', events: [event] }]

    await store.confirmTool('s1', 'assistant-1', event, true)

    expect(confirmToolCallMock).toHaveBeenCalledWith('s1', 'confirm / 1', true, expect.any(Function))
    expect(store.messages('s1')[0]).toMatchObject({ content: 'Before Done.' })
    expect(store.messages('s1')[0].events).toHaveLength(2)
    expect(store.messages('s1')[0].events[1]).toMatchObject({ type: 'tool_result', output: 'ok' })
    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it('passes a rejection to the confirmation API', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockResolvedValue()
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]

    await store.confirmTool('s1', 'assistant-1', event, false)

    expect(confirmToolCallMock).toHaveBeenCalledWith('s1', 'confirm / 1', false, expect.any(Function))
    expect(event.consumed).toBe(true)
  })

  it('prevents duplicate confirmation requests while one is in flight', async () => {
    let resolveConfirmation: (() => void) | undefined
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
      () => new Promise<void>((resolve) => { resolveConfirmation = resolve })
    )
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]
    store.loadingSessionId = 'other-session'

    const first = store.confirmTool('s1', 'assistant-1', event, true)
    await Promise.resolve()
    expect(event.confirming).toBe(true)
    expect(store.loadingSessionId).toBe('other-session')
    await store.confirmTool('s1', 'assistant-1', event, true)
    resolveConfirmation?.()
    await first

    expect(event.confirming).toBe(false)
    expect(store.loadingSessionId).toBe('other-session')
    expect(confirmToolCallMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the permission event and appends an error so confirmation can be retried', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(new Error('stream failed'))
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]

    await store.confirmTool('s1', 'assistant-1', event, true)

    expect(store.messages('s1')[0].events).toHaveLength(2)
    expect(store.messages('s1')[0].events[0]).toMatchObject({
      id: event.id,
      confirmationId: event.confirmationId
    })
    expect(store.messages('s1')[0].events[1]).toMatchObject({ type: 'error', message: 'stream failed' })
    expect(event).toMatchObject({ consumed: false, confirming: false })
  })

  it('does not request confirmation without an id or after consumption', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
    const store = useChatStore()
    const missingId = { ...toolEvent(), confirmationId: undefined }
    const consumed = { ...toolEvent(), consumed: true }
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [missingId, consumed] }]

    await store.confirmTool('s1', 'assistant-1', missingId, true)
    await store.confirmTool('s1', 'assistant-1', consumed, true)

    expect(confirmToolCallMock).not.toHaveBeenCalled()
  })

  it('keeps sendMessage streaming behavior', async () => {
    vi.spyOn(chatApi, 'streamChat').mockImplementation(async (_sessionId, _message, onEvent) => {
      onEvent({ type: 'text_delta', delta: 'reply' })
      onEvent({ type: 'done' })
    })
    const store = useChatStore()

    await store.sendMessage('s1', 'hello')

    expect(store.messages('s1')).toMatchObject([
      { role: 'user', content: 'hello' },
      { role: 'assistant', content: 'reply', loading: false }
    ])
  })
})
