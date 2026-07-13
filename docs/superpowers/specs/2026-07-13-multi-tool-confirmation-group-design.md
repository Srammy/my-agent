# 多工具确认组设计

## 背景

当前 `AgentScopeChatAgentGateway` 收到 `RequireUserConfirmEvent` 后，只登记
`getToolCalls().getFirst()`。当 AgentScope 在同一事件中要求确认多个工具时，后续工具会被
静默丢弃。

AgentScope Java 2.0.0-RC4 的事件和恢复契约本身支持批量工具：

- `RequireUserConfirmEvent.getToolCalls()` 返回 `List<ToolUseBlock>`；
- ReActAgent 会把同一轮处于 `ASKING` 状态的工具放进同一个确认事件；
- 恢复消息的 `Msg.METADATA_CONFIRM_RESULTS` 接收 `List<ConfirmResult>`。

因此不能把同一事件拆成多个互相独立的恢复请求。逐个恢复会使尚未提交决策的工具再次触发
确认事件，并产生重复确认记录。

## 目标

一个 `RequireUserConfirmEvent` 对应一个确认组和一个 `confirmationId`。用户对组内每个工具
分别选择允许或拒绝，全部选择完成后一次性提交。后端一次性构造完整
`List<ConfirmResult>`，只恢复 AgentScope 一次。

成功标准：

1. 同一确认事件中的全部工具都能展示且不会丢失。
2. 用户可以在同一组内混合选择允许和拒绝。
3. 一个确认组只能被成功消费和恢复一次。
4. HTTP 请求不能覆盖 Redis 中保存的可信工具快照。
5. 单工具事件使用同一套确认组模型，不保留两套实现。

## 不在本次范围内

- “始终允许”或“始终拒绝”等持久化权限规则；
- `RequireExternalExecutionEvent` 的外部执行结果闭环；
- 修改会话级 `PermissionMode`；
- 升级 AgentScope 版本；
- 为 30 分钟后自动过期的开发中旧格式 Redis 记录编写数据迁移。

## 方案选择

采用一个确认组对应一条 Redis 记录的方案。

没有采用“每个工具一条记录，再增加 groupId”的方案，因为它需要协调多个 Redis 状态，
容易产生部分消费。也没有采用按数组位置提交布尔值的方案，因为它依赖前后端顺序，无法安全
识别决策对应的工具。

## 数据模型

### Redis 确认组

将 `ToolConfirmationRecord` 的单个 `toolCall` 改为：

```java
List<ToolCallSnapshot> toolCalls
```

记录包含：

- `confirmationId`：确认组编号，继续使用随机 UUID 字符串；
- `userId`、`sessionId`：归属校验；
- `replyId`：AgentScope 回复编号；
- `toolCalls`：按 AgentScope 原始顺序保存的可信工具快照；
- `kind`：本次仍为 `USER_CONFIRM`；
- `createdAt`、`status`、`processingToken`、`leaseExpiresAtEpochMs`；
- `decisions`：消费后保存的最终工具 ID 与布尔决策，消费前为 `null`。

`toolCallId` 是请求决策和可信快照之间的关联键。创建确认组前必须确认所有工具 ID 非空且
互不重复；否则输出协议错误，不创建 Redis 记录。

Redis 键、30 分钟 TTL、30 秒处理租约以及 TTL 不续期的规则保持不变。

### HTTP DTO

确认接口保持现有路径：

```http
POST /api/chat/sessions/{sessionId}/tool-confirmations/{confirmationId}
Content-Type: application/json
Accept: application/x-ndjson
```

请求体改为：

```json
{
  "decisions": [
    { "toolCallId": "call-a", "confirmed": true },
    { "toolCallId": "call-b", "confirmed": false }
  ]
}
```

对应的请求类型为：

```java
record ToolConfirmationDecisionRequest(String toolCallId, Boolean confirmed) {}
record ToolConfirmationRequest(List<ToolConfirmationDecisionRequest> decisions) {}
```

字段使用 Bean Validation 检查非空；工具 ID 集合与 Redis 记录的匹配由服务层检查。

### 内部恢复请求

HTTP 决策与 Redis 快照校验成功后，按 Redis 中的原始工具顺序生成内部可信决策：

```java
record ToolCallDecision(ToolCallSnapshot toolCall, boolean confirmed) {}
```

`ChatToolConfirmationRequest` 持有 `List<ToolCallDecision>`，不再持有单个工具和单个
`confirmed`。

## 流事件协议

一个确认组只发布一个 `permission_required` 事件：

```json
{
  "type": "permission_required",
  "permission": "read_file",
  "confirmationId": "confirm-group-7",
  "replyId": "reply-1",
  "kind": "USER_CONFIRM",
  "toolCalls": [
    {
      "toolCallId": "call-a",
      "toolName": "read_file",
      "toolInput": { "path": "/workspace/report.md" }
    },
    {
      "toolCallId": "call-b",
      "toolName": "shell_command",
      "toolInput": { "command": "npm test" }
    }
  ]
}
```

