# 工具调用单次审批闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 AgentScope 工具调用补齐“允许一次/拒绝一次—恢复原执行流”的完整闭环，并使用 Redis 持久化待确认记录。

**Architecture:** 后端在收到 `RequireUserConfirmEvent` 时，把可信的工具调用快照写入 Redis，并向前端发出带 `confirmationId` 的 `permission_required` 事件。确认接口通过 Redis Lua 租约原子取得记录，重建同一用户/会话的 HarnessAgent，以 `Msg.METADATA_CONFIRM_RESULTS` 携带 `ConfirmResult` 恢复 RC4 的 pending-tool execution；成功后原子消费，失败时释放租约。前端复用现有 NDJSON 流解析器，把恢复事件追加到原 assistant 消息。

**Tech Stack:** Java 21、Spring Boot WebFlux 3.3、Reactive Spring Data Redis、AgentScope Java 2.0.0-RC4、JUnit 5、Mockito、Reactor Test、Vue 3、Pinia、TypeScript、Vitest、Vue Test Utils。

## Global Constraints

- `confirmationId` 必须由后端使用 `UUID.randomUUID().toString()` 生成，不得使用 `replyId` 或 `toolCallId` 充当确认编号。
- Redis 键固定为 `{agent.state-store.redis.key-prefix}tool-confirmations:{confirmationId}`，值使用 JSON，不使用 Java 原生序列化。
- 待确认记录 TTL 固定为 30 分钟；状态转换必须保留原始到期时间，查询和失败重试不得续期。
- `PROCESSING` 租约固定为 30 秒；Lua 脚本必须校验 `userId`、`sessionId`、状态和处理令牌。
- HTTP 请求只能提交 `confirmed`，恢复时只能使用 Redis 中的工具调用快照重建 `ToolUseBlock`。
- 第一版只处理 `RequireUserConfirmEvent`；`RequireExternalExecutionEvent` 继续只展示，不增加任意 `ToolResultBlock` 回传能力。
- 同一个 `RequireUserConfirmEvent` 若包含多个工具调用，只登记第一个；恢复后 AgentScope 会对仍待处理的工具重新发出确认事件，避免一次用户选择隐式批准多个工具。
- 不修改会话级 `PermissionMode`，不升级 AgentScope，不引入“始终允许/拒绝”规则。
- 不修改或提交现有未跟踪目录 `.claude/`。

---

## 文件结构

### 后端新增

- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolCallSnapshot.java`：可 JSON 持久化的工具调用可信快照，并负责重建 `ToolUseBlock`。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ConfirmationKind.java`：确认类型枚举。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationStatus.java`：Redis 状态枚举。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationRecord.java`：Redis 持久化记录模型。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationClaim.java`：一次成功租约的记录与处理令牌。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`：Redis 创建、读取、原子取得、完成和释放操作。
- `backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java`：内部恢复请求，携带可信快照和审批结果。
- `backend/src/main/java/com/example/myagent/chat/ToolConfirmationRequest.java`：HTTP 请求体，只含 `confirmed`。
- `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java`：Redis 键、TTL、Lua 参数和状态结果测试。

### 后端修改

- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`：启用 pending-tool recovery，并实现确认流执行。
- `backend/src/main/java/com/example/myagent/agent/AgentScopeStreamExecutor.java`：增加 `confirm(...)`。
- `backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`：扩展权限事件载荷。
- `backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java`：外部执行事件保持只读提示；用户确认交由网关登记。
- `backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`：增加确认恢复接口。
- `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`：登记用户确认事件，并映射恢复流。
- `backend/src/main/java/com/example/myagent/chat/StubChatAgentGateway.java`：提供可测试的确认恢复桩。
- `backend/src/main/java/com/example/myagent/chat/ChatService.java`：编排会话归属、Redis 租约、恢复、完成与释放。
- `backend/src/main/java/com/example/myagent/chat/ChatController.java`：增加流式确认接口。
- `backend/src/test/java/com/example/myagent/chat/AgentEventMapperTest.java`：更新外部执行提示断言。
- `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`：覆盖确认登记和 RC4 恢复请求映射。
- `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`：覆盖租约生命周期和失败释放。
- `backend/src/test/java/com/example/myagent/chat/ChatControllerTest.java`：覆盖请求校验、归属错误和 NDJSON 响应。
- `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`：覆盖 pending-tool recovery 配置和确认消息构造。

### 前端新增

- `frontend/src/components/__tests__/ToolEventCard.spec.ts`：权限卡片渲染、允许、拒绝和禁用状态测试。
- `frontend/src/stores/__tests__/chat.spec.ts`：恢复事件追加到原消息的测试。
- `frontend/src/test/setup.ts`：Vitest DOM 测试初始化。

### 前端修改

- `frontend/package.json`：增加 `test` 脚本和最小测试依赖。
- `frontend/vite.config.ts`：增加 Vitest `jsdom` 配置。
- `frontend/tsconfig.app.json`：纳入测试文件与 Vitest 类型。
- `frontend/src/api/chat.ts`：扩展权限事件类型，并增加确认 NDJSON 请求。
- `frontend/src/stores/chat.ts`：保存确认字段，增加 `confirmTool(...)`。
- `frontend/src/components/ToolEventCard.vue`：展示工具与参数，增加单次允许/拒绝按钮。
- `frontend/src/components/ChatTranscript.vue`：向权限卡片传递 `sessionId` 和 `messageId`。
- `frontend/src/views/ChatView.vue`：向聊天记录组件传递当前会话编号。

---

### Task 1: Redis 待确认记录与原子租约

**Files:**
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolCallSnapshot.java`
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ConfirmationKind.java`
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationStatus.java`
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationRecord.java`
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationClaim.java`
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`
- Create: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java`

**Interfaces:**
- Consumes: `ReactiveStringRedisTemplate`、Spring Boot `ObjectMapper`、`AgentProperties.StateStore.Redis.keyPrefix()`。
- Produces: `Mono<ToolConfirmationRecord> create(...)`、`Mono<ToolConfirmationClaim> claim(...)`、`Mono<Void> complete(...)`、`Mono<Void> release(...)`。

- [ ] **Step 1: 为快照重建、UUID、TTL 和租约状态编写失败测试**

测试必须断言以下行为：

```java
@Test
void createUsesUuidKeyAndThirtyMinuteTtl() {
  when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  when(valueOperations.set(anyString(), anyString(), eq(Duration.ofMinutes(30))))
      .thenReturn(Mono.just(true));

  ToolConfirmationRecord record =
      service.create(7L, "s_123", "reply-1", toolCall(), ConfirmationKind.USER_CONFIRM)
          .block();

  assertThat(record.confirmationId()).matches("[0-9a-f-]{36}");
  verify(valueOperations).set(
      eq("myagent:agent-state:tool-confirmations:" + record.confirmationId()),
      containsJson("\"userId\":7"),
      eq(Duration.ofMinutes(30)));
}

@Test
void snapshotRebuildsOriginalToolCall() {
  ToolUseBlock rebuilt = ToolCallSnapshot.from(toolCall()).toToolUseBlock();
  assertThat(rebuilt.getId()).isEqualTo("call-1");
  assertThat(rebuilt.getName()).isEqualTo("shell_command");
  assertThat(rebuilt.getInput()).isEqualTo(Map.of("command", "Get-ChildItem"));
}

@Test
void claimPassesOwnerSessionAndLeaseToAtomicScript() {
  when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
      .thenReturn(Flux.just(claimedJson()));

  ToolConfirmationClaim claim = service.claim(7L, "s_123", "confirmation-1").block();

  assertThat(claim.processingToken()).isNotBlank();
  verify(redisTemplate).execute(
      any(RedisScript.class),
      eq(List.of("myagent:agent-state:tool-confirmations:confirmation-1")),
      argThat(args -> args.contains("7") && args.contains("s_123") && args.contains("30")));
}
```

辅助 matcher `containsJson` 在测试类内实现为 `argThat(json -> json.contains(fragment))`，不要增加生产代码测试工具。

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -Dtest=ToolConfirmationServiceTest test
```

Expected: 编译失败，提示 `ToolConfirmationService`、`ToolConfirmationRecord` 等类型不存在。

- [ ] **Step 3: 实现最小记录模型和 Redis 服务**

模型采用以下确定签名：

```java
public record ToolCallSnapshot(String id, String name, Map<String, Object> input) {
  public static ToolCallSnapshot from(ToolUseBlock toolCall) {
    return new ToolCallSnapshot(toolCall.getId(), toolCall.getName(), Map.copyOf(toolCall.getInput()));
  }

