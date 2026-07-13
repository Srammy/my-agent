# 多工具确认组 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整支持一个 `RequireUserConfirmEvent` 中的多个工具，让用户逐项决策后整组提交，并用一个 `List<ConfirmResult>` 恢复 AgentScope 一次。

**Architecture:** 一个 AgentScope 确认事件只创建一条 Redis 确认组记录，记录保存有序可信工具快照。HTTP 请求只提交 `toolCallId + confirmed`；服务端校验完整集合、整组消费，再按 Redis 顺序构造批量恢复消息。前端用一张确认组卡片维护逐项选择并一次提交。

**Tech Stack:** Java 21、Spring Boot、Project Reactor、Reactive Redis/Lua、AgentScope Java 2.0.0-RC4、Vue 3、Pinia、Element Plus、Vitest。

## Global Constraints

- 使用当前分支 `fix-tool-permission-hitl`，不创建 `.worktrees/`。
- 不修改或提交未跟踪的 `.claude/`。
- AgentScope 版本保持 `2.0.0-RC4`，不升级依赖。
- 一个 `RequireUserConfirmEvent` 对应一个 Redis 记录和一个 `confirmationId`。
- 单工具事件也使用只有一个成员的确认组，不保留另一套恢复逻辑。
- 用户必须完成组内全部决策后才能提交；后端拒绝缺失、重复和未知工具 ID。
- Redis 中的工具快照是唯一可信输入，HTTP 不能覆盖工具 ID、名称或参数。
- 保持当前“先消费、后恢复”语义；AgentScope 恢复失败后原 `confirmationId` 不可重试。
- Redis key、30 分钟 TTL、30 秒处理租约和 TTL 不续期规则保持不变。
- 仅修改多工具确认链路需要的文件，不重构相邻代码。

---

## 文件结构与职责

### 后端

- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationRecord.java`：一条 Redis 确认组记录。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationDecision.java`：消费后持久化的工具 ID 与布尔决策。
- `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`：整组 create/claim/release/consume 与 Lua 原子状态转换。
- `backend/src/main/java/com/example/myagent/chat/ToolConfirmationDecisionRequest.java`：HTTP 单项决策 DTO。
- `backend/src/main/java/com/example/myagent/chat/ToolConfirmationRequest.java`：严格解析批量决策请求。
- `backend/src/main/java/com/example/myagent/chat/ToolCallDecision.java`：可信工具快照与布尔决策的内部配对。
- `backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java`：传给 AgentScope 适配层的有序决策列表。
- `backend/src/main/java/com/example/myagent/chat/ChatService.java`：归属校验、决策集合校验、消费和恢复编排。
- `backend/src/main/java/com/example/myagent/chat/ChatController.java`：接收批量决策并返回 NDJSON。
- `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`：把一个确认事件完整登记为一个确认组。
- `backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`：发布 `toolCalls` 数组。
- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`：构造一个消息中的多个 `ConfirmResult`。

### 前端

- `frontend/src/api/chat.ts`：批量决策类型、带 HTTP 状态的流请求错误和确认 POST。
- `frontend/src/stores/chat.ts`：确认组工具列表、逐项选择、整组提交与错误状态。
- `frontend/src/components/ToolEventCard.vue`：多工具确认组卡片。

现有测试文件原位修改，不新增通用抽象或迁移框架。

---

### Task 1: 后端整组持久化、校验与 AgentScope 批量恢复

**Files:**
- Create: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationDecision.java`
- Create: `backend/src/main/java/com/example/myagent/chat/ToolConfirmationDecisionRequest.java`
- Create: `backend/src/main/java/com/example/myagent/chat/ToolCallDecision.java`
- Modify: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationRecord.java`
- Modify: `backend/src/main/java/com/example/myagent/toolconfirmation/ToolConfirmationService.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ToolConfirmationRequest.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatToolConfirmationRequest.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatController.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Test: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationServiceTest.java`
- Test: `backend/src/test/java/com/example/myagent/toolconfirmation/ToolConfirmationRedisIntegrationTest.java`
- Test: `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`
- Test: `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- Test: `backend/src/test/java/com/example/myagent/chat/ChatControllerTest.java`
- Test: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

