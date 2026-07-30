# 工具确认恢复失败重试设计

## 目标

工具确认在 Agent 恢复流真正启动前失败时，恢复为可重试状态；恢复流一旦可能开始执行工具，则保持已消费，避免重复外部副作用。

## 非目标

- 不回滚已经执行的工具或外部副作用。
- 不为所有工具建立通用幂等账本。
- 不允许 Agent 恢复流启动后的失败重新提交同一确认。
- 不改变会话取消、用户隔离或 Skill 审批语义。

## 安全边界

“可安全重试”的边界是 Agent 事件源尚未订阅。事件源订阅后，即使尚未产生前端事件，也可能已经开始模型推理或工具执行，因此确认记录必须保持 `CONSUMED`。

## 状态流转

继续使用现有状态：

```text
PENDING
  └─ claim ─> PROCESSING
                 ├─ 参数无效或恢复启动前失败 ─> PENDING
                 └─ 即将订阅 Agent 事件源 ─> CONSUMED
                                                    └─ 后续成功或失败均保持 CONSUMED
```

`PROCESSING` 继续携带 processing token 和租约，阻止并发提交。租约到期后，未提交消费的记录可以重新 claim。

## 后端流程

1. 校验会话归属并读取权限模式。
2. `claim()` 将确认记录从 `PENDING` 原子改为 `PROCESSING`。
3. 校验 decisions 必须完整、唯一且只引用服务端保存的工具调用。
4. 创建 `AgentExecution`，但不订阅其事件流。
5. 将执行交给 `SessionExecutionCoordinator.track()`。
6. 协调器完成会话执行登记并准备调用事件源 supplier 时：
   - 先以 processing token 调用 `consume()`；
   - `consume()` 成功后，订阅 `AgentExecution.events()`；
   - 此时确认记录为 `CONSUMED`，用户决定已经持久化。
7. 第 6 步之前的错误调用条件回滚：只有记录仍为相同 token 的 `PROCESSING` 时才恢复 `PENDING`。
8. `consume()` 成功后发生的错误只返回错误事件，不恢复确认记录。

## 不确定结果处理

Redis 可能已经执行 `consume()`，但客户端没有收到成功响应。回滚必须使用 Lua 按状态和 processing token 判断：

- 仍为相同 token 的 `PROCESSING`：恢复 `PENDING`；
- 已为 `CONSUMED`、token 不匹配或记录不存在：不回滚。

因此网络不确定状态采用 fail-closed，不会把可能已经启动的工具重新开放确认。

## 前端行为

- 恢复启动前失败且后端成功回滚时，确认项恢复为可提交状态并展示错误。
- 后端返回普通确认冲突时，仅当前确认项保持不可重复提交，不锁定整个会话。
- 会话取消错误继续使用 `SESSION_CANCELLING` 错误码锁定会话。
- 恢复流启动后的错误保持确认项已消费。

## 测试

- decisions 校验失败后记录恢复 `PENDING`。
- `chatAgentGateway.confirmExecution()` 创建失败后可以重新 claim。
- 会话执行协调器登记失败、事件源 supplier 尚未调用时可以重新 claim。
- `consume()` 成功后事件流立即失败，记录仍为 `CONSUMED`。
- `consume()` 结果不确定但 Redis 中已为 `CONSUMED` 时，条件回滚不会重新开放。
- 两个并发确认请求仍只有一个取得 processing token。
- 前端对安全回滚错误恢复确认按钮；已消费或会话取消行为不回归。
