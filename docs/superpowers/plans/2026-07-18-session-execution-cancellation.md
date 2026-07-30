# 会话执行取消 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在多实例部署中，只有确认 Agent 执行停止后才能删除会话，并阻止该会话启动后续聊天或工具确认执行。

**Architecture:** 新增 Redis 协调的 `SessionExecutionCoordinator`。每个实例保存本机执行流的取消句柄，同时用 Redis 取消标记、活跃执行键和发布/订阅消息协同跨实例取消。聊天流、工具确认恢复流均通过协调器登记；会话删除先取消并等待活跃执行清空，再删除数据库记录。前端中止本地请求，但以后端取消完成为准。

**Tech Stack:** Java 21、Spring WebFlux、Project Reactor、Spring Data Redis Reactive、MyBatis-Plus、Vue 3、Pinia、Vitest、JUnit 5、Mockito。

## Global Constraints

- 保持用户隔离键：`userId + sessionId`。
- 删除成功（HTTP 204）必须表示该会话没有活跃 Agent 执行。
- 已完成的外部副作用不回滚；取消只停止未完成及后续步骤。
- 手工 Skill 上传、Skill 审批和普通会话创建不得改变。
- 不引入通用任务系统或新外部依赖。

---

## 文件结构

- Create: `backend/src/main/java/com/example/myagent/session/SessionExecutionCoordinator.java` — 跨实例执行登记、取消、等待接口。
- Create: `backend/src/main/java/com/example/myagent/session/RedisSessionExecutionCoordinator.java` — Redis 键、发布/订阅、本机取消句柄和 TTL 续期实现。
- Create: `backend/src/main/java/com/example/myagent/session/SessionExecutionKey.java` — `userId/sessionId` 值对象及 Redis 键编码。
- Create: `backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java` — 协调器单元测试。
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java` — 通过协调器运行聊天和确认恢复流。
- Modify: `backend/src/main/java/com/example/myagent/session/SessionService.java` — 删除前取消并等待，再删除数据库会话。
- Modify: `backend/src/main/java/com/example/myagent/session/SessionController.java` — 使用响应式删除流程。
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java` — 验证两类流均被登记。
- Modify: `backend/src/test/java/com/example/myagent/session/SessionServiceTest.java` — 验证停止完成前不删会话。
- Modify: `frontend/src/api/chat.ts` — 为 NDJSON 请求传递 `AbortSignal`。
- Modify: `frontend/src/stores/chat.ts` — 保存每会话 `AbortController`，支持中止和取消中状态。
- Modify: `frontend/src/stores/sessions.ts` — 保存删除中状态及后端错误。
- Modify: `frontend/src/views/ChatView.vue` — 删除时先中止本地流，仅在 DELETE 成功后清理 UI。
- Modify: `frontend/src/components/SessionSidebar.vue` — 禁止重复删除取消中的会话。
- Modify: `frontend/src/stores/__tests__/chat.spec.ts` — 验证信号传递与本地中止不显示伪错误。
- Create: `frontend/src/stores/__tests__/sessions.spec.ts` — 验证删除成功/取消超时的 UI 状态。

## Task 1: 定义并验证会话执行协调器

**Files:**
- Create: `backend/src/main/java/com/example/myagent/session/SessionExecutionKey.java`
- Create: `backend/src/main/java/com/example/myagent/session/SessionExecutionCoordinator.java`
- Create: `backend/src/main/java/com/example/myagent/session/RedisSessionExecutionCoordinator.java`
- Test: `backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java`

**Interfaces:**

```java
public interface SessionExecutionCoordinator {
  <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source);
  Mono<Void> cancelAndAwait(Long userId, String sessionId);
  Mono<Void> rejectIfCancelled(Long userId, String sessionId);
}

public record SessionExecutionKey(Long userId, String sessionId) {}
```

- [ ] **Step 1: 写失败测试：本机取消会取消已登记订阅并清理活跃记录。**

