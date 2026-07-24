# 工具确认恢复失败重试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅当 Agent 恢复事件源尚未订阅时，把失败的工具确认安全恢复为可重试；一旦可能开始模型或工具执行，就永久保持已消费。

**Architecture:** 保留 Redis 中 `PENDING → PROCESSING → CONSUMED` 三态和 processing token。`ChatService` 先构造 `AgentExecution` 并交给现有 `SessionExecutionCoordinator` 注册，直到协调器订阅事件源 supplier 时才原子 `consume()`；提交前的错误通过新 Lua 操作按状态和 token 条件回滚，提交后或结果不确定时 fail-closed。

**Tech Stack:** Java 21、Spring WebFlux、Project Reactor、Spring Data Redis/Lua、JUnit 5、Mockito、Testcontainers、Vue 3、Pinia、TypeScript、Vitest。

## Global Constraints

- 仅在 Agent 事件源尚未订阅时允许重试。
- 事件源订阅后，即使尚未向前端发送事件，也保持 `CONSUMED`。
- 不增加新的持久化状态，不建立通用工具幂等账本。
- Redis 结果不确定时 fail-closed，不重新开放可能已经执行的确认。
- 保持现有 `SESSION_CANCELLING` 会话取消语义。
- 不修改用户已有的 `.claude/` 未跟踪目录。

## 文件结构

- 修改 `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`：增加同 token 的条件回滚 Lua 和 `rollbackIfProcessing` API。
- 修改 `backend/src/main/java/com/example/myagent/chat/ChatService.java`：移动消费边界，区分可重试、已消费和会话取消错误。
- 修改 `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java`：验证条件回滚 API 的 Redis 参数和结果映射。
- 修改 `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java`：用真实 Redis 验证同 token 回滚及 fail-closed。
- 修改 `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`：覆盖网关构造、协调器注册、消费结果不确定和事件源启动后的失败。
- 修改 `backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java`：覆盖真实 Redis 与多实例协调器组合下的端到端状态边界。
- 修改 `frontend/src/stores/__tests__/chat.spec.ts`：确认现有 store 对 503 可重试、409 已消费、会话取消三种响应保持正确 UI 状态；若回归测试通过，不改生产代码。

---

### Task 1: Redis 条件回滚

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`
- Test: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java`
- Test: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java`

**Interfaces:**
- Consumes: 现有 confirmation key、`PROCESSING` 状态和 processing token。
- Produces: `Mono<Boolean> rollbackIfProcessing(String confirmationId, String processingToken)`；仅真正从同 token 的 `PROCESSING` 改回 `PENDING` 时返回 `true`，其余状态返回 `false`。

- [ ] **Step 1: 写失败的单元测试**

在 `ToolConfirmationServiceTest` 增加：

```java
@Test
void rollbackIfProcessingReturnsWhetherTheSameTokenWasReleased() {
  when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
      .thenReturn(Flux.just(1L), Flux.just(0L), Flux.just(0L));

  StepVerifier.create(service.rollbackIfProcessing("id", "token"))
      .expectNext(true)
      .verifyComplete();
  StepVerifier.create(service.rollbackIfProcessing("id", "wrong-token"))
      .expectNext(false)
      .verifyComplete();
  StepVerifier.create(service.rollbackIfProcessing("missing", "token"))
      .expectNext(false)
      .verifyComplete();

  verify(redisTemplate).execute(
      any(RedisScript.class),
      eq(List.of("prefix:tool-confirmations:id")),
      eq(List.of("token")));
}
```

- [ ] **Step 2: 运行单元测试并确认失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -Dtest=ToolConfirmationServiceTest test
```

Working directory: `backend`

Expected: FAIL，提示 `rollbackIfProcessing` 不存在。

- [ ] **Step 3: 实现最小条件回滚 API**

在 `ToolConfirmationService` 增加返回 `Long` 的 Lua 脚本：

```java
private static final DefaultRedisScript<Long> ROLLBACK_IF_PROCESSING_SCRIPT =
    new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        local ttl = redis.call('PTTL', KEYS[1])
        if not value or ttl <= 0 then return 0 end
        local data = cjson.decode(value)
        if data.status ~= 'PROCESSING' or data.processingToken ~= ARGV[1] then return 0 end
        data.status = 'PENDING'
        data.processingToken = nil
        data.leaseExpiresAtEpochMs = nil
        redis.call('SET', KEYS[1], cjson.encode(data), 'PX', ttl)
        return 1
        """, Long.class);
```