  public ToolUseBlock toToolUseBlock() {
    return new ToolUseBlock(id, name, input);
  }
}

public enum ConfirmationKind { USER_CONFIRM, EXTERNAL_EXECUTION }
public enum ToolConfirmationStatus { PENDING, PROCESSING, CONSUMED }

public record ToolConfirmationRecord(
    String confirmationId,
    Long userId,
    String sessionId,
    String replyId,
    ToolCallSnapshot toolCall,
    ConfirmationKind kind,
    Instant createdAt,
    ToolConfirmationStatus status,
    String processingToken,
    Long leaseExpiresAtEpochMs,
    Boolean confirmed) {}

public record ToolConfirmationClaim(
    ToolConfirmationRecord record,
    String processingToken) {}
```

`ToolConfirmationService` 使用注入的 `ObjectMapper`，固定常量：

```java
static final Duration RECORD_TTL = Duration.ofMinutes(30);
static final Duration PROCESSING_LEASE = Duration.ofSeconds(30);

public Mono<ToolConfirmationRecord> create(
    Long userId, String sessionId, String replyId,
    ToolUseBlock toolCall, ConfirmationKind kind) {
  ToolConfirmationRecord record = new ToolConfirmationRecord(
      UUID.randomUUID().toString(), userId, sessionId, replyId,
      ToolCallSnapshot.from(toolCall), kind, Instant.now(),
      ToolConfirmationStatus.PENDING, null, null, null);
  return redisTemplate.opsForValue()
      .set(key(record.confirmationId()), write(record), RECORD_TTL)
      .filter(Boolean::booleanValue)
      .switchIfEmpty(Mono.error(new IllegalStateException("Failed to persist tool confirmation")))
      .thenReturn(record);
}
```

Lua 取得脚本返回记录 JSON、`__NOT_FOUND__`、`__NOT_OWNED__` 或 `__CONFLICT__`：

```lua
local current = redis.call('GET', KEYS[1])
if not current then return '__NOT_FOUND__' end
local ttl = redis.call('PTTL', KEYS[1])
if ttl <= 0 then return '__NOT_FOUND__' end
local record = cjson.decode(current)
if tostring(record.userId) ~= ARGV[1] or record.sessionId ~= ARGV[2] then
  return '__NOT_OWNED__'
end
local now = tonumber(ARGV[3])
if record.status == 'CONSUMED' then return '__CONFLICT__' end
if record.status == 'PROCESSING' and tonumber(record.leaseExpiresAtEpochMs) > now then
  return '__CONFLICT__'
end
record.status = 'PROCESSING'
record.processingToken = ARGV[4]
record.leaseExpiresAtEpochMs = now + tonumber(ARGV[5])
local updated = cjson.encode(record)
redis.call('SET', KEYS[1], updated, 'PX', ttl)
return updated
```

完成脚本必须校验处理令牌并保留 TTL：

```lua
local current = redis.call('GET', KEYS[1])
if not current then return '__NOT_FOUND__' end
local ttl = redis.call('PTTL', KEYS[1])
if ttl <= 0 then return '__NOT_FOUND__' end
local record = cjson.decode(current)
if record.status ~= 'PROCESSING' or record.processingToken ~= ARGV[1] then
  return '__CONFLICT__'
end
record.status = 'CONSUMED'
record.confirmed = ARGV[2] == 'true'
record.processingToken = nil
record.leaseExpiresAtEpochMs = nil
redis.call('SET', KEYS[1], cjson.encode(record), 'PX', ttl)
return '__OK__'
```

释放脚本同样校验令牌，把记录恢复为 `PENDING`：

```lua
local current = redis.call('GET', KEYS[1])
if not current then return '__NOT_FOUND__' end
local ttl = redis.call('PTTL', KEYS[1])
if ttl <= 0 then return '__NOT_FOUND__' end
local record = cjson.decode(current)
if record.status ~= 'PROCESSING' or record.processingToken ~= ARGV[1] then
  return '__CONFLICT__'