```java
@Test
void cancelAndAwaitCancelsLocalExecutionAndWaitsForCleanup() {
  Sinks.Many<Integer> source = Sinks.many().unicast().onBackpressureBuffer();
  Disposable subscription = coordinator.track(1L, "s_1", source.asFlux()).subscribe();

  coordinator.cancelAndAwait(1L, "s_1").block();

  assertThat(subscription.isDisposed()).isTrue();
  verify(redisTemplate).convertAndSend(anyString(), anyString());
}
```

- [ ] **Step 2: 运行测试确认失败。**

Run: `mvn -q -f backend/pom.xml -Dtest=RedisSessionExecutionCoordinatorTest test`

Expected: FAIL，因为协调器类不存在。

- [ ] **Step 3: 实现最小协调器和 Redis 键。**

```java
public record SessionExecutionKey(Long userId, String sessionId) {
  String prefix() { return "myagent:session-execution:" + userId + ":" + sessionId; }
  String cancellationKey() { return prefix() + ":cancelled"; }
  String activeKey(String executionId) { return prefix() + ":active:" + executionId; }
}
```

`RedisSessionExecutionCoordinator.track(...)` 必须：先检查取消标记；创建带 TTL 的活跃执行键；登记本机 `Subscription::cancel`；在 `doFinally` 删除活跃键和本机句柄；运行期间用 `Flux.interval` 续期活跃键。取消消息的订阅者必须调用本机匹配句柄的 `cancel()`。

`cancelAndAwait(...)` 必须：写入取消标记、发布取消消息、同步取消本机句柄、轮询该会话的活跃键；活跃键全部消失时完成。达到固定等待上限时以 `ResponseStatusException(HttpStatus.CONFLICT, "Session cancellation is still in progress")` 失败。

- [ ] **Step 4: 增加失败测试：取消标记阻止晚到执行启动。**

```java
@Test
void trackRejectsExecutionAfterCancellationWasRecorded() {
  coordinator.cancelAndAwait(1L, "s_1").block();

  StepVerifier.create(coordinator.track(1L, "s_1", () -> Flux.just("unexpected")))
      .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ResponseStatusException.class))
      .verify();
}
```

- [ ] **Step 5: 实现取消检查和跨实例消息处理。**

使用现有 `ReactiveStringRedisTemplate` 发布取消消息；使用 `ReactiveRedisMessageListenerContainer` 订阅固定 channel。消息负载仅包含 `userId` 与 `sessionId`，接收后只取消本机内存映射中完全匹配的执行。实例启动时订阅，关闭时 dispose 订阅。

- [ ] **Step 6: 运行协调器测试确认通过。**

Run: `mvn -q -f backend/pom.xml -Dtest=RedisSessionExecutionCoordinatorTest test`

Expected: PASS。

- [ ] **Step 7: 提交。**

```bash
git add backend/src/main/java/com/example/myagent/session/SessionExecutionKey.java backend/src/main/java/com/example/myagent/session/SessionExecutionCoordinator.java backend/src/main/java/com/example/myagent/session/RedisSessionExecutionCoordinator.java backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java
git commit -m "coordinate session execution cancellation"
```

## Task 2: 将聊天、确认恢复和会话删除接入协调器

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Modify: `backend/src/main/java/com/example/myagent/session/SessionService.java`
- Modify: `backend/src/main/java/com/example/myagent/session/SessionController.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- Modify: `backend/src/test/java/com/example/myagent/session/SessionServiceTest.java`

**Interfaces:**

```java
// ChatService 内部使用
sessionExecutionCoordinator.track(currentUser.id(), sessionId, () -> chatAgentGateway.stream(request));

// SessionService 对控制器公开
public Mono<Void> deleteSession(CurrentUser currentUser, String sessionId);
```

- [ ] **Step 1: 写失败测试：聊天与确认恢复分别通过协调器登记。**

```java
verify(sessionExecutionCoordinator).track(
    eq(USER.id()), eq("s_123"), any());
```

为 `streamBuildsCurrentUsersChatRequestBeforeCallingGateway` 和确认恢复测试分别加入该断言，并让 coordinator mock 的 `track` 返回传入 source。

- [ ] **Step 2: 运行测试确认失败。**

Run: `mvn -q -f backend/pom.xml -Dtest=ChatServiceTest test`

Expected: FAIL，因为 `ChatService` 尚未注入或调用协调器。

- [ ] **Step 3: 最小接入 ChatService。**

```java
return sessionExecutionCoordinator.track(
    currentUser.id(), sessionId, () -> chatAgentGateway.stream(request));