并增加：

```java
public Mono<Boolean> rollbackIfProcessing(String confirmationId, String processingToken) {
  return redisTemplate.execute(
          ROLLBACK_IF_PROCESSING_SCRIPT,
          List.of(key(confirmationId)),
          List.of(processingToken))
      .next()
      .switchIfEmpty(Mono.error(
          new IllegalStateException("Failed to inspect tool confirmation rollback")))
      .map(result -> result == 1L);
}
```

保留现有 `release()`：它用于非法 decisions，并继续把状态不匹配作为客户端冲突返回；新 API 专用于故障补偿，不用冲突覆盖原始错误。

- [ ] **Step 4: 运行单元测试并确认通过**

Run: Task 1 Step 2 的 Maven 命令。

Expected: `ToolConfirmationServiceTest` PASS。

- [ ] **Step 5: 写真实 Redis 的失败测试**

在 `ToolConfirmationRedisIntegrationTest` 增加两个测试：

```java
@Test
void rollbackIfProcessingOnlyReleasesTheMatchingClaimAndPreservesTtl() throws Exception {
  ToolConfirmationRecord created = create();
  String key = key(created);
  ToolConfirmationClaim claim =
      service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
  Duration before = ttl(key);

  assertThat(service.rollbackIfProcessing(
      created.confirmationId(), "wrong-token").block()).isFalse();
  assertThat(json(key).get("status").asText()).isEqualTo("PROCESSING");

  assertThat(service.rollbackIfProcessing(
      created.confirmationId(), claim.processingToken()).block()).isTrue();
  JsonNode pending = json(key);
  assertThat(pending.get("status").asText()).isEqualTo("PENDING");
  assertThat(pending.has("processingToken")).isFalse();
  assertThat(pending.has("leaseExpiresAtEpochMs")).isFalse();
  assertTtlNotReset(before, ttl(key));
}

@Test
void rollbackIfProcessingDoesNotReopenAConsumedOrMissingRecord() throws Exception {
  ToolConfirmationRecord created = create();
  ToolConfirmationClaim claim =
      service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
  service.consume(created.confirmationId(), claim.processingToken(), List.of()).block();

  assertThat(service.rollbackIfProcessing(
      created.confirmationId(), claim.processingToken()).block()).isFalse();
  assertThat(json(key(created)).get("status").asText()).isEqualTo("CONSUMED");
  assertThat(service.rollbackIfProcessing("missing", claim.processingToken()).block()).isFalse();
}
```

- [ ] **Step 6: 运行 Redis 集成测试**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -Dtest=ToolConfirmationRedisIntegrationTest test
```

Working directory: `backend`

Expected: Docker 可用时测试 PASS；同 token 的 `PROCESSING` 可回滚，`CONSUMED`、错误 token 和缺失记录均返回 `false`。

- [ ] **Step 7: 提交 Task 1**

```powershell
git add backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java
git commit -m "add conditional tool confirmation rollback"
```

### Task 2: 把消费边界移动到事件源订阅前

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Test: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `rollbackIfProcessing`、现有 `SessionExecutionCoordinator.track` supplier、`AgentExecution.events()` 和 `completion()`。
- Produces: 响应头错误码 `TOOL_CONFIRMATION_RETRYABLE`、`TOOL_CONFIRMATION_CONSUMED`；保留 `SESSION_CANCELLING`。

- [ ] **Step 1: 用测试固定四个安全边界**

修改旧的“先消费再调用网关”断言，并在 `ChatServiceTest` 增加：

```java
@Test
void confirmGatewayConstructionFailureRollsBackAndReturnsRetryableCode() {
  ToolConfirmationClaim claim = stubValidClaim();
  when(chatAgentGateway.confirmExecution(any()))
      .thenThrow(new IllegalStateException("gateway construction failed"));
  when(toolConfirmationService.rollbackIfProcessing(
      "confirm_123", claim.processingToken())).thenReturn(Mono.just(true));

  StepVerifier.create(newChatService().confirm(
          USER, "s_123", "confirm_123", requested(true)))
      .expectErrorSatisfies(error -> assertErrorCode(
          error, HttpStatus.SERVICE_UNAVAILABLE, "TOOL_CONFIRMATION_RETRYABLE"))
      .verify();
  verify(toolConfirmationService, never()).consume(any(), any(), anyList());
}

