# 确认续流互斥设计

## 问题

`sendMessage()` 使用 `loadingSessionId` 阻止并发消息流，但 `confirmTool()` 只设置确认事件自身的 `confirming` 状态。确认续流期间仍可启动新消息流；反过来，普通消息流尚未结束时也可提交确认。两个请求可能同时推进同一 AgentScope session 的持久化状态。

## 方案

沿用当前 store 的全局单流语义，让普通消息流和确认续流共用 `loadingSessionId`：

1. `confirmTool()` 在请求前检查 `loadingSessionId`；已有流时直接返回。
2. 确认请求开始前同步设置 `loadingSessionId = sessionId` 和 `event.confirming = true`。
3. `finally` 中清除 `event.confirming`；仅当锁仍属于当前 session 时清除 `loadingSessionId`。
4. `ToolEventCard` 在 chat store 正忙时禁用整组确认控件，使 UI 与 store 的互斥规则一致。

锁覆盖完整的 `confirmToolCall()` Promise 生命周期，包括 NDJSON 读取、成功、HTTP 错误和解析错误。

## 取舍

- 不只禁用按钮：store action 仍可能被测试、其他组件或程序代码直接调用，不能依赖 UI 保证互斥。
- 不改成每 session 锁表：现有 `sendMessage()` 已采用全局单流锁，本次保持一致，避免扩大状态模型。
- 不新增后端分布式锁：本次修复当前前端正常操作路径；跨标签页或直接 API 并发属于独立的服务端一致性需求。

## 验证

- 确认续流未完成时，`loadingSessionId` 被占用，`sendMessage()` 不调用 `streamChat()`。
- 普通消息流未完成时，`confirmTool()` 不调用 `confirmToolCall()`。
- 确认成功与失败后均释放 `loadingSessionId`。
- chat store 正忙时，确认卡片的所有决策和提交按钮均禁用。
- 前端全量测试和生产构建通过。
