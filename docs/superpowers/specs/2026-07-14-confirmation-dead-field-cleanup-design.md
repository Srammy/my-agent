# 确认链路死字段清理设计

## 目标

清理多工具确认协议落地后不再生效的字段，避免前端类型和后端内部请求继续表达不存在的行为。

## 前端扁平工具字段

用户确认事件以 `toolCalls[]` 表示一个或多个工具。删除以下顶层可选字段：

- `StreamEvent.toolCallId/toolName/toolInput`；
- `ToolEvent.toolCallId/toolName/toolInput`；
- `toToolEvent()` 对上述顶层字段的复制。

保留 `ConfirmationToolCall` 中的同名字段。保留顶层 `replyId`，因为后端当前仍将它作为确认事件元数据输出。

## 后端 replyId

删除 `ChatToolConfirmationRequest.replyId`。`ChatService` 构造恢复请求时不再从 `ToolConfirmationRecord` 复制该值；AgentScope 恢复仍通过可信、有序的 `ToolCallDecision` 构造 `ConfirmResult`。

保留 `ToolConfirmationRecord.replyId` 及 `permission_required.replyId`，避免改变 Redis 记录结构和对外事件协议。

## 不在范围内

- 不拆分 `ToolConfirmationRecord` 的持久化字段；
- 不迁移或重写已有 Redis 确认记录；
- 不修改 `ConfirmResult` 的构造和整组提交语义。

## 验证

- 前端接收包含遗留顶层工具字段的事件时，不再把它们复制到 `ToolEvent`，但完整保留 `toolCalls[]`。
- 后端测试使用不含 `replyId` 的 `ChatToolConfirmationRequest`，并验证生成的 `ConfirmResult` 内容不变。
- 前端全量测试与构建、后端相关测试通过。
