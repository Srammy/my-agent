import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as chatApi from '../../api/chat'
import type { StreamEvent } from '../../api/chat'
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

  it('passes the supplied abort signal to the streaming fetch', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn((_path: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    }))
    vi.stubGlobal('fetch', fetchMock)

    const request = chatApi.streamNdjson('/api/chat/sessions/s1/stream', { message: 'hello' }, vi.fn(), controller.signal)
    controller.abort()

    expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('reads the stable error code from a failed streaming response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('conflict', {
        status: 409,
        headers: { 'X-Error-Code': 'SESSION_CANCELLING' }
      })
    ))

    await expect(
      chatApi.streamNdjson('/api/chat/sessions/s1/stream', { message: 'hello' }, vi.fn())
    ).rejects.toMatchObject({
      name: 'StreamRequestError',
      status: 409,
      code: 'SESSION_CANCELLING'
    })
  })

  it('aborts only the matching session stream without appending a false error', async () => {
    let streamSignal: AbortSignal | undefined
    vi.spyOn(chatApi, 'streamChat').mockImplementation((...args: unknown[]) => {
      const signal = args[3] as AbortSignal
      streamSignal = signal
      return new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const store = useChatStore()

    const sending = store.sendMessage('s1', 'hello')
    await Promise.resolve()
    store.abortSession('s2')
    expect(streamSignal?.aborted).toBe(false)

    store.abortSession('s1')
    await sending

    expect(streamSignal?.aborted).toBe(true)
    expect(store.error).toBe('')
    expect(store.messages('s1')[1].events).toEqual([])
    expect(store.isCancellingSession('s1')).toBe(true)

    store.finishSessionCancellation('s1')
    expect(store.isCancellingSession('s1')).toBe(false)
  })

  it('aborts tool confirmation without appending a false error', async () => {
    let confirmationSignal: AbortSignal | undefined
    vi.spyOn(chatApi, 'confirmToolCall').mockImplementation((...args: unknown[]) => {
      const signal = args[4] as AbortSignal
      confirmationSignal = signal
      return new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [{ id: 'assistant-1', role: 'assistant', content: '', events: [event] }]
    selectAll(store, event)

    const confirming = store.confirmTool('s1', 'assistant-1', event)
    await Promise.resolve()
    store.abortSession('s1')
    await confirming

    expect(confirmationSignal?.aborted).toBe(true)
    expect(store.error).toBe('')
    expect(store.messages('s1')[0].events).toEqual([event])
    expect(event).toMatchObject({ consumed: false, confirming: false })
  })

  it('keeps a confirmation consumed when aborted after receiving a stream event', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
      async (_sessionId, _confirmationId, _decisions, onEvent, signal) => {
        onEvent({ type: 'text_delta', delta: 'partial' })
        await new Promise<void>((_resolve, reject) => {
          signal?.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError'))
          )
        })
      }
    )
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [
      { id: 'assistant-1', role: 'assistant', content: '', events: [event] }
    ]
    selectAll(store, event)

    const confirming = store.confirmTool('s1', 'assistant-1', event)
    await Promise.resolve()
    store.abortSession('s1')
    await confirming

    expect(store.error).toBe('')
    expect(store.messages('s1')[0].content).toBe('partial')
    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it('blocks sending and tool confirmation while the session is cancelling', async () => {
    const streamChatMock = vi.spyOn(chatApi, 'streamChat')
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    store.abortSession('s1')
    await store.sendMessage('s1', 'hello')
    await store.confirmTool('s1', 'assistant-1', event)

    expect(streamChatMock).not.toHaveBeenCalled()
    expect(confirmToolCallMock).not.toHaveBeenCalled()
  })

  it('does not show an abort error when server deletion finishes before the local stream settles', async () => {
    vi.spyOn(chatApi, 'streamChat').mockImplementation((...args: unknown[]) => {
      const signal = args[3] as AbortSignal
      return new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          queueMicrotask(() => reject(new DOMException('Aborted', 'AbortError')))
        })
      })
    })
    const store = useChatStore()

    const sending = store.sendMessage('s1', 'hello')
    await Promise.resolve()
    store.abortSession('s1')
    store.finishSessionCancellation('s1')
    await sending

    expect(store.error).toBe('')
    expect(store.messages('s1')[1].events).toEqual([])
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
    ], expect.any(Function), expect.any(AbortSignal))
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

  it('blocks message sending while tool confirmation is in flight', async () => {
    let resolveConfirmation: (() => void) | undefined
    vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
      () => new Promise<void>((resolve) => { resolveConfirmation = resolve })
    )
    const streamChatMock = vi.spyOn(chatApi, 'streamChat')
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    const confirmation = store.confirmTool('s1', 'assistant-1', event)

    expect(store.loadingSessionId).toBe('s1')
    await store.sendMessage('s2', 'hello')
    expect(streamChatMock).not.toHaveBeenCalled()

    resolveConfirmation?.()
    await confirmation

    expect(store.loadingSessionId).toBe('')
  })

  it('blocks tool confirmation while message sending is in flight', async () => {
    let resolveMessage: (() => void) | undefined
    vi.spyOn(chatApi, 'streamChat').mockImplementation(
      () => new Promise<void>((resolve) => { resolveMessage = resolve })
    )
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    const message = store.sendMessage('s1', 'hello')
    await store.confirmTool('s1', 'assistant-1', event)

    expect(confirmToolCallMock).not.toHaveBeenCalled()
    expect(event.confirming).toBeUndefined()

    resolveMessage?.()
    await message
  })

  it.each(['done', 'error'] as const)(
    'retains the chat lock until the stream resolves after a %s event',
    async (eventType) => {
      let resolveStream: (() => void) | undefined
      vi.spyOn(chatApi, 'streamChat').mockImplementation((_sessionId, _message, onEvent) => {
        if (eventType === 'done') {
          onEvent({ type: 'done' })
        } else {
          onEvent({ type: 'error', message: 'stream failed' })
        }
        return new Promise<void>((resolve) => { resolveStream = resolve })
      })
      const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall').mockResolvedValue()
      const store = useChatStore()
      const event = toolEvent()
      selectAll(store, event)
      let messageSettled = false

      const message = store.sendMessage('s1', 'hello').finally(() => { messageSettled = true })
      await Promise.resolve()

      expect(messageSettled).toBe(false)
      expect(store.loadingSessionId).toBe('s1')
      await store.confirmTool('s1', 'assistant-1', event)
      expect(confirmToolCallMock).not.toHaveBeenCalled()

      resolveStream?.()
      await message

      expect(store.loadingSessionId).toBe('')
    }
  )

  it('releases the chat lock when tool confirmation fails', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(new Error('confirmation failed'))
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(store.loadingSessionId).toBe('')
    expect(event.confirming).toBe(false)
  })

  it('does not submit a consumed confirmation again', async () => {
    const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)
    event.consumed = true

    await store.confirmTool('s1', 'assistant-1', event)

    expect(confirmToolCallMock).not.toHaveBeenCalled()
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

  it('keeps a confirmation consumed when a partial stream later fails', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
      async (_sessionId, _confirmationId, _decisions, onEvent) => {
        onEvent({ type: 'text_delta', delta: 'partial' })
        throw new Error('connection lost')
      }
    )
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [
      { id: 'assistant-1', role: 'assistant', content: '', events: [event] }
    ]
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(store.messages('s1')[0].content).toBe('partial')
    expect(event).toMatchObject({ consumed: true, confirming: false })
  })

  it.each([
    [
      'an unclassified 5xx',
      new chatApi.StreamRequestError('server failed', 500)
    ],
    ['a network error', new Error('network failed')]
  ])('fails closed after %s', async (_label, failure) => {
    vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(failure)
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

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

  it('keeps a safely rolled-back confirmation retryable', async () => {
    vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(
      new chatApi.StreamRequestError('registration failed', 503, 'TOOL_CONFIRMATION_RETRYABLE')
    )
    const store = useChatStore()
    const event = toolEvent()
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(event).toMatchObject({ consumed: false, confirming: false })
    expect(store.cancellingSessionIds.s1).toBeUndefined()
  })

  it.each([409, 400])(
    'keeps a fail-closed confirmation consumed with HTTP %s',
    async (status) => {
      vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(
        new chatApi.StreamRequestError(
          'consume result was uncertain',
          status,
          'TOOL_CONFIRMATION_CONSUMED'
        )
      )
      const store = useChatStore()
      const event = toolEvent()
      selectAll(store, event)

      await store.confirmTool('s1', 'assistant-1', event)

      expect(event).toMatchObject({ consumed: true, confirming: false })
      expect(store.cancellingSessionIds.s1).toBeUndefined()
    }
  )

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
      } as unknown as StreamEvent)
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

  it('does not copy obsolete top-level tool metadata from permission events', async () => {
    vi.spyOn(chatApi, 'streamChat').mockImplementation(async (_sessionId, _message, onEvent) => {
      onEvent({
        type: 'permission_required',
        confirmationId: 'confirm-1',
        replyId: 'reply-1',
        kind: 'USER_CONFIRM',
        toolCallId: 'obsolete-call',
        toolName: 'obsolete-tool',
        toolInput: { obsolete: true },
        toolCalls: [
          { toolCallId: 'call-1', toolName: 'shell_command', toolInput: { command: 'npm test' } }
        ]
      })
      onEvent({ type: 'done' })
    })
    const store = useChatStore()

    await store.sendMessage('s1', 'hello')

    const event = store.messages('s1')[1].events[0]
    expect(event).toMatchObject({
      confirmationId: 'confirm-1',
      replyId: 'reply-1',
      toolCalls: [
        { toolCallId: 'call-1', toolName: 'shell_command', toolInput: { command: 'npm test' } }
      ]
    })
    expect(event).not.toHaveProperty('toolCallId')
    expect(event).not.toHaveProperty('toolName')
    expect(event).not.toHaveProperty('toolInput')
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

  it('marks a message stream with the session cancelling code as cancelling and blocks another send', async () => {
    const streamChatMock = vi.spyOn(chatApi, 'streamChat')
      .mockRejectedValueOnce(new chatApi.StreamRequestError('cancelling', 409, 'SESSION_CANCELLING'))
      .mockResolvedValueOnce()
    const store = useChatStore()

    await store.sendMessage('s1', 'first')
    await store.sendMessage('s1', 'second')

    expect(store.isCancellingSession('s1')).toBe(true)
    expect(streamChatMock).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['a 409 without a code', new chatApi.StreamRequestError('conflict', 409)],
    ['a 409 with another code', new chatApi.StreamRequestError('conflict', 409, 'TOOL_CONFIRMATION_CONFLICT')]
  ])('does not mark a message session as cancelling for %s', async (_label, error) => {
    vi.spyOn(chatApi, 'streamChat').mockRejectedValue(error)
    const store = useChatStore()

    await store.sendMessage('s1', 'hello')

    expect(store.isCancellingSession('s1')).toBe(false)
  })

  it('marks a confirmation with the session cancelling code as cancelling', async () => {
    vi.spyOn(chatApi, 'confirmToolCall')
      .mockRejectedValue(new chatApi.StreamRequestError('cancelling', 409, 'SESSION_CANCELLING'))
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [
      { id: 'assistant-1', role: 'assistant', content: '', events: [event] }
    ]
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(store.isCancellingSession('s1')).toBe(true)
    expect(event.consumed).toBe(false)
  })

  it.each([
    ['a 409 without a code', new chatApi.StreamRequestError('conflict', 409)],
    ['a 409 with another code', new chatApi.StreamRequestError('conflict', 409, 'TOOL_CONFIRMATION_CONFLICT')]
  ])('does not mark a confirmation session as cancelling for %s', async (_label, error) => {
    vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(error)
    const store = useChatStore()
    const event = toolEvent()
    store.messagesBySession.s1 = [
      { id: 'assistant-1', role: 'assistant', content: '', events: [event] }
    ]
    selectAll(store, event)

    await store.confirmTool('s1', 'assistant-1', event)

    expect(store.isCancellingSession('s1')).toBe(false)
  })
})