end
record.status = 'PENDING'
record.processingToken = nil
record.leaseExpiresAtEpochMs = nil
redis.call('SET', KEYS[1], cjson.encode(record), 'PX', ttl)
return '__OK__'
```

Java 将 `__NOT_FOUND__`/`__NOT_OWNED__` 转为 `ResponseStatusException(404)`，将 `__CONFLICT__` 转为 `ResponseStatusException(409)`。记录字段使用 `leaseExpiresAtEpochMs: Long`，避免 Lua 解析 ISO-8601 时间；相应地将 `ToolConfirmationRecord` 中的 `Instant leaseExpiresAt` 改为 `Long leaseExpiresAtEpochMs`。

- [ ] **Step 4: 运行 Redis 服务测试并确认通过**

Run: `mvn -q -Dtest=ToolConfirmationServiceTest test`

Expected: PASS，且 Mockito 验证创建写入携带 30 分钟 TTL，三个状态脚本均通过 `ReactiveStringRedisTemplate.execute(...)` 调用。

- [ ] **Step 5: 提交 Redis 存储任务**

```powershell
git add backend/src/main/java/com/example/myagent/toolconfirmation backend/src/test/java/com/example/myagent/toolconfirmation
git commit -m "feat: persist tool confirmations in Redis"
```

---

### Task 2: 登记 AgentScope 权限事件并扩展流协议

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/AgentEventMapperTest.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ToolConfirmationService.create(...)` 和 `ToolConfirmationRecord`。
- Produces: 带 `confirmationId`、`replyId`、`toolCallId`、`toolName`、`toolInput`、`kind` 的 `permission_required`。

- [ ] **Step 1: 编写用户确认登记失败测试**

把原 `AgentEventMapperTest.mapsConfirmRequestToPermissionRequiredEvent` 移到网关测试，并使用 mock 服务：

```java
@Test
void registersFirstUserConfirmationAndEmitsMetadata() {
  ToolUseBlock toolCall = new ToolUseBlock(
      "call-1", "shell_command", Map.of("command", "Get-ChildItem"));
  when(executor.stream(any(), any())).thenReturn(
      Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));
  when(confirmationService.create(7L, "s_123", "reply-1", toolCall,
      ConfirmationKind.USER_CONFIRM)).thenReturn(Mono.just(pendingRecord()));

  StreamEventDto event = gateway().stream(request()).blockFirst();

  assertThat(event.type()).isEqualTo("permission_required");
  assertThat(event.payload())
      .containsEntry("confirmationId", "confirmation-1")
      .containsEntry("replyId", "reply-1")
      .containsEntry("toolCallId", "call-1")
      .containsEntry("toolName", "shell_command")
      .containsEntry("permission", "shell_command")
      .containsEntry("kind", "USER_CONFIRM");
  assertThat(event.payload().get("toolInput"))
      .isEqualTo(Map.of("command", "Get-ChildItem"));
}
```

再增加一个包含两个 `ToolUseBlock` 的测试，验证只调用一次 `create(...)` 且参数是第一个工具。保留 `RequireExternalExecutionEvent` 的 mapper 测试，断言它仍产生旧式展示事件但没有 `confirmationId`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -Dtest=AgentEventMapperTest,AgentScopeChatAgentGatewayTest test`

Expected: FAIL，权限事件缺少元数据，且网关尚未依赖 `ToolConfirmationService`。

- [ ] **Step 3: 实现异步登记与权限载荷**

`StreamEventDto` 增加：

```java
public static StreamEventDto permissionRequired(ToolConfirmationRecord record) {
  ToolCallSnapshot toolCall = record.toolCall();
  return new StreamEventDto("permission_required", Map.of(
      "permission", toolCall.name(),
      "confirmationId", record.confirmationId(),
      "replyId", record.replyId(),
      "toolCallId", toolCall.id(),
      "toolName", toolCall.name(),
      "toolInput", toolCall.input(),
      "kind", record.kind().name()));
}
```

`AgentScopeChatAgentGateway.stream(...)` 的 `flatMap` 在普通 mapper 之前拦截 `RequireUserConfirmEvent`：空列表映射为协议错误；非空列表调用 `confirmationService.create(...)` 登记第一个工具并映射为权限事件。`AgentEventMapper` 保留 `RequireExternalExecutionEvent -> permissionRequired(firstToolName)`，删除其对 `RequireUserConfirmEvent` 的直接映射，避免绕过 Redis 登记。

- [ ] **Step 4: 运行映射与网关测试**

Run: `mvn -q -Dtest=AgentEventMapperTest,AgentScopeChatAgentGatewayTest test`

Expected: PASS。

- [ ] **Step 5: 提交流协议任务**

```powershell
git add backend/src/main/java/com/example/myagent/chat backend/src/test/java/com/example/myagent/chat/AgentEventMapperTest.java backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java
git commit -m "feat: publish pending tool confirmations"
```

---

### Task 3: 使用 RC4 pending-tool recovery 恢复执行流

**Files:**
- Create: `backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java`
- Modify: `backend/src/main/java/com/example/myagent/agent/AgentScopeStreamExecutor.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/StubChatAgentGateway.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

