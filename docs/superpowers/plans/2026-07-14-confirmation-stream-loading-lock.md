# Confirmation Stream Loading Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让工具确认续流和普通消息流共享现有前端 loading 锁，避免同一时刻启动两条 AgentScope 会话流。

**Architecture:** `useChatStore` 继续以 `loadingSessionId` 表示唯一在途流，`confirmTool()` 增加与 `sendMessage()` 对称的占用、检查和释放逻辑。`ToolEventCard` 读取 store 的 `isLoading`，在任一流进行时禁用确认控件。

**Tech Stack:** Vue 3、Pinia、TypeScript、Vitest、Vue Test Utils

---

### Task 1: 统一普通消息与确认续流的前端互斥

**Files:**
- Modify: `frontend/src/stores/chat.ts`
- Modify: `frontend/src/components/ToolEventCard.vue`
- Test: `frontend/src/stores/__tests__/chat.spec.ts`
- Test: `frontend/src/components/__tests__/ToolEventCard.spec.ts`

- [ ] **Step 1: 编写 store 失败测试**

在 `chat.spec.ts` 增加三个测试：

```ts
it('holds the loading lock during confirmation and blocks sendMessage', async () => {
  let resolveConfirmation: (() => void) | undefined
  vi.spyOn(chatApi, 'confirmToolCall').mockImplementation(
    () => new Promise<void>((resolve) => { resolveConfirmation = resolve })
  )
  const streamChatMock = vi.spyOn(chatApi, 'streamChat')
  const store = useChatStore()
  const event = toolEvent()
  selectAll(store, event)

  const confirmation = store.confirmTool('s1', 'assistant-1', event)
  await Promise.resolve()

  expect(store.loadingSessionId).toBe('s1')
  await store.sendMessage('s1', 'another message')
  expect(streamChatMock).not.toHaveBeenCalled()

  resolveConfirmation?.()
  await confirmation
  expect(store.loadingSessionId).toBe('')
})

it('does not start confirmation while a message stream is in flight', async () => {
  let resolveStream: (() => void) | undefined
  vi.spyOn(chatApi, 'streamChat').mockImplementation(
    () => new Promise<void>((resolve) => { resolveStream = resolve })
  )
  const confirmToolCallMock = vi.spyOn(chatApi, 'confirmToolCall')
  const store = useChatStore()
  const event = toolEvent()
  selectAll(store, event)

  const stream = store.sendMessage('s1', 'hello')
  await Promise.resolve()
  await store.confirmTool('s1', 'assistant-1', event)

  expect(confirmToolCallMock).not.toHaveBeenCalled()
  expect(event.confirming).toBeUndefined()

  resolveStream?.()
  await stream
})

it('releases the loading lock after confirmation failure', async () => {
  vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(new Error('network unavailable'))
  const store = useChatStore()
  const event = toolEvent()
  selectAll(store, event)

  await store.confirmTool('s1', 'assistant-1', event)

  expect(store.loadingSessionId).toBe('')
  expect(event.confirming).toBe(false)
})
```

- [ ] **Step 2: 编写组件失败测试**

在 `ToolEventCard.spec.ts` 增加：

```ts
it('disables all confirmation controls while another chat stream is loading', () => {
  const chat = useChatStore()
  chat.loadingSessionId = 's_123'

  const wrapper = mountCard(confirmationEvent({
    decisions: { 'call-1': true, 'call-2': false }
  }))

  expect(wrapper.findAll('button')).toHaveLength(5)
  expect(wrapper.findAll('button').every(
    (button) => button.attributes('disabled') !== undefined
  )).toBe(true)
})
```

- [ ] **Step 3: 运行测试并确认按预期失败**

Run: `npm test -- src/stores/__tests__/chat.spec.ts src/components/__tests__/ToolEventCard.spec.ts`（工作目录 `frontend`）

Expected: FAIL；确认期间 `loadingSessionId` 仍为空、消息流期间仍调用 `confirmToolCall`，并且 loading 时确认按钮仍可用。

- [ ] **Step 4: 编写最小 store 实现**

在 `confirmTool()` 的首个 guard 中加入 `this.loadingSessionId`：

```ts
if (!event.confirmationId || event.confirming || event.consumed || this.loadingSessionId) {
  return
}
```

决策校验通过后、调用 API 前同步占用锁：

```ts
this.loadingSessionId = sessionId
event.confirming = true
this.error = ''
```

在 `finally` 中只释放属于当前 session 的锁：

```ts
event.confirming = false
if (this.loadingSessionId === sessionId) {
  this.loadingSessionId = ''
}
```

- [ ] **Step 5: 编写最小组件实现**

在 `ToolEventCard.vue` 的 `confirmationLocked` 中加入 `chat.isLoading`：

```ts
const confirmationLocked = computed(
  () =>
    chat.isLoading ||
    props.event.confirming ||
    props.event.consumed ||
    !props.sessionId ||
    !props.messageId
)
```

- [ ] **Step 6: 运行聚焦测试并确认通过**

Run: `npm test -- src/stores/__tests__/chat.spec.ts src/components/__tests__/ToolEventCard.spec.ts`（工作目录 `frontend`）

Expected: PASS，所有 store 和确认卡片测试通过。

- [ ] **Step 7: 运行前端全量验证**

Run: `npm test`（工作目录 `frontend`）

Expected: PASS，所有测试通过。

Run: `npm run build`（工作目录 `frontend`）

Expected: PASS，TypeScript 检查与 Vite 生产构建成功。

- [ ] **Step 8: 提交实现**

```bash
git add frontend/src/stores/chat.ts frontend/src/stores/__tests__/chat.spec.ts frontend/src/components/ToolEventCard.vue frontend/src/components/__tests__/ToolEventCard.spec.ts
git commit -m "fix: lock chat during tool confirmation"
```