**Interfaces:**
- Consumes: `RequireUserConfirmEvent.getToolCalls(): List<ToolUseBlock>` 和现有 Redis key/TTL/lease 约定。
- Produces: `ToolConfirmationService.create(Long, String, String, List<ToolUseBlock>, ConfirmationKind)`、`release(String, String)`、`consume(String, String, List<ToolConfirmationDecision>)`；`ChatToolConfirmationRequest(..., List<ToolCallDecision>)`；一个消息中的 `List<ConfirmResult>`。

- [ ] **Step 1: 先把后端测试改成确认组契约**

在 `ToolConfirmationServiceTest` 和 Redis 集成测试中使用两个工具：

```java
ToolUseBlock first = new ToolUseBlock("call-1", "read_file", Map.of("path", "a.md"));
ToolUseBlock second = new ToolUseBlock("call-2", "shell_command", Map.of("command", "npm test"));

ToolConfirmationRecord record =
    service.create(7L, "s_123", "reply-1", List.of(first, second), ConfirmationKind.USER_CONFIRM)
        .block();

assertThat(record.toolCalls()).extracting(ToolCallSnapshot::id)
    .containsExactly("call-1", "call-2");
```

增加 `release` 测试：claim 后 release，记录回到 `PENDING`、租约字段清空、原 TTL 不增加；增加混合消费测试：

```java
service.consume(
    record.confirmationId(),
    claim.processingToken(),
    List.of(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false)))
    .block();

assertThat(readRecord(record.confirmationId()).decisions())
    .containsExactly(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false));
```

把网关测试 `registersOnlyTheFirstToolFromAConfirmationEvent` 改为断言只调用一次 `create`，但传入两个工具，并且只发布一个事件：

```java
assertThat(events).singleElement().satisfies(event -> {
  assertThat(event.type()).isEqualTo("permission_required");
  assertThat((List<?>) event.payload().get("toolCalls")).hasSize(2);
});
verify(toolConfirmationService).create(
    7L, "s_123", "reply-1", List.of(first, second), ConfirmationKind.USER_CONFIRM);
```

增加网关测试：重复或空白 `toolCallId` 输出一个 `error`，并且 `create` 从未调用。

在 `ChatControllerTest` 中把成功请求改为：

```json
{"decisions":[{"toolCallId":"call-1","confirmed":true},{"toolCallId":"call-2","confirmed":false}]}
```

并增加以下 `400` 请求：空数组、缺少 `toolCallId`、缺少 `confirmed`、决策项含未知字段、顶层含未知字段。

在 `ChatServiceTest` 中断言：

```java
verify(toolConfirmationService).consume(
    eq("confirm-1"),
    eq("token-1"),
    eq(List.of(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false))));

assertThat(requestCaptor.getValue().decisions())
    .extracting(decision -> decision.toolCall().id(), ToolCallDecision::confirmed)
    .containsExactly(tuple("call-1", true), tuple("call-2", false));
```

再覆盖缺失、重复、未知工具 ID：调用 `release("confirm-1", "token-1")` 后返回 `400`，不调用 `consume` 和 gateway。

在 `AgentScopeConfigTest` 中断言确认消息元数据包含两个有序结果：第一个允许、第二个拒绝，且规则列表均为空；executor 只调用一次 `agent.streamEvents(...)`。

- [ ] **Step 2: 运行后端目标测试，确认新契约先失败**

Run（从 `backend` 目录）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q '-Dtest=ToolConfirmationServiceTest,ToolConfirmationRedisIntegrationTest,AgentScopeChatAgentGatewayTest,ChatServiceTest,ChatControllerTest,AgentScopeConfigTest' test
```

Expected: FAIL；编译错误指向尚不存在的列表签名和决策类型，或旧实现仍只登记一个工具。

- [ ] **Step 3: 实现确认组记录和 Redis 原子状态转换**

创建持久化决策：

```java
package com.example.myagent.toolconfirmation;

