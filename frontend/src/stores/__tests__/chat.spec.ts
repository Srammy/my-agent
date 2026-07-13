import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as chatApi from '../../api/chat'
import { useChatStore, type ToolEvent } from '../chat'

function toolEvent(): ToolEvent {
  return {
    id: 'event-1',
    type: 'permission_required',
    confirmationId: 'confirm-1',
    kind: 'USER_CONFIRM',
    toolCalls: [
      { toolCallId: 'call-1', toolName: 'read_file', toolInput: { path: 'a.md' } },
      { toolCallId: 'call-2', toolName: 'shell_command', toolInput: { command: 'npm test' } }
    ],
    decisions: {}
  }
}

function selectAll(store: ReturnType<typeof useChatStore>, event: ToolEvent) {
  store.setToolDecision(event, 'call-1', true)
  store.setToolDecision(event, 'call-2', false)
}

describe('chat confirmation streams', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('confirmToolCall sends encoded path, bearer token, decisions, and parsed NDJSON events', async () => {
    localStorage.setItem('myagent.token', 'token-1')
    const events: unknown[] = []
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        '{"type":"text_delta","delta":"one"}\n{"type":"tool_result","tool":"shell"}\n',
        { status: 200 }
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    const decisions = [
      { toolCallId: 'call-1', confirmed: true },
      { toolCallId: 'call-2', confirmed: false }
    ]

    await chatApi.confirmToolCall('session / 1', 'confirm / 1', decisions, (event) => events.push(event))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/chat/sessions/session%20%2F%201/tool-confirmations/confirm%20%2F%201',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ decisions }) })
    )
    const headers = fetchMock.mock.calls[0][1].headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(events).toEqual([
      { type: 'text_delta', delta: 'one' },
      { type: 'tool_result', tool: 'shell' }
    ])
  })

  it('submits decisions in tool-call order and appends resumed output to the original message', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(async (_sessionId, _confirmationId, _decisions, onEvent) => {
      onEvent({ type: 'text_delta', delta: 'Done.' })
      onEvent({ type: 'tool_result', tool: 'shell', output: 'ok' })
      onEvent({ type: 'done' })
    })
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: 'Before ', events: [event] }]

    selectAll(store, event)
    await store.confirmTool('s1', 'assistant-1', event)

    expect(confirmToolCallMock).toHaveBeenCalledWith('s1', 'confirm-1', [
      { toolCallId: 'call-1', confirmed: true },
      { toolCallId: 'call-2', confirmed: false }
    ], expect.any(Function))
    expect(store.messages('s1')[0]).toMatchObject({ content: 'Before Done.' })
    expect(store.messages('s1')[0].events[1]).toMatchObject({ type: 'tool_result', output: 'ok' })
    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it('does not request confirmation until every tool call has a decision', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
    const store = useChatStore()
    const event = toolEvent()

    store.setToolDecision(event, 'call-1', true)
    await store.confirmTool('s1', 'assistant-1', event)

    expect(confirmToolCallMock).not.toHaveBeenCalled()
    expect(event.confirming).toBeUndefined()
  })

  it('prevents duplicate confirmation requests while one is in flight', async () => {
    let resolveConfirmation: (() => void) | undefined
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
      () => new Promise<void>((resolve) => { resolveConfirmation = resolve })
    )
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    const first = store.confirmTool('s1', 'assistant-1', event)
    await Promise.resolve()
    expect(event.confirming).toBe(true)
    await store.confirmTool('s1', 'assistant-1', event)
    resolveConfirmation?.()
    await first

    expect(event.confirming).toBe(false)
    expect(confirmToolCallMock).toHaveBeenCalledTimes(1)
  })

  it('consumes a confirmation after an NDJSON error event', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(async (_sessionId, _confirmationId, _decisions, onEvent) => {
      onEvent({ type: 'error', message: 'tool execution failed' })
    })
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(store.messages('s1')[0].events).toMatchObject([
      { id: event.id, confirmationId: event.confirmationId },
      { type: 'error', message: 'tool execution failed' }
    ])
    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it('keeps decisions after HTTP 400 and allows retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{"message":"invalid decisions"}', { status: 400 }))
      .mockResolvedValueOnce(new Response('{"type":"done"}\n', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(event).toMatchObject({ decisions: { 'call-1': true, 'call-2': false }, consumed: false, confirming: false })

    await store.confirmTool('s1', 'assistant-1', event)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(event.consumed).toBe(true)
  })

  it.each([404, 409])('consumes a stale confirmation after HTTP %s', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status })))
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it('filters malformed tool calls, preserves valid order, and initializes decisions', async () => {
    vi.spyOn(chatApi, 'streamChat').mockImplementation(async (_sessionId, _message, onEvent) => {
      onEvent({
        type: 'permission_required',
        confirmationId: 'confirm-1',
        kind: 'USER_CONFIRM',
        toolCalls: [
          { toolCallId: 'call-2', toolName: 'shell_command', toolInput: { command: 'npm test' } },
          { toolCallId: 3, toolName: 'invalid' },
          { toolCallId: 'call-1', toolName: 'read_file', toolInput: { path: 'a.md' } }
        ]
      })
      onEvent({ type: 'done' })
    })
    const store = useChatStore()

    await store.sendMessage('s1', 'hello')

    expect(store.messages('s1')[1].events[0]).toMatchObject({
      type: 'permission_required',
      toolCalls: [
        { toolCallId: 'call-2', toolName: 'shell_command' },
        { toolCallId: 'call-1', toolName: 'read_file' }
      ],
      decisions: {}
    })
  })

  it('does not change decisions for unknown, confirming, or consumed events', () => {
    const store = useChatStore()
    const event = toolEvent()

    store.setToolDecision(event, 'missing', true)
    event.confirming = true
    store.setToolDecision(event, 'call-1', true)
    event.confirming = false
    event.consumed = true
    store.setToolDecision(event, 'call-2', false)

    expect(event.decisions).toEqual({})
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