```

确认恢复使用同一模式包裹 `chatAgentGateway.confirm(request)`。会话所有权校验、权限模式和现有确认决策校验顺序保持不变。

- [ ] **Step 4: 写失败测试：删除在协调器完成前不得调用 mapper 删除。**

```java
when(sessionExecutionCoordinator.cancelAndAwait(USER_A.id(), "s_a"))
    .thenReturn(Mono.never());

StepVerifier.create(sessionService.deleteSession(USER_A, "s_a"))
    .thenCancel()
    .verify();

verify(chatSessionMapper, never()).deleteOwnedById(anyLong(), anyString());
```

- [ ] **Step 5: 将删除流程改为响应式编排。**

`SessionService.deleteSession(...)` 先在 `Schedulers.boundedElastic()` 校验归属，再调用 `cancelAndAwait`，最后在 `boundedElastic()` 执行 `deleteOwnedById`。取消超时错误直接返回给控制器，且不调用 mapper 删除。

`SessionController.deleteSession(...)` 不再用 `Mono.fromRunnable` 包装同步删除，直接返回该 `Mono<Void>`。

- [ ] **Step 6: 运行后端单元测试确认通过。**

Run: `mvn -q -f backend/pom.xml -Dtest=ChatServiceTest,SessionServiceTest,SessionControllerTest test`

Expected: PASS。

- [ ] **Step 7: 提交。**

```bash
git add backend/src/main/java/com/example/myagent/chat/ChatService.java backend/src/main/java/com/example/myagent/session/SessionService.java backend/src/main/java/com/example/myagent/session/SessionController.java backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java backend/src/test/java/com/example/myagent/session/SessionServiceTest.java
git commit -m "stop session execution before deletion"
```

## Task 3: 前端中止本地流并按删除结果更新 UI

**Files:**
- Modify: `frontend/src/api/chat.ts`
- Modify: `frontend/src/stores/chat.ts`
- Modify: `frontend/src/stores/sessions.ts`
- Modify: `frontend/src/views/ChatView.vue`
- Modify: `frontend/src/components/SessionSidebar.vue`
- Modify: `frontend/src/stores/__tests__/chat.spec.ts`
- Create: `frontend/src/stores/__tests__/sessions.spec.ts`

**Interfaces:**

```ts
export function streamNdjson(
  path: string,
  body: unknown,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal
): Promise<void>