public record ToolConfirmationDecision(String toolCallId, boolean confirmed) {}
```

将记录改为：

```java
public record ToolConfirmationRecord(
    String confirmationId,
    String userId,
    String sessionId,
    String replyId,
    List<ToolCallSnapshot> toolCalls,
    ConfirmationKind kind,
    Instant createdAt,
    ToolConfirmationStatus status,
    String processingToken,
    Long leaseExpiresAtEpochMs,
    List<ToolConfirmationDecision> decisions) {}
```

`create` 接收列表并用 `toolCalls.stream().map(ToolCallSnapshot::from).toList()` 保存。

增加 release Lua，必须保留读取到的原 TTL：

```lua
local value = redis.call('GET', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
if not value or ttl <= 0 then return '__NOT_FOUND__' end
local data = cjson.decode(value)
if data.status ~= 'PROCESSING' or data.processingToken ~= ARGV[1] then return '__CONFLICT__' end
data.status = 'PENDING'
data.processingToken = nil
data.leaseExpiresAtEpochMs = nil
redis.call('SET', KEYS[1], cjson.encode(data), 'PX', ttl)
return '__OK__'
```

consume Lua 的结果字段改为：

```lua
data.status = 'CONSUMED'
data.decisions = cjson.decode(ARGV[2])
data.processingToken = nil
data.leaseExpiresAtEpochMs = nil
```

Java 方法签名固定为：

```java
public Mono<Void> release(String confirmationId, String processingToken)

public Mono<Void> consume(
    String confirmationId,
    String processingToken,
    List<ToolConfirmationDecision> decisions)
```

`consume` 使用当前 `ObjectMapper` 把 `decisions` 写成 JSON 后作为 Lua 的第二个参数。

- [ ] **Step 4: 实现完整确认组登记和流事件载荷**

`AgentScopeChatAgentGateway.registerUserConfirmation(...)` 先验证工具 ID，再创建一次记录：

```java
List<ToolUseBlock> toolCalls = confirmationEvent.getToolCalls();
if (toolCalls == null || toolCalls.isEmpty()) {
  return Flux.just(StreamEventDto.error(
      "AgentScope confirmation event did not include a tool call"));
}

Set<String> ids = new HashSet<>();
if (toolCalls.stream().anyMatch(tool ->
    tool.getId() == null || tool.getId().isBlank() || !ids.add(tool.getId()))) {
  return Flux.just(StreamEventDto.error(
      "AgentScope confirmation event included invalid or duplicate tool call ids"));
}

return toolConfirmationService
    .create(userId, sessionId, confirmationEvent.getReplyId(), toolCalls,
        ConfirmationKind.USER_CONFIRM)
    .map(StreamEventDto::permissionRequired)
    .flux();
```

删除只处理第一个工具的 logger 和告警。`StreamEventDto.permissionRequired(record)` 输出：

```java
List<Map<String, Object>> toolCalls = record.toolCalls().stream()
    .map(tool -> Map.<String, Object>of(
        "toolCallId", tool.id(),
        "toolName", tool.name(),
        "toolInput", tool.input()))
    .toList();

return new StreamEventDto("permission_required", Map.of(
    "permission", record.toolCalls().getFirst().name(),
    "confirmationId", record.confirmationId(),
    "replyId", record.replyId(),
    "toolCalls", toolCalls,
    "kind", record.kind().name()));
```

- [ ] **Step 5: 实现严格批量 HTTP DTO 和可信决策编排**

创建：

```java
package com.example.myagent.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ToolConfirmationDecisionRequest(
    @NotBlank String toolCallId,
    @NotNull Boolean confirmed) {}
```

`ToolConfirmationRequest` 改为：

```java
public record ToolConfirmationRequest(
    @NotEmpty @Valid List<ToolConfirmationDecisionRequest> decisions) {}
```

保留现有严格反序列化原则：顶层只允许 `decisions`；它必须是数组；每项必须是对象且只允许
`toolCallId`、`confirmed`；类型错误通过 `context.reportInputMismatch(...)` 返回 `400`。

创建可信内部类型并修改网关请求：

```java
package com.example.myagent.chat;

import com.example.myagent.toolconfirmation.ToolCallSnapshot;

public record ToolCallDecision(ToolCallSnapshot toolCall, boolean confirmed) {}

public record ChatToolConfirmationRequest(
    Long userId,
    String sessionId,
    PermissionMode permissionMode,
    String replyId,
    List<ToolCallDecision> decisions) {}
```

`ChatController.confirm(...)` 把 `request.decisions()` 传给 `ChatService.confirm(...)`。

`ChatService` 在 claim 后建立请求决策 Map，拒绝重复/未知/缺失 ID；成功时按
`claim.record().toolCalls()` 顺序生成 `ToolCallDecision`。校验失败路径必须先执行：

```java
return toolConfirmationService
    .release(confirmationId, claim.processingToken())
    .then(Mono.error(new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Decisions must match every pending tool call exactly")));
```

成功路径先整组消费，再恢复：

```java
List<ToolConfirmationDecision> persisted = trustedDecisions.stream()
    .map(decision -> new ToolConfirmationDecision(
        decision.toolCall().id(), decision.confirmed()))
    .toList();

ChatToolConfirmationRequest gatewayRequest = new ChatToolConfirmationRequest(
    currentUser.id(), sessionId, permissionMode, claim.record().replyId(), trustedDecisions);

return toolConfirmationService
    .consume(confirmationId, claim.processingToken(), persisted)
    .thenMany(Flux.defer(() -> chatAgentGateway.confirm(gatewayRequest)
        .onErrorResume(error -> Flux.just(StreamEventDto.error(errorMessage(error))))));
```

不要在 gateway 恢复失败或订阅取消时调用 `release`。

- [ ] **Step 6: 实现 AgentScope 批量 ConfirmResult**

将单个 `confirmResult(request)` 改为：

```java
List<ConfirmResult> confirmResults(ChatToolConfirmationRequest request) {
  return request.decisions().stream()
      .map(decision -> new ConfirmResult(
          decision.confirmed(),
          decision.toolCall().toToolUseBlock(),
          Collections.emptyList()))
      .toList();
}

UserMessage confirmationMessage(ChatToolConfirmationRequest request) {
  return UserMessage.builder()
      .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults(request)))
      .build();
}
```

executor 仍只调用一次 `agent.streamEvents(confirmationMessage(request), runtimeContext)`，不循环创建 Agent。

- [ ] **Step 7: 运行后端目标测试**

Run:

```powershell
mvn -q '-Dtest=ToolConfirmationServiceTest,ToolConfirmationRedisIntegrationTest,AgentScopeChatAgentGatewayTest,ChatServiceTest,ChatControllerTest,AgentScopeConfigTest' test
```

Expected: PASS。若 Docker 未运行，只有 Testcontainers 集成测试可以因环境不可用失败；启动 Docker 后必须重新运行并通过，不能跳过该测试。

- [ ] **Step 8: 提交后端原子变更**

```powershell
git add backend/src/main/java/com/example/myagent/toolconfirmation backend/src/main/java/com/example/myagent/chat backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/test/java/com/example/myagent/toolconfirmation backend/src/test/java/com/example/myagent/chat backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "feat: support grouped tool confirmations"
```

---

### Task 2: 前端批量确认 API 与 Pinia 状态

**Files:**
- Modify: `frontend/src/api/chat.ts`
- Modify: `frontend/src/stores/chat.ts`
- Test: `frontend/src/stores/__tests__/chat.spec.ts`

**Interfaces:**
- Consumes: Task 1 的 `permission_required.toolCalls` 和 `{ decisions: [...] }` HTTP 请求。
- Produces: `ToolEvent.toolCalls`、`ToolEvent.decisions`、`setToolDecision(...)` 和无布尔参数的 `confirmTool(sessionId, messageId, event)`。

- [ ] **Step 1: 编写失败的 API/store 测试**

固定事件：

```ts
const permissionEvent: ToolEvent = {
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
```

测试先调用：

```ts
store.setToolDecision(permissionEvent, 'call-1', true)
store.setToolDecision(permissionEvent, 'call-2', false)
await store.confirmTool('s_123', 'assistant-1', permissionEvent)
```

断言 fetch body 精确为：

```json
{"decisions":[{"toolCallId":"call-1","confirmed":true},{"toolCallId":"call-2","confirmed":false}]}
```

再测试：决策未完成不发请求；重复提交被 `confirming` 拦截；NDJSON `error` 后 `consumed=true`；HTTP `400` 保留选择且可重试；HTTP `404/409` 设置 `consumed=true`。

- [ ] **Step 2: 运行 store 测试并确认失败**

Run（从 `frontend` 目录）：

```powershell
npm test -- --run src/stores/__tests__/chat.spec.ts
```

Expected: FAIL；旧 API 仍发送 `{ confirmed }`，store 也没有逐项决策方法。

- [ ] **Step 3: 实现前端批量类型和带状态错误**

在 `api/chat.ts` 增加：

```ts
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
```

`StreamEvent` 增加 `toolCalls?: ConfirmationToolCall[]`。`streamNdjson` 在非 2xx 时抛出：

```ts
throw new StreamRequestError(await readError(response), response.status)
```

确认 API 固定为：

```ts
export function confirmToolCall(
  sessionId: string,
  confirmationId: string,
  decisions: ToolConfirmationDecision[],
  onEvent: (event: StreamEvent) => void
): Promise<void> {
  return streamNdjson(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/tool-confirmations/${encodeURIComponent(confirmationId)}`,
    { decisions },
    onEvent
  )
}
```

- [ ] **Step 4: 实现 Pinia 逐项决策和整组提交**

`ToolEvent` 增加：

```ts
toolCalls?: ConfirmationToolCall[]
decisions?: Record<string, boolean | undefined>
```

`toToolEvent` 对 `toolCalls` 做结构过滤并保留顺序；用户确认事件初始化 `decisions: {}`。

新增 action：

```ts
setToolDecision(event: ToolEvent, toolCallId: string, confirmed: boolean) {
  if (event.confirming || event.consumed || !event.toolCalls?.some(
    (tool) => tool.toolCallId === toolCallId
  )) return

  event.decisions ??= {}
  event.decisions[toolCallId] = confirmed
}
```

`confirmTool` 从 `event.toolCalls` 原始顺序构造决策；只要一项不是 boolean 就直接返回。catch 中：

```ts
if (error instanceof StreamRequestError && (error.status === 404 || error.status === 409)) {
  event.consumed = true
} else {
  event.consumed = false
}
```

保留现有规则：流正常结束（包括收到 NDJSON `error` 事件）后 `event.consumed=true`，恢复事件追加到原消息。

- [ ] **Step 5: 运行 store 测试和类型构建**

```powershell
npm test -- --run src/stores/__tests__/chat.spec.ts
npm run build
```

Expected: store 测试 PASS；构建 PASS。此时旧卡片仍待 Task 3 改为组界面，但类型和数据流已可用。

- [ ] **Step 6: 提交前端数据流**

```powershell
git add frontend/src/api/chat.ts frontend/src/stores/chat.ts frontend/src/stores/__tests__/chat.spec.ts
git commit -m "feat: submit grouped tool decisions"
```

---

### Task 3: 多工具确认组卡片

**Files:**
- Modify: `frontend/src/components/ToolEventCard.vue`
- Test: `frontend/src/components/__tests__/ToolEventCard.spec.ts`

**Interfaces:**
- Consumes: Task 2 的 `event.toolCalls`、`event.decisions`、`setToolDecision`、`confirmTool`。
- Produces: 逐项互斥选择、全部完成后启用的“提交本组决策”按钮。

- [ ] **Step 1: 编写失败的组件测试**

挂载含两个工具的用户确认事件，断言两个名称和两份 JSON 参数可见。点击第一项“允许”和第二项“拒绝”，断言：

```ts
expect(setToolDecision).toHaveBeenNthCalledWith(1, event, 'call-1', true)
expect(setToolDecision).toHaveBeenNthCalledWith(2, event, 'call-2', false)
```

未完成全部选择时提交按钮 disabled；为两项填入决策后重新渲染，点击提交并断言：

```ts
expect(confirmTool).toHaveBeenCalledWith('s_123', 'assistant-1', event)
```

保留现有测试：外部权限事件或缺少 `confirmationId` 时只显示提示，不显示确认操作。

- [ ] **Step 2: 运行组件测试并确认失败**

```powershell
npm test -- --run src/components/__tests__/ToolEventCard.spec.ts
```

Expected: FAIL；旧组件只显示一个工具和两个整卡操作按钮。

- [ ] **Step 3: 实现最小确认组 UI**

用户确认区域按 `event.toolCalls` 渲染：

```vue
<div v-for="tool in event.toolCalls" :key="tool.toolCallId" class="tool-event__confirmation-item">
  <div class="tool-event__tool-name">{{ tool.toolName }}</div>
  <pre class="tool-event__payload">{{ formatValue(tool.toolInput) }}</pre>
  <el-button
    :type="event.decisions?.[tool.toolCallId] === true ? 'primary' : 'default'"
    :disabled="confirmationLocked"
    @click="chat.setToolDecision(event, tool.toolCallId, true)"
  >允许</el-button>
  <el-button
    :type="event.decisions?.[tool.toolCallId] === false ? 'danger' : 'default'"
    :disabled="confirmationLocked"
    @click="chat.setToolDecision(event, tool.toolCallId, false)"
  >拒绝</el-button>
</div>

<el-button
  :disabled="confirmationLocked || !allDecided"
  @click="chat.confirmTool(sessionId, messageId, event)"
>提交本组决策</el-button>
```

`allDecided` 必须逐项检查 `typeof event.decisions?.[toolCallId] === 'boolean'`。`confirmationLocked` 复用 `confirming/consumed/sessionId/messageId` 条件。只增加组列表所需的局部样式，不修改其他事件卡片样式。

- [ ] **Step 4: 运行全部前端测试与构建**

```powershell
npm test
npm run build
```

Expected: 全部 Vitest PASS，Vite production build PASS；允许现有非失败性的 chunk/PURE 警告。

- [ ] **Step 5: 提交确认组 UI**

```powershell
git add frontend/src/components/ToolEventCard.vue frontend/src/components/__tests__/ToolEventCard.spec.ts
git commit -m "feat: render grouped tool confirmation controls"
```

---

### Task 4: 全链路回归与变更边界检查

**Files:**
- Modify only if a failure is caused by this feature; do not clean unrelated code.

**Interfaces:**
- Consumes: Tasks 1-3 的完整实现。
- Produces: 可交付的多工具确认组修复和验证证据。

- [ ] **Step 1: 运行完整后端测试**

Run（从 `backend` 目录）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q test
```

Expected: 全部测试 PASS，包括 Testcontainers Redis 集成测试。

- [ ] **Step 2: 运行完整前端验证**

Run（从 `frontend` 目录）：

```powershell
npm test
npm run build
```

Expected: 全部测试 PASS，production build 成功。

- [ ] **Step 3: 检查提交、差异和未跟踪文件边界**

```powershell
git diff --check
git status --short
git log --oneline -8
```

Expected: `git diff --check` 无输出；`.claude/` 仍未跟踪且从未暂存；实现文件均已提交；没有 `.tmp`、测试输出或可视化会话文件进入 Git。

- [ ] **Step 4: 对照验收场景人工检查**

在 Redis、数据库和模型 API 可用时执行：

1. 触发同一回复中的两个 ASK 工具。
2. 确认前端只显示一张组卡片和两个工具。
3. 选择“工具 A 允许、工具 B 拒绝”，一次提交。
4. 确认允许工具只执行一次，拒绝工具不执行，AgentScope 继续同一回复。
5. 重放相同 `confirmationId`，确认返回 `409`。
6. 检查 Redis 只有一条确认组记录，`toolCalls` 与 `decisions` 各有两项，状态为 `CONSUMED`，TTL 未被续期。

Expected: 六项均符合；若模型 API 密钥不可用，记录为外部环境阻塞，但不得用该阻塞替代自动化测试。