`toolCalls` 是用户确认事件的权威展示字段。保留 `permission`，值为第一个工具名，仅用于
兼容通用权限事件展示；多工具卡片不得依赖它判断组内工具。旧的顶层 `toolCallId`、
`toolName`、`toolInput` 不再用于用户确认组。

## 后端数据流

### 登记确认组

1. `AgentScopeChatAgentGateway` 收到 `RequireUserConfirmEvent`。
2. 校验工具列表非空，且所有工具 ID 非空、唯一。
3. 将完整 `List<ToolUseBlock>` 一次性交给 `ToolConfirmationService.create(...)`。
4. 服务将整个列表转换为快照并写入一条 Redis 记录。
5. Redis 写入成功后发布一个 `permission_required`；写入失败则发布一个协议错误。

现有“只处理第一个工具”的告警和 `getFirst()` 分支删除。

### 提交与恢复

1. `ChatService` 先校验当前用户拥有会话，并读取会话权限模式。
2. 原子 `claim` 确认组，获得可信记录与 `processingToken`。
3. 校验请求决策：不得为空，不得重复，不得出现未知工具，且必须覆盖全部工具。
4. 按 Redis 工具顺序把布尔决策与可信快照配对。
5. 若步骤 3 失败，以同一令牌把记录从 `PROCESSING` 释放回 `PENDING`，保留原 TTL，
   然后返回 `400`。释放只用于消费前的请求校验失败。
6. 校验成功后，把整个确认组原子标记为 `CONSUMED`，同时保存最终决策。
7. 构造 `ChatToolConfirmationRequest` 并调用网关恢复。
8. `AgentScopeConfig` 按原始顺序为每项创建无持久化规则的 `ConfirmResult`，放进同一个
   `Msg.METADATA_CONFIRM_RESULTS` 列表。
9. 使用相同 user/session 的 HarnessAgent 调用一次 `streamEvents(...)`。
10. 恢复事件追加到原 assistant 消息；若再次产生确认事件，则创建一个新的确认组。

步骤 6 继续遵循当前已经确定的“先消费、后恢复”语义。AgentScope 恢复失败时，原
`confirmationId` 不可重试，用户需要通过新消息触发新的确认流程。

## 前端交互

一条用户确认事件渲染为一张确认组卡片：

- 按 `toolCalls` 顺序展示每个工具名称和参数；
- 每项具有“允许”和“拒绝”两个互斥选择；
- 本地决策状态为 `true | false | undefined`；
- 任一工具尚未选择时，“提交本组决策”按钮禁用；
- 点击提交后整张卡片进入 `confirming`，所有操作按钮禁用；
- 请求只发送工具 ID 与布尔决策；
- 恢复事件继续追加到当前 assistant 消息，不创建新用户消息；
- 流正常结束或返回流式错误后，确认组均标记为 `consumed`。

单工具场景仍展示同一组卡片，只包含一个工具。

## 错误与并发语义

- 空工具事件，或事件内工具 ID 为空、重复：输出协议 `error`，不创建确认组。
- 请求体字段为空：`400`。
- 决策缺失、重复或包含未知工具 ID：释放处理租约后返回 `400`。
- 会话或确认组不存在，或归属不匹配：`404`。
- 确认组正在处理或已经消费：`409`。
- AgentScope 恢复失败：返回 NDJSON `error`，确认组保持已消费。
- 并发提交由 Redis claim/consume 脚本保证只有一个请求能消费整个组。
- 网络中断时前端可能无法判断服务器是否已消费；再次提交由后端以成功或 `409` 给出
  最终结果。

## 测试设计

### 后端

- 两个工具的事件只创建一条 Redis 记录，并保留原始顺序；
- `permission_required.toolCalls` 包含所有稳定元数据；
- 空或重复工具 ID 不创建记录；
- 混合决策生成同一消息中的两个 `ConfirmResult`；
- AgentScope 只恢复一次；
- HTTP 数据不能覆盖可信工具名称、参数和调用 ID；
- 缺失、重复和未知决策均返回 `400`，记录恢复为 `PENDING`；
- 并发提交只能消费整个确认组一次；
- 消费记录保存整组最终决策并保留原 TTL；
- 单工具事件使用单成员确认组正常工作；
- 恢复后出现新的多工具确认时创建新的确认组；
- Redis 集成测试覆盖列表 JSON、claim、release、consume 和租约过期重新领取。

### 前端

- 一张卡片展示多个工具及各自参数；
- 未完成全部决策时不能提交；
- 支持同组混合允许和拒绝，并一次发送完整决策数组；
- 提交期间整组操作禁用；
- 恢复事件追加到原 assistant 消息；
- `400` 保留选择并允许修正，`404/409` 禁用确认组；
- 流式恢复错误标记确认组已消费且不能重试。

### 回归验证

运行完整 Maven 测试、完整 Vitest 测试和 Vite 生产构建，并执行 `git diff --check`。