**Interfaces:**
- Consumes: Task 1 的可信 `ToolCallSnapshot`。
- Produces: `ChatAgentGateway.confirm(ChatToolConfirmationRequest)` 和 `AgentScopeStreamExecutor.confirm(...)` 返回恢复后的事件流。

- [ ] **Step 1: 编写恢复请求映射和配置失败测试**

内部请求固定为：

```java
public record ChatToolConfirmationRequest(
    Long userId,
    String sessionId,
    PermissionMode permissionMode,
    String replyId,
    ToolCallSnapshot toolCall,
    boolean confirmed) {}
```

网关测试捕获传给 executor 的请求和 `RuntimeContext`，断言 user/session/permissionMode 保持一致，并断言 executor 错误会向上游传播而不是被吞成成功完成。

在 `AgentScopeConfigTest` 中使用可捕获输入的假 HarnessAgent 工厂，验证确认路径构造的消息：

```java
assertThat(confirmMessage.getMetadata())
    .containsKey(Msg.METADATA_CONFIRM_RESULTS);
List<ConfirmResult> results = (List<ConfirmResult>)
    confirmMessage.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
assertThat(results).singleElement().satisfies(result -> {
  assertThat(result.isConfirmed()).isTrue();
  assertThat(result.getToolCall().getId()).isEqualTo("call-1");
  assertThat(result.getRules()).isEmpty();
});
```

- [ ] **Step 2: 运行测试并确认接口不存在**

Run: `mvn -q -Dtest=AgentScopeChatAgentGatewayTest,AgentScopeConfigTest test`

Expected: 编译或断言失败，因为 `confirm(...)` 和 pending-tool recovery 尚未实现。

- [ ] **Step 3: 增加确认恢复接口并实现 RC4 输入消息**

接口签名：

```java
public interface AgentScopeStreamExecutor {
  Flux<Object> stream(ChatAgentRequest request, Object runtimeContext);
  Flux<Object> confirm(ChatToolConfirmationRequest request, Object runtimeContext);
}

public interface ChatAgentGateway {
  Flux<StreamEventDto> stream(ChatAgentRequest request);
  Flux<StreamEventDto> confirm(ChatToolConfirmationRequest request);
}
```

在 `configureHarnessAgentBuilder(...)` 中增加：

```java
builder.enablePendingToolRecovery(true);
```

`AgentScopeConfig` 的 executor 确认方法必须重建相同 user/session 的 HarnessAgent，并调用：

```java
ConfirmResult result = new ConfirmResult(
    request.confirmed(), request.toolCall().toToolUseBlock());
Msg confirmationMessage = UserMessage.builder()
    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(result)))
    .build();
return Flux.using(
    () -> buildHarnessAgent(/* 与 stream 相同依赖和 request scope */),
    agent -> agent.streamEvents(confirmationMessage, (RuntimeContext) runtimeContext).cast(Object.class),
    HarnessAgent::close);
```

为避免为两种请求复制 Agent 构造参数，新增私有记录 `AgentRequestScope(Long userId, String sessionId, PermissionMode permissionMode)`；`ChatAgentRequest` 和 `ChatToolConfirmationRequest` 分别转换成该记录，`buildHarnessAgent(...)` 与 `applyRequestScope(...)` 只接收这个确定的 request scope。不得创建其他通用“未来请求”抽象。

`AgentScopeChatAgentGateway.confirm(...)` 复用 RuntimeContext 构造和普通事件映射，但遇到 `Throwable` 对象或 reactive error 时使用 `Flux.error(...)` 向上传播，让上层决定是否释放 Redis 租约。

- [ ] **Step 4: 运行恢复相关测试**

Run: `mvn -q -Dtest=AgentScopeChatAgentGatewayTest,AgentScopeConfigTest test`

Expected: PASS，确认消息包含一个无持久规则的 `ConfirmResult`，builder 启用了 pending-tool recovery。

