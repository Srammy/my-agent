# 会话执行完成确认 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅在所有实例确认 Agent 与当前工具实际结束后删除会话并返回 204。

**Architecture:** 执行协调器将“请求取消”与“完成注销”分离，按 `userId + sessionId` 维护有 TTL 的会话活跃执行计数。执行注册增加计数，流和工具实际结束才递减；删除写取消墓碑、取消本地句柄并等待计数归零。前端在 409 时保持取消中，不恢复发送。

**Tech Stack:** Spring WebFlux、Reactor、Reactive Redis、Vue 3、Pinia、Vitest、Testcontainers。

## Global Constraints

- `DELETE 204` 时对应 `userId + sessionId` 不得有 Agent 或工具执行。
- 已完成的外部副作用不回滚；取消后不得启动后续工具。
- 不新增依赖、不改变 Skill、手工上传、审批或普通会话创建。
- 取消墓碑使用现有 Redis key prefix 且 TTL 覆盖迟到执行登记窗口。

---

### Task 1: 后端完成确认与会话活跃计数

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/session/SessionExecutionCoordinator.java`
- Modify: `backend/src/main/java/com/example/myagent/session/RedisSessionExecutionCoordinator.java`
- Test: `backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java`

**Interfaces:**
- Preserve: `<T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source)`。
- Produce: `cancelAndAwait` 只在该会话活跃计数为零时完成；活跃计数只在底层执行真正终止时递减。

- [ ] **Step 1: 写失败测试**

```java
@Test
void cancelWaitsForExecutionCompletionInsteadOfSubscriptionCancellation() {
    // 取消订阅后保持工具 completion 未完成，断言 cancelAndAwait 尚未完成且计数仍为 1。
}
```

- [ ] **Step 2: 运行失败测试**

Run: `$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; mvn -q -f backend/pom.xml -Dtest=RedisSessionExecutionCoordinatorTest test`
Expected: FAIL，当前活跃 key 在 cancel 后过早清理。

- [ ] **Step 3: 最小实现**

```java
// register: increment session execution counter
// actual completion callback: decrement counter and clear local handle
// cancelAndAwait: wait for counter == 0; never use Redis KEYS
// cancellation tombstone: configured keyPrefix + bounded TTL
```

- [ ] **Step 4: 补边界测试并转绿**

```java
@Test void cancellationMarkerExpiresAfterConfiguredTtl() {}
@Test void differentUsersWithSameSessionIdHaveIndependentCounters() {}
@Test void cancellationDoesNotCompleteUntilBlockingToolCompletionSignal() {}
```

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main/java/com/example/myagent/session backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java
git commit -m "wait for session execution completion"
```

### Task 2: 工具退出完成信号与删除链路

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Modify: `backend/src/main/java/com/example/myagent/session/SessionService.java`
- Test: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- Test: `backend/src/test/java/com/example/myagent/session/SessionServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的完成确认协调器。
- Produces: 工具执行结束后才允许协调器注销；`SessionService.deleteSession` 仅在完成确认后删除。

- [ ] **Step 1: 写失败测试**

```java
@Test void deleteDoesNotDeleteWhileCurrentToolHasNotCompleted() {}
@Test void cancellationPreventsNextToolInvocationAfterCurrentToolCompletes() {}
```

- [ ] **Step 2: 运行失败测试**

Run: `$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; mvn -q -f backend/pom.xml -Dtest=ChatServiceTest,SessionServiceTest test`
Expected: FAIL，当前实现只观察 subscription cancel。

- [ ] **Step 3: 最小实现**

```java
// tool wrapper registers an interruptible Future/process handle when available
// cancellation interrupts it; non-interruptible work completes naturally
// no subsequent tool starts once session cancellation is observed
```

- [ ] **Step 4: 转绿**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/main/java/com/example/myagent/chat backend/src/main/java/com/example/myagent/session backend/src/test/java/com/example/myagent
git commit -m "wait for tool completion before session deletion"
```

### Task 3: 前端取消中状态与并发删除

**Files:**
- Modify: `frontend/src/stores/chat.ts`
- Modify: `frontend/src/views/ChatView.vue`
- Modify: `frontend/src/components/SessionSidebar.vue`
- Test: `frontend/src/stores/chat.spec.ts`
- Test: `frontend/src/views/ChatView.spec.ts`

- [ ] **Step 1: 写失败测试**

```ts
it('keeps a 409 session cancelling and disables sending', async () => {})
it('does not abort session B when delete B is rejected while deleting A', async () => {})
```

- [ ] **Step 2: 运行失败测试**

Run: `npm test -- --run frontend/src/stores/chat.spec.ts frontend/src/views/ChatView.spec.ts`
Expected: FAIL，409 后当前代码会清除 cancelling 状态。

- [ ] **Step 3: 最小实现**

```ts
// 409 keeps cancellingSessionIds[sessionId]
// only the delete request accepted for a session may abort that session stream
// composer and confirmation controls remain disabled while cancelling
```

- [ ] **Step 4: 转绿**

Run: `npm run typecheck; npm test`
Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add frontend/src/stores/chat.ts frontend/src/views/ChatView.vue frontend/src/components/SessionSidebar.vue frontend/src/**/*.spec.ts
git commit -m "keep cancelling sessions unavailable"
```

### Task 4: 真实 Redis 多实例回归验证

**Files:**
- Modify: `backend/src/test/java/com/example/myagent/session/SessionExecutionCoordinatorRedisIntegrationTest.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test void deleteReturns204OnlyAfterRemoteBlockingExecutionCompletes() {}
@Test void cancellationCounterDoesNotUseUnrelatedRedisKeys() {}
```

- [ ] **Step 2: 运行并验证失败**

Run: `$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; mvn -q -f backend/pom.xml -Dtest=SessionExecutionCoordinatorRedisIntegrationTest,ChatServiceConfirmationIntegrationTest test`
Expected: FAIL before Tasks 1-2.

- [ ] **Step 3: 仅按需要修复测试装配或实现**

```java
// start two coordinator instances against one Redis container;
// assert delete is pending until remote completion latch opens, then 204.
```

- [ ] **Step 4: 完整验证**

Run: `mvn -q -f backend/pom.xml test; npm run typecheck; npm test`
Expected: PASS，或精确记录既有独立失败。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/test/java/com/example/myagent
git commit -m "verify session execution completion across instances"
```
