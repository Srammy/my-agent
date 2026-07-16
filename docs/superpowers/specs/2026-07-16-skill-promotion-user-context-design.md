# Skill 提升用户上下文修复设计

日期：2026-07-16

## 背景

当前 workspace 使用 `IsolationScope.USER`，正常调用必须携带 `RuntimeContext.userId` 才能定位用户命名空间。
但是 AgentScope Harness `2.0.0-RC4` 的 `SkillPromoter` 在批准后使用
`RuntimeContext.empty()` 调用 `WorkspaceManager.moveSkill`。最新 `2.0.0` GA 仍有相同行为。

因此，`WebApprovalGate` 能在当前用户空间校验并批准草稿，但随后的移动会落到空命名空间，
无法把该用户的 `skills/_drafts/<skillName>` 提升到 `skills/<skillName>`。
`SkillUsageStore` 同样固定使用空上下文；如果给它传入依赖上下文解析用户的共享文件系统，
usage 数据也不会落到当前用户空间。

## 目标

- AgentScope 内部即使传入空上下文，也只能操作当前请求用户的 workspace。
- 同一用户的 skill 和 usage 数据跨会话共享。
- 不同用户的数据继续隔离。
- Web 审批服务与 AgentScope 读取同一用户的 usage 数据。
- 不复制或覆盖 AgentScope 内部类。

## 非目标

- 本次不解决审批校验与实际移动之间的 TOCTOU 竞态；该问题在后续独立分支修复。
- 不改变审批状态、重试周期或 curator 的触发语义。
- 不升级 AgentScope 版本。

## 方案

### 共享存储与两种文件系统视图

将 Redis `BaseStore` 提升为共享 Bean，并基于它提供两种文件系统视图：

1. `workspaceFilesystem`：继续使用 `IsolationScope.USER.toNamespaceFactory()`。它供能够显式传入
   `RuntimeContext` 的 Web/API 服务使用。
2. `UserScopedFilesystemFactory.create(userId)`：返回固定命名空间为 `[userId]` 的
   `RemoteFilesystem`。它忽略调用处是否携带上下文，只能访问构造时绑定的用户空间。

两种视图使用同一个 `BaseStore`，所以看到的是同一份 Redis 数据，只是用户命名空间的解析方式不同。

### AgentScope 请求构建

每次构建 `HarnessAgent` 时：

1. 根据认证请求中的 `userId` 创建固定用户文件系统。
2. 通过 `HarnessAgent.Builder.abstractFilesystem(...)` 注入该实例，不再让 Builder 根据
   `RemoteFilesystemSpec` 创建依赖运行时上下文的文件系统。
3. 使用同一实例创建该请求的 `SkillUsageStore`，并传给 promotion、visibility 和 curator 组件。

这样，AgentScope 内部的 `RuntimeContext.empty()` 会在固定用户文件系统中解析为当前用户，
不会回退到全局空间，也不能切换到其他用户。

### Web 审批 usage 查询

移除共享的 `SkillUsageStore` Bean。`SkillReviewService` 按当前认证用户通过
`UserScopedFilesystemFactory` 创建 `SkillUsageStore`，确保审批列表和审批响应展示该用户自己的
use/view/patch、来源会话及环境信息。

审批决定与草稿指纹仍使用 `workspaceFilesystem`，并继续显式传入用户上下文。

## 错误处理

- `UserScopedFilesystemFactory` 拒绝空白 `userId`，避免意外创建全局视图。
- Redis/BaseStore 异常保持现有传播方式，本次不改变接口错误语义。

## 测试策略

- 使用 `InMemoryStore` 验证固定 Alice 文件系统在 `RuntimeContext.empty()` 下可以写入和移动草稿。
- 使用上下文型 USER 文件系统验证 Alice 的结果对 Alice 可见、对 Bob 不可见。
- 验证同一 Alice 用户的不同会话读取相同数据。
- 验证 AgentScope Builder 注入固定用户文件系统，且 promotion 使用与其相同命名空间的
  `SkillUsageStore`。
- 验证 `SkillReviewService` 按用户读取 usage 数据，不再读取空/全局命名空间。
- 运行后端完整测试套件。

## 验收标准

- 当前用户批准后的草稿能从 `_drafts` 提升到正式 skill 目录。
- 提升过程不会读取或写入其他用户的命名空间。
- 同一用户跨会话可访问已提升 skill。
- 审批页面展示的 usage 数据属于当前用户。
- 后端完整测试通过。