@Test
void confirmCoordinatorRegistrationFailureRollsBackBeforeSourceSubscription() {
  ToolConfirmationClaim claim = stubValidClaim();
  when(sessionExecutionCoordinator.track(anyLong(), anyString(), any(), any()))
      .thenReturn(Flux.error(new IllegalStateException("registration failed")));
  when(toolConfirmationService.rollbackIfProcessing(
      "confirm_123", claim.processingToken())).thenReturn(Mono.just(true));

  StepVerifier.create(newChatService().confirm(
          USER, "s_123", "confirm_123", requested(true)))
      .expectErrorSatisfies(error -> assertErrorCode(
          error, HttpStatus.SERVICE_UNAVAILABLE, "TOOL_CONFIRMATION_RETRYABLE"))
      .verify();
  verify(toolConfirmationService, never()).consume(any(), any(), anyList());
}

@Test
void uncertainConsumeResultDoesNotReopenTheConfirmation() {
  ToolConfirmationClaim claim = stubValidClaim();
  when(toolConfirmationService.consume(
      "confirm_123", claim.processingToken(), persisted(true)))
      .thenReturn(Mono.error(new IllegalStateException("response lost")));
  when(toolConfirmationService.rollbackIfProcessing(
      "confirm_123", claim.processingToken())).thenReturn(Mono.just(false));

  StepVerifier.create(newChatService().confirm(
          USER, "s_123", "confirm_123", requested(true)))
      .expectErrorSatisfies(error -> assertErrorCode(
          error, HttpStatus.CONFLICT, "TOOL_CONFIRMATION_CONSUMED"))
      .verify();
  verify(chatAgentGateway, never()).confirm(any());
}

