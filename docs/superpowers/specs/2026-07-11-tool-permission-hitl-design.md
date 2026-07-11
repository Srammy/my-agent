# 工具调用单次审批闭环设计

## 目标

修复当前 AgentScope 工具调用权限确认链路不完整的问题。

目前系统虽然能够向前端发送 `permission_required` 事件，但用户无法针对某一次被暂停的工具调用执行“允许一次”或“拒绝一次”，也无法在审批后继续原来的 AgentScope 执行流程。本次修复需要形成以下最小闭环：

1. AgentScope 在执行工具前要求用户确认。
2. 前端展示本次待确认的具体工具调用。
3. 用户选择“允许一次”或“拒绝一次”。
4. 后端把确认结果提交回同一个 AgentScope 会话。
5. 原执行流恢复并继续输出，不修改整个会话的权限模式，也不切换到 `BYPASS`。

本方案暂不支持“始终允许”或“始终拒绝”等持久化规则。

## 当前问题

当前代码由 `AgentEventMapper` 映射 AgentScope 的权限事件：

- `RequireUserConfirmEvent` 被转换为 `permission_required`。
- `RequireExternalExecutionEvent` 也被转换为 `permission_required`。

现有事件载荷只提供一个 `permission` 字符串，通常是第一个工具名称。因此前端只能提示“需要权限”，却缺少以下信息：

- 可唯一标识本次审批的确认编号；
- 完整的工具调用信息；
- 向后端提交本次审批结果的接口；
- 让暂停中的 AgentScope 执行流继续运行的恢复入口。

系统现有的会话级 `PermissionMode` 适合控制整体权限策略，但修改整个会话的权限模式不等价于批准某一次具体工具调用。

当前使用的 AgentScope RC4 已提供实现正式审批闭环所需的事件类型：

- `ConfirmResult`
- `UserConfirmResultEvent`
- `ExternalExecutionResultEvent`
- `RequireUserConfirmEvent`
- `RequireExternalExecutionEvent`

## 方案概述

在后端增加一个轻量的“工具确认桥接层”，并让前端通过它提交单次审批结果。

当 AgentScope 产生待确认事件时，后端保存一条待确认记录，并生成稳定的 `confirmationId`。发送给前端的 `permission_required` 事件同时携带展示工具调用和后续提交审批所需的信息。

用户允许或拒绝后，前端调用新的确认接口。后端校验当前用户、会话和待确认记录之间的归属关系，使用原始 `ToolUseBlock` 构造 `ConfirmResult`，放入恢复消息的 `Msg.METADATA_CONFIRM_RESULTS`，再提交回同一个 AgentScope 会话，使原执行流继续运行。

## 后端设计

### Redis 待确认记录服务

新增职责单一的 `ToolConfirmationService`，直接使用项目现有的 `ReactiveStringRedisTemplate` 将待确认记录持久化到 Redis。Redis 是该数据的唯一事实来源，服务实例不保存可影响审批结果的本地副本，因此任意后端实例都可以处理后续确认请求。

每条记录包含：

- `confirmationId`：本系统生成的单次确认编号；
- `userId`：发起对话的用户；
- `sessionId`：所属会话；
- `replyId`：AgentScope 要求回复的事件编号；
- `toolCallId`：具体工具调用编号；
- `toolName`：工具名称；
- `toolInput`：仅用于前端展示的工具入参；
- 原始 `ToolUseBlock` 的规范化快照：保存重建该对象所需的调用编号、名称和输入，构造确认结果时使用，禁止信任前端回传的工具调用内容；
- `kind`：用户确认或外部执行；
- `createdAt`：创建时间；
- `status`：`PENDING`、`PROCESSING` 或 `CONSUMED`；处理中状态记录租约令牌与租约截止时间，消费结果中记录最终的 `confirmed` 值。

Redis 键使用项目现有的 `agent.state-store.redis.key-prefix`，格式为：

```text
{keyPrefix}tool-confirmations:{confirmationId}
```

值使用 JSON 保存，不直接使用 Java 原生序列化。待确认记录默认设置 30 分钟 TTL；过期后视为不存在并返回 `404`。TTL 只在创建记录时设置，查询和失败重试不续期，避免无人处理的审批记录长期占用存储。

创建记录使用带 TTL 的单次 Redis 写入。消费记录必须通过 Lua 脚本执行原子比较并更新：只有状态为 `PENDING` 且 `userId`、`sessionId` 均匹配时，才能取得规范化工具调用快照并进入本次恢复流程。这样可以保证在多实例和并发重复点击场景下，同一个 `confirmationId` 只有一个请求获得执行权。

为满足“AgentScope 未接受确认时允许重试”的要求，消费过程使用短期租约状态 `PROCESSING`：原子脚本把 `PENDING` 改为带处理令牌的 `PROCESSING`；AgentScope 接受事件后，再以同一处理令牌改为 `CONSUMED`。若提交失败，则以同一令牌恢复为 `PENDING`。所有状态转换必须保留创建记录时的原始过期时间，不能重置 TTL。处理实例异常退出时，`PROCESSING` 租约在 30 秒后可被新的请求重新获取，记录本身仍受原始 30 分钟 TTL 限制。

### 权限事件载荷

扩展 `StreamEventDto.permissionRequired(...)`，使 `permission_required` 事件包含：

- `confirmationId`
- `replyId`
- `toolCallId`
- `toolName`
- `toolInput`
- `kind`

为兼容现有前端逻辑，保留原有 `permission` 字段，并令其值等于 `toolName`。