- [ ] **Step 5: 提交 AgentScope 恢复任务**

```powershell
git add backend/src/main/java/com/example/myagent/agent backend/src/main/java/com/example/myagent/chat backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "feat: resume AgentScope tool calls after confirmation"
```

---

### Task 4: 单次确认 HTTP 接口与租约生命周期

**Files:**
- Create: `backend/src/main/java/com/example/myagent/chat/ToolConfirmationRequest.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatController.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 claim/complete/release，Task 3 的 `ChatAgentGateway.confirm(...)`。
- Produces: `POST /api/chat/sessions/{sessionId}/tool-confirmations/{confirmationId}`，响应类型 `application/x-ndjson`。

- [ ] **Step 1: 编写控制器校验和服务编排失败测试**

请求 DTO：

```java
public record ToolConfirmationRequest(@NotNull Boolean confirmed) {}
```

控制器测试覆盖：

```java
// confirmed 缺失 -> 400
.bodyValue("{}")

// 未知字段 -> 400
.bodyValue("{\"confirmed\":true,\"toolInput\":{}}")

// 成功 -> NDJSON
.bodyValue("{\"confirmed\":true}")
.expectBody(String.class)
.isEqualTo("{\"type\":\"text_delta\",\"delta\":\"continued\"}\n{\"type\":\"done\"}\n");
```

服务测试必须验证：

1. `sessionService.requireOwnedSession(...)` 在 claim 前调用；
2. gateway 成功完成后调用 `complete(confirmationId, processingToken, confirmed)`；
3. gateway reactive error 时调用 `release(...)`，返回一个 `error` 事件；
4. claim 返回 404/409 时不调用 gateway；
5. HTTP 请求中的数据无法覆盖 `ToolConfirmationClaim.record().toolCall()`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -Dtest=ChatServiceTest,ChatControllerTest test`

Expected: FAIL，确认路由和服务方法不存在。

- [ ] **Step 3: 实现服务编排和 NDJSON 路由**

`ChatService` 增加：

```java
public Flux<StreamEventDto> confirm(
    CurrentUser currentUser, String sessionId,
    String confirmationId, boolean confirmed) {
  return Mono.fromCallable(() -> {
        sessionService.requireOwnedSession(currentUser, sessionId);
        return permissionService.getModeForOwnedSession(sessionId);
      })
      .subscribeOn(Schedulers.boundedElastic())
      .flatMap(mode -> confirmationService.claim(currentUser.id(), sessionId, confirmationId)
          .map(claim -> new ConfirmationContext(mode, claim)))
      .flatMapMany(context -> {
        ToolConfirmationClaim claim = context.claim();
        ChatToolConfirmationRequest request = new ChatToolConfirmationRequest(
            currentUser.id(), sessionId, context.mode(), claim.record().replyId(),
            claim.record().toolCall(), confirmed);
        return chatAgentGateway.confirm(request)
            .concatWith(confirmationService.complete(
                confirmationId, claim.processingToken(), confirmed).thenMany(Flux.empty()))
            .onErrorResume(error -> confirmationService.release(
                    confirmationId, claim.processingToken())
                .thenMany(Flux.just(StreamEventDto.error(errorMessage(error)))));
      });
}
```

`ChatController` 增加相同 NDJSON 序列化方式的路由：

```java
@PostMapping(
    path = "/{sessionId}/tool-confirmations/{confirmationId}",
    produces = "application/x-ndjson")
public Flux<String> confirm(
    @AuthenticationPrincipal CurrentUser currentUser,
    @PathVariable String sessionId,
    @PathVariable String confirmationId,
    @Valid @RequestBody ToolConfirmationRequest request) {
  return chatService.confirm(currentUser, sessionId, confirmationId, request.confirmed())
      .map(this::toNdjsonLine);
}
```

- [ ] **Step 4: 运行后端确认链路测试**

Run: `mvn -q -Dtest=ToolConfirmationServiceTest,AgentEventMapperTest,AgentScopeChatAgentGatewayTest,ChatServiceTest,ChatControllerTest,AgentScopeConfigTest test`

Expected: PASS。

- [ ] **Step 5: 提交 HTTP 闭环任务**

```powershell
git add backend/src/main/java/com/example/myagent/chat backend/src/test/java/com/example/myagent/chat
git commit -m "feat: add one-time tool confirmation endpoint"
```

---

