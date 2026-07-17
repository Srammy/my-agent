# Skill 管理工具关闭时保留 Workspace Skill 可见性

## 背景

当前 `AgentScopeConfig.configureHarnessAgentBuilder` 无条件调用
`disableDefaultWorkspaceSkills()`，关闭 AgentScope 默认的用户级只读
`WorkspaceSkillRepository`。与此同时，`applySkillLearning` 在
`manageToolEnabled=false` 时直接返回，不再通过 `enableSkillManageTool` 创建可写仓库。

因此，关闭 Skill 管理工具后，用户仍能通过 Web/API 向同一份用户级 Redis
工作区上传和查看正式 Skill，但 HarnessAgent 没有对应的 Skill 仓库，Agent
无法发现和使用这些 Skill。

AgentScope Java 2.0.0-RC4 的默认语义是：Workspace Skill 仓库负责读取和使用
Skill；`enableSkillManageTool` 是可选的自学习能力，开启后会把默认只读仓库升级为
可写仓库，并注册创建、编辑 Skill 的工具。

## 目标

- `manageToolEnabled=false` 时，Agent 仍能加载用户通过 API 上传的正式 Workspace Skill。
- 关闭管理工具后，Agent 仍不能创建、编辑或晋升 Skill，也不运行 curator。
- `manageToolEnabled=true` 的现有管理、审批、晋升和 curator 行为保持不变。
- 不修改用户隔离、Redis 路径、API、前端或审批数据。

## 方案

删除 `configureHarnessAgentBuilder` 中的 `disableDefaultWorkspaceSkills()` 调用，让
AgentScope 按默认行为注册用户级只读 Workspace Skill 仓库。

保留 `applySkillLearning` 中基于 `manageToolEnabled` 的条件逻辑：

- `false`：不调用 `enableSkillManageTool`、`enableSkillPromotionGate` 和
  `enableSkillCurator`；默认只读仓库仍然存在。
- `true`：AgentScope 在构建时把默认只读仓库替换成可写仓库，并继续启用管理工具、
  审批晋升和 curator。官方构建逻辑执行替换而非追加，所以不会产生重复仓库。

不新增配置项，也不手动构造第二个 `WorkspaceSkillRepository`。

## 测试策略

在 `AgentScopeConfigTest` 中增加配置回归测试：

1. 使用 `manageToolEnabled=false` 的 `AgentProperties` 配置 Builder。
2. 验证 `disableDefaultWorkspaceSkills` 保持 AgentScope 默认值 `false`。
3. 调用 `applySkillLearning` 后验证 `skillManageToolEnabled=false`、
   `skillCuratorEnabled=false`。
4. 保留现有 `manageToolEnabled=true` 测试，验证管理工具和 curator 仍被启用。

测试先在现有代码上失败，删除禁用调用后通过。随后运行完整后端测试，并在合并回
`codex/skill-review-draft-fingerprint` 后再次运行完整测试。

## 成功标准

- 关闭 Skill 管理工具不再关闭默认 Workspace Skill 仓库。
- 管理工具和 curator 在配置关闭时仍保持关闭。
- 管理工具开启时的现有行为无回归。
- 后端全量测试在功能分支和合并后的集成分支上均通过。
- 用户原有 `.claude/` 目录不被修改或暂存。