### 单次确认接口

新增接口：

```http
POST /api/sessions/{sessionId}/tool-confirmations/{confirmationId}
Content-Type: application/json

{
  "confirmed": true
}
```

接口规则：

- 当前用户必须拥有该会话；
- 待确认记录必须存在，并且属于当前用户和当前会话；
- 已消费的确认记录不得再次提交；
- 允许与拒绝均只能执行一次；
- 请求体只接收审批结果，不接收或覆盖原始工具名称、参数和 `ToolUseBlock`。

接口应优先直接返回恢复后的 SSE 事件流，使前端能把后续事件追加到同一段对话。如果现有客户端无法稳定处理流式 `POST`，可以由确认接口返回已受理结果，再由前端使用 `confirmationId` 打开后续流；无论采用哪种传输形式，都必须恢复原 AgentScope 执行，不能重新发送原用户消息来模拟恢复。

### AgentScope 恢复桥接

当前流执行器只接收用户消息和 `RuntimeContext`。本次为其增加以下确认恢复方法：

```java
Flux<Object> confirm(ChatToolConfirmationRequest request, Object runtimeContext)
```

AgentScope 适配层使用待确认记录中保存的原始对象构造：

```java
new ConfirmResult(confirmed, toolUseBlock)
```

已通过 AgentScope RC4 字节码确认恢复入口：构建带 `Msg.METADATA_CONFIRM_RESULTS` 元数据的 `UserMessage`，其中保存 `List<ConfirmResult>`；随后使用相同 `userId`、`sessionId` 和已启用 `enablePendingToolRecovery(true)` 的 HarnessAgent 调用 `streamEvents(...)`。AgentScope 从 Redis 中的 AgentState 恢复待处理工具，因此不需要保持原 Agent 实例存活，也不使用 `UserConfirmResultEvent` 作为恢复输入。

## 前端设计

权限卡片展示以下内容：

- 待执行工具名称；
- 精简后的工具参数预览；
- “允许一次”按钮；
- “拒绝一次”按钮。

点击按钮后，前端使用当前 `sessionId` 和事件中的 `confirmationId` 调用单次确认接口，并只提交 `confirmed` 布尔值。

请求处理中同时禁用两个按钮，避免重复提交。恢复接口返回的流事件继续追加到当前会话中，不创建新的用户消息。

现有会话级权限设置面板继续保留，但不再承担某一次工具调用的确认职责。

## 状态与错误处理

- 会话或待确认记录不存在，或者不属于当前用户时，返回 `404`，避免泄露其他用户是否存在待确认操作。
- 待确认记录已经消费时，返回 `409`。
- 待确认记录已过期时，按不存在处理并返回 `404`。
- 待确认记录正在被其他请求处理且租约尚未过期时，返回 `409`。
- 请求体缺失或 `confirmed` 值非法时，返回 `400`。
- AgentScope 未接受确认事件时，通过正常的 `error` 流事件告知前端。
- 只有 AgentScope 接受确认事件后，才能把记录标记为已消费；提交失败时应保留可重试状态。

## 安全约束

- 后端必须使用首次收到权限事件时保存的原始 `ToolUseBlock`。
- 前端回传的内容不得改变工具名称、工具参数或工具调用编号。
- 所有读取和消费待确认记录的操作都必须同时校验 `userId` 与 `sessionId`。
- 同一个 `confirmationId` 只能成功消费一次，并发重复请求只能有一个成功。
- Redis 中的工具调用快照是确认恢复的唯一可信输入；HTTP 请求只能决定 `confirmed` 的值。

## 测试范围

后端测试：

- 映射 `RequireUserConfirmEvent` 时创建待确认记录，并输出完整确认元数据；
- 不能审批不属于当前用户的会话；
- 不存在或已经消费的确认记录会被拒绝；
- “允许一次”使用原始 `ToolUseBlock` 构造 `ConfirmResult(true, toolCall)`；
- “拒绝一次”使用原始 `ToolUseBlock` 构造 `ConfirmResult(false, toolCall)`；
- 并发重复提交只能消费一次；
- Redis 记录带有 30 分钟 TTL，过期记录不能再审批；
- `PROCESSING` 租约阻止并发处理，并能在处理实例异常后恢复；
- 确认恢复失败时不会提前消费待确认记录。

前端测试：

- 带确认元数据的 `permission_required` 事件展示允许和拒绝按钮；
- 点击允许或拒绝时使用正确的 `sessionId`、`confirmationId` 和 `confirmed` 值调用接口；
- 请求处理中两个按钮均不可重复点击；
- 恢复后的事件继续追加到原会话。

## 不在本次范围内

- 持久化的“始终允许”或“始终拒绝”规则；
- 编辑 AgentScope `PermissionRule` 的界面；
- 外部工具执行完成后回传任意 `ToolResultBlock` 的完整闭环；
- 修改默认会话权限模式；
- 将 AgentScope 从 RC4 升级到 GA；

## 已确认的 AgentScope RC4 恢复约束

- HarnessAgent 必须启用 `enablePendingToolRecovery(true)`。
- 恢复调用必须重用原 `userId` 与 `sessionId`，使 AgentScope 加载同一份 Redis AgentState。
- 恢复消息的 `Msg.METADATA_CONFIRM_RESULTS` 必须包含使用 Redis 可信快照构造的 `ConfirmResult`。
- 可以为恢复请求重建 HarnessAgent，原请求结束后无需保留进程内 Agent 实例。
- 不得通过修改权限模式后重新发送用户消息来模拟恢复。