@Test
void eventFailureAfterConsumptionStaysConsumedAndReturnsAnErrorEvent() {
  ToolConfirmationClaim claim = stubValidClaim();
  when(toolConfirmationService.consume(
      "confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
  when(chatAgentGateway.confirmExecution(any())).thenReturn(new AgentExecution<>(
      Flux.error(new IllegalStateException("tool failed")), Mono.empty()));

  StepVerifier.create(newChatService().confirm(
          USER, "s_123", "confirm_123", requested(true)))
      .expectNext(StreamEventDto.error("tool failed"))
      .verifyComplete();
  verify(toolConfirmationService, never()).rollbackIfProcessing(any(), any());
}

private ToolConfirmationClaim stubValidClaim() {
  ToolConfirmationClaim claim = claim("reply_123", "tool_123");
  when(sessionService.requireOwnedSession(USER, "s_123")).thenReturn(
      new ChatSessionEntity(
          "s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
  when(permissionService.getModeForOwnedSession("s_123"))
      .thenReturn(PermissionMode.DEFAULT);
  when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123"))
      .thenReturn(Mono.just(claim));
  return claim;
}

private void assertErrorCode(
    Throwable error, HttpStatus status, String expectedCode) {
  assertThat(error).isInstanceOf(ResponseStatusException.class);
  ResponseStatusException responseError = (ResponseStatusException) error;
  assertThat(responseError.getStatusCode()).isEqualTo(status);
  assertThat(responseError.getHeaders().getFirst("X-Error-Code"))
      .isEqualTo(expectedCode);
}
```

给测试增加 `any`、`anyList`、`anyLong`、`anyString`、`never` 的 Mockito 静态导入。不能让协调器 mock 在“注册失败”测试里调用 source supplier。

- [ ] **Step 2: 运行 ChatServiceTest 并确认失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -Dtest=ChatServiceTest test
```

Working directory: `backend`

Expected: FAIL；旧代码会在网关和协调器注册前消费，且没有条件回滚及稳定错误码。

- [ ] **Step 3: 实现恢复尝试状态和错误映射**

在 `ChatService` 增加私有 `AtomicBoolean consumed`，并把恢复流程改为以下顺序：

```java
AtomicBoolean consumed = new AtomicBoolean();
Flux<StreamEventDto> resumed = Flux.defer(() -> {
  AgentExecution<StreamEventDto> execution =
      chatAgentGateway.confirmExecution(request);
  return sessionExecutionCoordinator.track(
      currentUser.id(),
      sessionId,
      () -> toolConfirmationService
          .consume(confirmationId, claim.processingToken(), persisted)
          .doOnSuccess(ignored -> consumed.set(true))
          .thenMany(execution.events().onErrorResume(
              error -> Flux.just(StreamEventDto.error(errorMessage(error))))),
      execution::completion)
      .concatWith(Flux.defer(() -> consumed.get()
          ? Flux.empty()
          : Flux.error(new IllegalStateException(
              "Confirmation execution ended before its event source started"))));
});
return resumed.onErrorResume(error -> recoverConfirmationFailure(
        confirmationId, claim.processingToken(), consumed.get(), error))
    .doOnCancel(() -> {
      if (!consumed.get()) {
        toolConfirmationService
            .rollbackIfProcessing(confirmationId, claim.processingToken())
            .subscribe(ignored -> {}, ignored -> {});
      }
    });
```

`recoverConfirmationFailure` 的最小行为：

```java
private Flux<StreamEventDto> recoverConfirmationFailure(
    String confirmationId,
    String processingToken,
    boolean consumed,
    Throwable original) {
  if (consumed) {
    return Flux.error(consumedFailure(original));
  }
  return toolConfirmationService.rollbackIfProcessing(confirmationId, processingToken)
      .onErrorReturn(false)
      .flatMapMany(rolledBack -> {
        if (rolledBack && isSessionCancelling(original)) {
          return Flux.error(original);
        }
        return Flux.error(rolledBack
            ? retryableFailure(original)
            : consumedFailure(original));
      });
}
```

增加两个私有 `ResponseStatusException` 子类：

```java
private static final class ToolConfirmationRetryableException
    extends ResponseStatusException {
  private final HttpHeaders headers = new HttpHeaders();

  private ToolConfirmationRetryableException(Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, errorMessage(cause), cause);
    headers.set("X-Error-Code", "TOOL_CONFIRMATION_RETRYABLE");
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }
}

private static final class ToolConfirmationConsumedException
    extends ResponseStatusException {
  private final HttpHeaders headers = new HttpHeaders();

  private ToolConfirmationConsumedException(Throwable cause) {
    super(HttpStatus.CONFLICT, errorMessage(cause), cause);
    headers.set("X-Error-Code", "TOOL_CONFIRMATION_CONSUMED");
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }
}
```

`isSessionCancelling` 通过 `ResponseStatusException.getHeaders()` 检查 `SESSION_CANCELLING`，确保协调器注册因会话取消失败时，先回滚确认，再保留原取消错误。

在取消信号且 `consumed == false` 时，使用 `doOnCancel` 触发同一个条件回滚；回滚与 `consume()` 竞争也安全，因为两个 Lua 只有一个能匹配同 token 的 `PROCESSING`。

- [ ] **Step 4: 运行 ChatServiceTest 并确认通过**

Run: Task 2 Step 2 的 Maven 命令。

Expected: `ChatServiceTest` PASS；Mockito 严格校验无未使用 stub。

- [ ] **Step 5: 提交 Task 2**

```powershell
git add backend/src/main/java/com/example/myagent/chat/ChatService.java backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java
git commit -m "make pre-start confirmation failures retryable"
```

### Task 3: 真实 Redis 和多实例协调边界

**Files:**
- Test: `backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的原子回滚、Task 2 的延迟消费流程、现有 Redis 多实例 `SessionExecutionCoordinator`。
- Produces: 端到端证据，证明注册前失败可重新 claim，订阅后失败不可重新 claim。

- [ ] **Step 1: 增加注册前失败可重试集成测试**

在 `ChatServiceConfirmationIntegrationTest` 使用真实 `ToolConfirmationService` 创建记录，给 `ChatService` 注入一个不会调用 source supplier、直接返回注册错误的协调器：

```java
String confirmationId = toolConfirmationService.create(
    USER.id(),
    SESSION_ID,
    "reply",
    List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
    ConfirmationKind.USER_CONFIRM).block().confirmationId();
ChatAgentGateway gateway = mock(ChatAgentGateway.class);
when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
    .thenReturn(new AgentExecution<>(Flux.never(), Mono.empty()));
SessionExecutionCoordinator rejectingCoordinator = mock(SessionExecutionCoordinator.class);
when(rejectingCoordinator.track(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
    .thenReturn(Flux.error(new IllegalStateException("registration failed")));

StepVerifier.create(new ChatService(
        sessionService(),
        gateway,
        permissionService(),
        toolConfirmationService,
        rejectingCoordinator).confirm(
        USER,
        SESSION_ID,
        confirmationId,
        List.of(new ToolConfirmationDecisionRequest("call", true))))
    .expectErrorSatisfies(error -> assertThat(
        ((ResponseStatusException) error).getHeaders().getFirst("X-Error-Code"))
        .isEqualTo("TOOL_CONFIRMATION_RETRYABLE"))
    .verify();

ToolConfirmationClaim retry =
    toolConfirmationService.claim(USER.id(), SESSION_ID, confirmationId).block();
assertThat(retry).isNotNull();
toolConfirmationService.release(confirmationId, retry.processingToken()).block();
```

- [ ] **Step 2: 增加订阅后失败不可重试集成测试**

使用真实协调器并让 `AgentExecution.events()` 订阅后立即失败：

```java
String confirmationId = toolConfirmationService.create(
    USER.id(),
    SESSION_ID,
    "reply",
    List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
    ConfirmationKind.USER_CONFIRM).block().confirmationId();
ChatAgentGateway gateway = mock(ChatAgentGateway.class);
Sinks.Empty<Void> completion = Sinks.empty();
when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
    .thenReturn(new AgentExecution<>(
        Flux.<StreamEventDto>error(new IllegalStateException("started failure"))
            .doFinally(ignored -> completion.tryEmitEmpty()),
        completion.asMono()));
RedisSessionExecutionCoordinator realCoordinator = coordinator();
try {
  ChatService chatService = new ChatService(
      sessionService(),
      gateway,
      permissionService(),
      toolConfirmationService,
      realCoordinator);

  StepVerifier.create(chatService.confirm(
          USER,
          SESSION_ID,
          confirmationId,
          List.of(new ToolConfirmationDecisionRequest("call", true))))
      .expectNext(StreamEventDto.error("started failure"))
      .verifyComplete();

  assertThatThrownBy(() -> toolConfirmationService.claim(
      USER.id(), SESSION_ID, confirmationId).block())
      .isInstanceOfSatisfying(
          ResponseStatusException.class,
          error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
} finally {
  realCoordinator.destroy();
}
```

- [ ] **Step 3: 运行集成测试**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -Dtest=ChatServiceConfirmationIntegrationTest test
```

Working directory: `backend`

Expected: Docker 可用时 PASS；注册前失败后可重新 claim，事件源订阅后记录保持 `CONSUMED`。

- [ ] **Step 4: 提交 Task 3**

```powershell
git add backend/src/test/java/com/example/myagent/chat/ChatServiceConfirmationIntegrationTest.java
git commit -m "test confirmation resume retry boundary"
```

### Task 4: 锁定前端对服务端安全结论的现有行为

**Files:**
- Test: `frontend/src/stores/__tests__/chat.spec.ts`

**Interfaces:**
- Consumes: Task 2 的 HTTP 503 + `TOOL_CONFIRMATION_RETRYABLE`、HTTP 409 + `TOOL_CONFIRMATION_CONSUMED`，以及现有 `SESSION_CANCELLING`。
- Produces: retryable 时 `event.consumed = false`；consumed、普通 404/409 时为 `true`；session cancelling 继续锁定会话。

- [ ] **Step 1: 写 Pinia store 回归测试**

在 `chat.spec.ts` 增加：

```ts
it('keeps a safely rolled-back confirmation retryable', async () => {
  vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(
    new chatApi.StreamRequestError(
      'registration failed',
      503,
      'TOOL_CONFIRMATION_RETRYABLE'
    )
  )
  const store = useChatStore()
  const event = toolEvent()
  selectAll(store, event)

  await store.confirmTool('s1', 'assistant-1', event)

  expect(event).toMatchObject({ consumed: false, confirming: false })
  expect(store.cancellingSessionIds.s1).toBeUndefined()
})

it('keeps a fail-closed confirmation consumed', async () => {
  vi.spyOn(chatApi, 'confirmToolCall').mockRejectedValue(
    new chatApi.StreamRequestError(
      'consume result was uncertain',
      409,
      'TOOL_CONFIRMATION_CONSUMED'
    )
  )
  const store = useChatStore()
  const event = toolEvent()
  selectAll(store, event)

  await store.confirmTool('s1', 'assistant-1', event)

  expect(event).toMatchObject({ consumed: true, confirming: false })
  expect(store.cancellingSessionIds.s1).toBeUndefined()
})
```

保留现有 `SESSION_CANCELLING`、普通 404/409、HTTP 400 和 NDJSON error event 测试。

- [ ] **Step 2: 运行前端测试并确认现有逻辑满足契约**

Run:

```powershell
npm test -- --run src/stores/__tests__/chat.spec.ts
```

Working directory: `frontend`

Expected: 新增测试 PASS。当前 `chat.ts` 已将普通 503 视为可重试、普通 409 视为已消费；稳定错误码用于明确服务端语义，不需要扩大前端生产改动。

- [ ] **Step 3: 核对生产代码无需修改**

确认 `confirmTool` 仍满足以下现有逻辑：

```ts
if (error instanceof StreamRequestError && error.code === 'SESSION_CANCELLING') {
  this.cancellingSessionIds[sessionId] = true
}
if (error instanceof StreamRequestError && (error.status === 404 || error.status === 409)) {
  event.consumed = true
} else {
  event.consumed = false
}
```

503 因不属于 404/409 而恢复按钮；409 因服务端 fail-closed 而禁止重复提交。不要为已满足的行为修改 `chat.ts`，也不要改变 decisions、错误事件追加、会话锁和 abort 分支。

- [ ] **Step 4: 运行前端定向测试和类型检查**

Run:

```powershell
npm test -- --run src/stores/__tests__/chat.spec.ts
npm run typecheck
```

Working directory: `frontend`

Expected: 定向 Vitest 全部 PASS，`vue-tsc` 和 `tsc` 退出码均为 0。

- [ ] **Step 5: 提交 Task 4**

```powershell
git add frontend/src/stores/__tests__/chat.spec.ts
git commit -m "test confirmation resume error states"
```

### Task 5: 全量回归与分支交付

**Files:**
- Verify only; do not modify unrelated files.

**Interfaces:**
- Consumes: Tasks 1–4 的全部提交。
- Produces: 后端全量测试、前端全量测试和构建证据；合并回 `codex/skill-review-draft-fingerprint`。

- [ ] **Step 1: 运行后端全量测试**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
```

Working directory: `backend`

Expected: 全部测试 PASS，包含 Testcontainers Redis 测试；没有 failures 或 errors。

- [ ] **Step 2: 运行前端全量测试和构建**

Run:

```powershell
npm test
npm run build
```

Working directory: `frontend`

Expected: 全部 Vitest PASS，typecheck 与 Vite build 退出码均为 0。

- [ ] **Step 3: 检查变更范围**

Run:

```powershell
git status --short
git diff --check codex/skill-review-draft-fingerprint...HEAD
git diff --stat codex/skill-review-draft-fingerprint...HEAD
```

Expected: `.claude/` 仍是未跟踪且未被暂存；没有空白错误；变更只覆盖本计划列出的实现、测试、设计文档和计划文档。

- [ ] **Step 4: 进行代码审查**

使用 `superpowers:requesting-code-review` 检查：

- `consume()` 是否确实发生在协调器注册成功后、事件源订阅前。
- 所有提交前错误是否只在同 token 的 `PROCESSING` 上回滚。
- `consume()` 不确定结果和提交后错误是否 fail-closed。
- 多实例并发是否仍由 Redis Lua 原子化。
- 前端是否只对 `TOOL_CONFIRMATION_RETRYABLE` 恢复确认按钮。

Expected: 没有 P1/P2 问题；若有，修复后重跑受影响测试和全量回归。

- [ ] **Step 5: 合并回集成分支**

```powershell
git checkout codex/skill-review-draft-fingerprint
git merge --no-ff codex/retry-tool-confirmation-resume
git branch -d codex/retry-tool-confirmation-resume
```

Expected: 产生非 fast-forward merge commit，集成分支包含本修复，功能分支被安全删除，`.claude/` 保持未跟踪。
