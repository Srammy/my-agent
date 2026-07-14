# Confirmation Dead Field Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除多工具确认协议迁移后不再生效的前端顶层工具字段和后端内部恢复请求 `replyId`。

**Architecture:** 前端只从 `permission_required.toolCalls[]` 提取工具元数据，不再复制遗留顶层字段。后端继续在 Redis 记录和对外事件中保留 `replyId`，但内部 `ChatToolConfirmationRequest` 只携带恢复实际使用的用户、会话、权限模式和有序工具决策。

**Tech Stack:** TypeScript、Vue 3、Pinia、Vitest、Java 21、JUnit 5、AssertJ、Maven

---

### Task 1: 删除前端顶层工具字段

**Files:**
- Modify: `frontend/src/api/chat.ts`
- Modify: `frontend/src/stores/chat.ts`
- Test: `frontend/src/stores/__tests__/chat.spec.ts`

- [ ] **Step 1: 编写失败测试**

在 `chat.spec.ts` 增加测试，模拟后端同时发送新数组和遗留顶层字段：

```ts
it('does not copy obsolete top-level tool metadata from permission events', async () => {
  vi.spyOn(chatApi, 'streamChat').mockImplementation(async (_sessionId, _message, onEvent) => {
    onEvent({
      type: 'permission_required',
      confirmationId: 'confirm-1',
      replyId: 'reply-1',
      kind: 'USER_CONFIRM',
      toolCallId: 'legacy-call',
      toolName: 'legacy-tool',
      toolInput: { legacy: true },
      toolCalls: [
        { toolCallId: 'call-1', toolName: 'read_file', toolInput: { path: 'a.md' } }
      ]
    })
  })
  const store = useChatStore()

  await store.sendMessage('s1', 'hello')

  const event = store.messages('s1')[1].events[0]
  expect(event).toMatchObject({
    confirmationId: 'confirm-1',
    replyId: 'reply-1',
    toolCalls: [
      { toolCallId: 'call-1', toolName: 'read_file', toolInput: { path: 'a.md' } }
    ]
  })
  expect(event).not.toHaveProperty('toolCallId')
  expect(event).not.toHaveProperty('toolName')
  expect(event).not.toHaveProperty('toolInput')
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- src/stores/__tests__/chat.spec.ts`（工作目录 `frontend`）

Expected: FAIL；生成的 `ToolEvent` 仍含 `toolCallId/toolName/toolInput` 顶层属性。

- [ ] **Step 3: 删除死字段与映射**

从 `StreamEvent` 和 `ToolEvent` 删除以下声明：

```ts
toolCallId?: string
toolName?: string
toolInput?: unknown
```

从 `toToolEvent()` 返回对象删除：

```ts
toolCallId: typeof event.toolCallId === 'string' ? event.toolCallId : undefined,
toolName: typeof event.toolName === 'string' ? event.toolName : undefined,
toolInput: event.toolInput,
```

保留 `ConfirmationToolCall` 的数组元素字段以及顶层 `replyId`。

- [ ] **Step 4: 运行聚焦测试与全量前端验证**

Run: `npm test -- src/stores/__tests__/chat.spec.ts`（工作目录 `frontend`）

Expected: PASS。

Run: `npm test`（工作目录 `frontend`）

Expected: PASS。

Run: `npm run build`（工作目录 `frontend`）

Expected: PASS。

- [ ] **Step 5: 提交 Task 1**

```bash
git add frontend/src/api/chat.ts frontend/src/stores/chat.ts frontend/src/stores/__tests__/chat.spec.ts
git commit -m "refactor: remove obsolete flat tool fields"
```

### Task 2: 删除内部确认请求 replyId

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Test: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- Test: `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`
- Test: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

- [ ] **Step 1: 编写失败的结构测试**

在 `ChatServiceTest` 增加：

```java
@Test
void confirmationGatewayRequestDoesNotExposeUnusedReplyId() {
  assertThat(java.util.Arrays.stream(ChatToolConfirmationRequest.class.getRecordComponents())
      .map(java.lang.reflect.RecordComponent::getName))
      .doesNotContain("replyId");
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -Dtest=ChatServiceTest#confirmationGatewayRequestDoesNotExposeUnusedReplyId test`（工作目录 `backend`）

Expected: FAIL；record components 仍包含 `replyId`。

- [ ] **Step 3: 删除请求字段并更新构造点**

将内部请求改为：

```java
public record ChatToolConfirmationRequest(
    Long userId,
    String sessionId,
    PermissionMode permissionMode,
    List<ToolCallDecision> decisions) {}
```

`ChatService` 构造请求时删除 `claim.record().replyId()` 参数：

```java
ChatToolConfirmationRequest request = new ChatToolConfirmationRequest(
    currentUser.id(), sessionId, context.permissionMode(), trustedDecisions);
```

同步更新 `ChatServiceTest`、`AgentScopeChatAgentGatewayTest` 和 `AgentScopeConfigTest` 中所有 `new ChatToolConfirmationRequest(...)`，只删除原来的 `replyId` 参数。不要删除 `ToolConfirmationRecord.replyId` 或 `StreamEventDto` 的对外 `replyId`。

- [ ] **Step 4: 运行相关后端测试**

Run: `mvn -q '-Dtest=ChatServiceTest,AgentScopeChatAgentGatewayTest,AgentScopeConfigTest' test`（工作目录 `backend`）

Expected: PASS；既有 `confirmationResultCarriesTrustedToolSnapshotForApprovalAndRejection` 和批量恢复测试继续通过。

- [ ] **Step 5: 检查残留并提交 Task 2**

Run: `rg -n "request\.replyId\(\)|ChatToolConfirmationRequest\([^\n]*reply" backend/src`

Expected: 无结果。`ToolConfirmationRecord.replyId` 和对外协议 `replyId` 仍存在。

Run: `git diff --check`

Expected: PASS。

```bash
git add backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java backend/src/main/java/com/example/myagent/chat/ChatService.java backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "refactor: remove unused confirmation reply id"
```