abortSession(sessionId: string): void
isCancellingSession(sessionId: string): boolean
```

- [ ] **Step 1: 写失败测试：删除会话时 fetch 收到 AbortSignal。**

```ts
it('aborts the active stream for a deleted session', async () => {
  const controller = new AbortController()
  mockFetch.mockReturnValue(pendingResponse())

  const request = streamNdjson('/api/chat/sessions/s_1/stream', { message: 'hello' }, vi.fn(), controller.signal)
  controller.abort()

  expect(mockFetch.mock.calls[0][1]?.signal).toBe(controller.signal)
  await expect(request).rejects.toThrow()
})
```

- [ ] **Step 2: 运行测试确认失败。**

Run: `npm test -- --run src/stores/__tests__/chat.spec.ts`

Expected: FAIL，因为 `streamNdjson` 尚未接受或传递 signal。

- [ ] **Step 3: 最小实现 API 和 chat store。**

向 `streamNdjson`、`streamChat`、`confirmToolCall` 添加可选 `signal` 并传给 `fetch`。chat store 在开始聊天/确认时创建每会话 controller；新增 `abortSession(sessionId)` 调用 `abort()` 并标记该会话取消中。捕获 `AbortError` 且会话处于取消中时不追加“发送失败”事件；`finally` 清理 controller。

- [ ] **Step 4: 写失败测试：DELETE 失败时不清理会话。**

```ts
it('keeps a session when server-side cancellation is still running', async () => {
  deleteSessionApi.mockRejectedValue(new ApiError('Session cancellation is still in progress', 409, null))
  const store = useSessionsStore()
  store.sessions = [session('s_1')]

  await expect(store.deleteSession('s_1')).rejects.toThrow('Session cancellation is still in progress')

  expect(store.sessions).toHaveLength(1)
  expect(store.error).toContain('Session cancellation is still in progress')
})
```

- [ ] **Step 5: 最小实现会话 UI 状态。**

`sessions` store 用 `deletingSessionId` 防止重复删除，并在 create/delete 的 catch 中写入 `error` 后重新抛出。`ChatView.deleteSession` 先调用 `chat.abortSession(sessionId)`，再等待 `sessions.deleteSession(sessionId)`；仅成功时调用 `chat.clearSession(sessionId)`。`SessionSidebar` 接收删除中的 sessionId，禁用对应删除按钮。Composer 对取消中的当前会话保持禁用。

- [ ] **Step 6: 运行前端测试与类型检查确认通过。**

Run: `npm run typecheck && npm test`

Expected: typecheck PASS；Vitest 全部 PASS。

- [ ] **Step 7: 提交。**

```bash
git add frontend/src/api/chat.ts frontend/src/stores/chat.ts frontend/src/stores/sessions.ts frontend/src/views/ChatView.vue frontend/src/components/SessionSidebar.vue frontend/src/stores/__tests__/chat.spec.ts frontend/src/stores/__tests__/sessions.spec.ts
git commit -m "abort local streams while deleting sessions"
```

## Task 4: 跨实例与完整回归验证

**Files:**
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java`
- Modify: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java`
- Modify: `backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java`

- [ ] **Step 1: 写 Redis 集成测试：实例 A 发布取消后，实例 B 的本机句柄被取消。**

```java
@Test
void cancellationPublishedByOneCoordinatorStopsExecutionTrackedByAnother() {
  Disposable execution = coordinatorB.track(1L, "s_1", Flux.never()).subscribe();

  coordinatorA.cancelAndAwait(1L, "s_1").block();

  assertThat(execution.isDisposed()).isTrue();
}
```

- [ ] **Step 2: 运行集成测试确认失败。**

Run: `mvn -q -f backend/pom.xml -Dtest=RedisSessionExecutionCoordinatorTest test`

Expected: FAIL，直到 Redis 发布/订阅和活跃记录等待都已实现。

- [ ] **Step 3: 完成 TTL 续期、取消消息订阅和测试等待条件。**

测试不得依赖固定 `Thread.sleep`；使用 Awaitility 不可新增依赖时，以 Reactor `StepVerifier`、`CountDownLatch` 或轮询断言等待消息消费。测试结束必须 dispose 两个 coordinator 的消息订阅。

- [ ] **Step 4: 运行完整验证。**

Run: `mvn -q -f backend/pom.xml test`

Expected: 非 Docker 环境下记录 Testcontainers 无法连接 Docker；Docker 可用时所有后端测试 PASS。

Run: `npm run typecheck && npm test`

Expected: PASS。

- [ ] **Step 5: 提交。**

```bash
git add backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java backend/src/test/java/com/example/myagent/session/RedisSessionExecutionCoordinatorTest.java
git commit -m "verify distributed session cancellation"
```

## 计划自检

- 规格覆盖：Task 1 实现 Redis 跨实例协调、取消标记、TTL 和本机句柄；Task 2 接入聊天、确认和删除顺序；Task 3 实现浏览器中止与仅成功清理 UI；Task 4 覆盖跨实例验证。
- 占位符检查：无 TBD、TODO 或未定义的后续实现步骤。
- 类型一致性：所有后续任务使用 Task 1 定义的 `SessionExecutionCoordinator.track/cancelAndAwait/rejectIfCancelled` 接口；前端信号接口在 Task 3 定义后使用。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-18-session-execution-cancellation.md`.

Two execution options:

1. Subagent-Driven (recommended) — 每个任务使用独立子代理并在任务间审查。
2. Inline Execution — 在当前会话按任务逐项执行并设置审查检查点。
