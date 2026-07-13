# AgentScope 事件错误语义统一设计

## 问题

`AgentScopeStreamExecutor` 既可能以 reactive error 发出 SDK 失败，也可能把 `Throwable` 作为普通流元素发出。当前 `stream()` 先调用 `AgentEventMapper`，而 mapper 已将 `Throwable` 转成协议 `error` 事件，因此流会继续处理后续元素；`confirm()` 则把同一类元素转成 reactive error 并终止流。两条路径的底层错误语义不一致。

## 方案

在 `AgentScopeChatAgentGateway` 中提取一个两条路径共用的原始事件处理方法：

1. `Throwable` 元素统一转为 reactive error，终止当前 SDK 事件流。
2. `RequireUserConfirmEvent` 继续注册待确认记录。
3. 其他事件继续交给 `AgentEventMapper`，无法映射的事件忽略。

两条入口保留各自的错误边界：

- `stream()` 保留末端 `onErrorResume`，把终止错误转换成一个协议 `error` 事件。
- `confirm()` 不在网关吞掉错误，继续交给 `ChatService` 处理“确认记录已消费后恢复失败”的响应语义。

不把 `confirm()` 改为直接返回普通 `error` 事件，因为这会让编排层无法区分正常协议输出和恢复失败。

## 备选方案

- 只补注释：没有消除行为差异和不可达分支，不采用。
- 两条路径都直接映射为普通 `error` 事件：会隐藏确认恢复失败，不采用。

## 验证

- `stream()` 收到“正常事件、Throwable 元素、结束事件”时，只输出正常事件和一个 `error`，不输出错误后的 `done`。
- `confirm()` 继续把 `Throwable` 元素作为 reactive error 向 `ChatService` 传播。
- 相关网关及服务测试全部通过。