### Task 5: 前端确认流 API 与状态追加

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/tsconfig.app.json`
- Create: `frontend/src/test/setup.ts`
- Modify: `frontend/src/api/chat.ts`
- Modify: `frontend/src/stores/chat.ts`
- Create: `frontend/src/stores/__tests__/chat.spec.ts`

**Interfaces:**
- Consumes: Task 4 的 NDJSON 确认接口和 Task 2 的权限事件字段。
- Produces: `confirmTool(sessionId, messageId, event, confirmed)`，恢复事件追加到指定 assistant 消息。

- [ ] **Step 1: 安装最小测试依赖并增加测试命令**

`package.json` 增加：

```json
"scripts": {
  "test": "vitest run"
},
"devDependencies": {
  "@vue/test-utils": "^2.4.6",
  "jsdom": "^25.0.1",
  "vitest": "^2.1.8"
}
```

执行：

```powershell
cd frontend
npm install
```

Expected: 生成或更新 `package-lock.json`，安装成功。

- [ ] **Step 2: 配置 Vitest 并编写失败的 store 测试**

`vite.config.ts` 增加：

```ts
test: {
  environment: 'jsdom',
  setupFiles: ['./src/test/setup.ts']
}
```

`tsconfig.app.json` 移除对 `src/**/__tests__/*` 的排除，并增加 `types: ['vitest/globals']`。测试使用 `setActivePinia(createPinia())`，mock `confirmToolCall` 后断言：

```ts
await store.confirmTool('s_123', 'assistant-1', permissionEvent, true)

expect(confirmToolCall).toHaveBeenCalledWith(
  's_123', 'confirmation-1', true, expect.any(Function)
)
expect(store.messages('s_123')[0].content).toBe('continued')
expect(store.messages('s_123')[0].events.at(-1)?.type).toBe('tool_result')
```

再测试请求过程中 `event.confirming === true`，结束后为 `false`；失败时在原消息追加 `error` 事件并保留权限卡片可重试。

- [ ] **Step 3: 运行前端测试并确认失败**

Run: `npm test -- --run src/stores/__tests__/chat.spec.ts`

Expected: FAIL，`confirmToolCall` 和 store action 尚不存在。

- [ ] **Step 4: 实现共享 NDJSON 请求和确认 store action**

扩展类型：

```ts
export interface StreamEvent {
  confirmationId?: string
  replyId?: string
  toolCallId?: string
  toolName?: string
  toolInput?: unknown
  kind?: 'USER_CONFIRM' | 'EXTERNAL_EXECUTION' | string
}

export interface ToolEvent {
  confirmationId?: string
  replyId?: string
  toolCallId?: string
  toolName?: string
  toolInput?: unknown
  kind?: string
  confirming?: boolean
  consumed?: boolean
}
```

把 `streamChat` 内重复的 fetch/reader/parser 提取为仅供本文件使用的 `streamNdjson(path, body, onEvent)`，然后实现：

```ts
export function confirmToolCall(
  sessionId: string,
  confirmationId: string,
  confirmed: boolean,
  onEvent: (event: StreamEvent) => void
) {
  return streamNdjson(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/tool-confirmations/${encodeURIComponent(confirmationId)}`,
    { confirmed },
    onEvent
  )
}
```

store 的 `confirmTool(...)` 使用与 `sendMessage` 相同的事件归并逻辑，但目标由 `messageId` 明确指定，不创建新的 user/assistant 消息。成功完成后设置 `event.consumed = true`；请求错误只追加错误事件并恢复按钮，不删除原权限事件。

- [ ] **Step 5: 运行 store 测试和类型检查**

Run:

```powershell
npm test -- --run src/stores/__tests__/chat.spec.ts
npm run build
```

Expected: 两条命令均成功。

- [ ] **Step 6: 提交前端流状态任务**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/tsconfig.app.json frontend/src/test frontend/src/api/chat.ts frontend/src/stores/chat.ts frontend/src/stores/__tests__/chat.spec.ts
git commit -m "feat: handle tool confirmation streams in chat state"
```

---

### Task 6: 权限卡片允许/拒绝交互

**Files:**
- Modify: `frontend/src/components/ToolEventCard.vue`
- Modify: `frontend/src/components/ChatTranscript.vue`
- Modify: `frontend/src/views/ChatView.vue`
- Create: `frontend/src/components/__tests__/ToolEventCard.spec.ts`

**Interfaces:**
- Consumes: Task 5 的 `ToolEvent` 确认字段和 `chat.confirmTool(...)`。
- Produces: 在原权限卡片中展示工具、参数、“允许一次”和“拒绝一次”。

- [ ] **Step 1: 编写权限卡片失败测试**

挂载组件时传入：

```ts
const event: ToolEvent = {
  id: 'event-1',
  type: 'permission_required',
  permission: 'shell_command',
  confirmationId: 'confirmation-1',
  toolCallId: 'call-1',
  toolName: 'shell_command',
  toolInput: { command: 'Get-ChildItem' },
  kind: 'USER_CONFIRM'
}
```

断言工具名和参数可见，两个按钮文本分别为“允许一次”“拒绝一次”；点击后分别调用：

```ts
expect(confirmTool).toHaveBeenCalledWith(
  's_123', 'assistant-1', event, true
)
expect(confirmTool).toHaveBeenCalledWith(
  's_123', 'assistant-1', event, false
)
```

当 `event.confirming` 或 `event.consumed` 为 true 时两个按钮禁用；旧式无 `confirmationId` 的 external-execution 提示不显示按钮。

- [ ] **Step 2: 运行组件测试并确认失败**

Run: `npm test -- --run src/components/__tests__/ToolEventCard.spec.ts`

Expected: FAIL，组件尚无 props 和按钮。

- [ ] **Step 3: 实现最小组件调用链**

`ToolEventCard` 新增 `sessionId`、`messageId` props，权限事件的 payload 使用 `toolInput`；仅当 `kind === 'USER_CONFIRM' && confirmationId` 时显示按钮：

```vue
<el-button
  :disabled="event.confirming || event.consumed"
  @click="chat.confirmTool(sessionId, messageId, event, true)"
>
  允许一次
</el-button>
<el-button
  :disabled="event.confirming || event.consumed"
  @click="chat.confirmTool(sessionId, messageId, event, false)"
>
  拒绝一次
</el-button>
```

`ChatTranscript` 增加必需的 `sessionId: string` prop，并向每个 `ToolEventCard` 传递 `sessionId` 和当前 `message.id`。`ChatView` 传递 `:session-id="currentSessionId"`。不修改 `PermissionPanel`。

- [ ] **Step 4: 运行全部前端验证**

Run:

```powershell
npm test
npm run build
```

Expected: Vitest 全部 PASS，Vite production build 成功。

- [ ] **Step 5: 提交权限卡片任务**

```powershell
git add frontend/src/components/ToolEventCard.vue frontend/src/components/ChatTranscript.vue frontend/src/views/ChatView.vue frontend/src/components/__tests__/ToolEventCard.spec.ts
git commit -m "feat: add one-time tool confirmation controls"
```

---

### Task 7: 全链路回归验证

**Files:**
- Modify only if a failure is caused by this feature; do not clean unrelated code.

**Interfaces:**
- Consumes: Tasks 1-6 的完整实现。
- Produces: 可交付的修复分支和验证证据。

- [ ] **Step 1: 运行后端完整测试**

Run:

```powershell
cd backend
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q test
```

Expected: 全部测试 PASS。

- [ ] **Step 2: 运行前端完整验证**

Run:

```powershell
cd frontend
npm test
npm run build
```

Expected: 全部测试 PASS，production build 成功。

- [ ] **Step 3: 检查改动边界和工作区状态**

Run:

```powershell
git diff --check
git status --short
git log --oneline -10
```

Expected: `git diff --check` 无输出；`.claude/` 仍保持未跟踪且未被提交；功能改动均已提交。

- [ ] **Step 4: 对照验收场景做人工验证**

在 Redis、MySQL 和 AgentScope 模型配置可用的本地环境中：

1. 使用 `DEFAULT` 权限模式请求一个会触发 ASK 的工具；
2. 确认前端展示工具名、参数和两个单次操作按钮；
3. 点击“拒绝一次”，确认工具没有执行且 AgentScope 继续输出；
4. 再触发一次工具并点击“允许一次”，确认工具只执行一次且继续同一回复；
5. 对同一 `confirmationId` 重放请求，确认返回 `409`；
6. 确认 Redis 记录位于约定 key 下并保留原始 TTL。

Expected: 六项均符合预期，不需要修改会话级 Permission Mode。
