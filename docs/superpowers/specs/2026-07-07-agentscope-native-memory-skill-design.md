# AgentScope 原生记忆与 Skill 体系切换设计

日期：2026-07-07

## 目标

将当前应用中的记忆和 skill 自学习能力切换到 AgentScope Java Harness 原生体系：

- 记忆不再由应用自建表和接口实现，改由 AgentScope Harness 自动维护每日记忆和长期记忆。
- Skill 不再使用自建 MySQL `skills`、`skill_files`、`user_skill_settings` 作为真相源，改由 AgentScope workspace/filesystem/repository 管理。
- 自我进化只保留 AgentScope 的 skill 自学习闭环：agent 生成草稿，人工审核闸门决定是否晋升，发布后的可见性由 AgentScope filter 控制。
- 本地部署和分布式集群部署都必须支持同一套语义。

## 非目标

- 不再维护应用自建的通用 evolution proposal 类型，例如 `MEMORY`、`TOOL_POLICY`、`PROMPT`、`CODE_PATCH`。
- 不让自学习流程自动打开高权限工具。
- 不升级 AgentScope Java 版本；本设计基于当前项目依赖的 `io.agentscope:agentscope-harness:2.0.0-RC4` 可用 API。

## 当前问题

当前实现有三套与 AgentScope 原生能力重叠的应用层实现：

- `memory/UserMemoryEntity`、`UserMemoryMapper`、`MemoryService` 和 `/api/memory/**` 自己维护长期/每日记忆。
- `skill/SkillEntity`、`SkillFileEntity`、`SkillService`、`SkillMaterializer` 将 MySQL skill 物化成本地目录，再传给 AgentScope。
- `evolution/EvolutionProposalEntity`、`EvolutionService` 自己维护 proposal 状态，并直接 apply 到 MySQL skill 或 memory 表。

同时 `AgentScopeConfig.configureHarnessAgentBuilder` 当前显式关闭了多项 Harness 原生能力：

```java
disableDynamicSkills()
disableDefaultWorkspaceSkills()
disableMemoryTools()
disableMemoryHooks()
```

这些设置与目标相反，需要改为按配置打开 AgentScope 原生记忆和 skill 闭环。

## 方案概览

### 记忆

记忆由 AgentScope Harness 自动工作。应用层删除或停用记忆 CRUD：

- 后端不再暴露 `/api/memory/**`。
- 前端移除 `MemoryPanel`。
- 后端不再读写 `user_memories`。
- 构建 `HarnessAgent` 时启用 memory hooks 和 memory tools，并使用 `MemoryConfig`。

运行时由 Harness 维护：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

应用只负责传入正确的 `RuntimeContext.userId` 和 `RuntimeContext.sessionId`，不再解释、合并或编辑记忆内容。

### Skill

所有 skill 文件内容切换到 AgentScope workspace/filesystem：

```text
skills/<skillName>/SKILL.md
skills/<skillName>/references/**
skills/<skillName>/scripts/**
skills/<skillName>/assets/**
skills/_drafts/<skillName>/**
```

自建 MySQL skill 体系删除，不再作为代码或 schema 的一部分：

- 不再从 MySQL 查询 skill。
- 不再物化 MySQL skill 到本地目录。
- `HarnessAgent` 不再加载 MySQL materialized skill root。
- 删除 `SkillEntity`、`SkillFileEntity`、`UserSkillSettingEntity`、对应 mapper、MySQL `SkillService` 和 `SkillMaterializer`。
- 删除 schema 中的 `skills`、`skill_files`、`user_skill_settings` 表定义。
- UI 中的 skill 管理改为操作 AgentScope workspace/filesystem。

### 自学习闭环

自学习只覆盖 AgentScope skill 闭环：

1. Agent 通过 AgentScope `SkillManageTool` 创建或修改草稿。
2. 草稿保存在 AgentScope workspace 的 `skills/_drafts/**`。
3. 草稿晋升正式 skill 必须经过 `SkillPromotionGate`。
4. 审核通过后，AgentScope 更新 `SkillUsageStore`。
5. 推理时能否看到 agent-created skill，由 `SkillVisibilityFilter` 决定。

生产/集群不使用 stdin 形式的 `LocalApprovalGate`。本项目应实现 Web 审核 gate，或封装 AgentScope 的等待式审核 gate，使人工审核通过 Web UI 完成。

配置形态：

```java
builder.enableSkillManageTool(skillManageConfig);
builder.enableSkillPromotionGate(
    promotionGate,
    new CompositeFilter(List.of(
        new EnvironmentFilter(environment, skillUsageStore),
        new CanaryFilter(canaryPercent, skillUsageStore)
    )));
builder.environment(environment);
```

`canaryPercent` 在当前 RC4 API 中是整数百分比，例如 `10` 表示 10%。

## 本地与分布式

### 本地模式

本地模式使用本地 AgentScope workspace：

```text
.agentscope/workspace
```

记忆、skill 草稿、正式 skill、usage record 都落在本地 workspace/filesystem 下。适合单机开发和手动调试。

### 分布式模式

分布式模式使用 AgentScope remote filesystem：

```java
new RemoteFilesystemSpec(baseStore)
    .isolationScope(IsolationScope.USER)
```

含义：

- 每个 `RuntimeContext.userId` 拥有独立 workspace 命名空间。
- 同一用户在不同后端副本之间共享记忆、skill 草稿、正式 skill 和 usage record。
- 不同用户之间互相隔离。

当前项目已有 Redis-backed `BaseStore` 和 `AgentStateStore`，应复用它作为 remote filesystem 的共享存储基础。后端多副本仍然保持无状态。

## API 与 UI 调整

### 删除或停用记忆接口

删除或停用：

```text
GET /api/memory/summary
GET /api/memory/daily
GET /api/memory/daily/{date}
```

前端移除记忆面板。记忆能力只通过对话中的 AgentScope memory tools/hooks 生效。

### Skill 管理接口

现有 `/api/skills/**` 可保留路径，但实现从 MySQL CRUD 改为 AgentScope workspace 文件操作。Skill 面板看到的是 AgentScope 体系自身维护的 skill，而不是旧 MySQL skill：

```text
GET    /api/skills/system
GET    /api/skills/mine
POST   /api/skills/mine
PUT    /api/skills/mine/{skillName}
DELETE /api/skills/mine/{skillName}
GET    /api/skills/{skillName}/files
PUT    /api/skills/{skillName}/files/{path}
DELETE /api/skills/{skillName}/files/{path}
PUT    /api/skills/{skillName}/enabled
```

`skillName` 必须做路径安全校验。`SKILL.md` frontmatter 校验仍然保留，但校验服务应面向 workspace 文件，而不是 MySQL entity。

### 自学习审核接口

现有 `/api/evolution/proposals/**` 语义改为 AgentScope skill 草稿审核。也可以重命名为更清晰的路径：

```text
GET  /api/skill-reviews
POST /api/skill-reviews/{skillName}/approve
POST /api/skill-reviews/{skillName}/reject
```

审核列表来自 AgentScope workspace 中的 `skills/_drafts/**` 和 `SkillUsageStore.agentCreatedReport()`。审批动作必须通过 `SkillPromotionGate` 对应的人工审核流程完成，不直接绕过 AgentScope 晋升机制。

前端 `EvolutionPanel` 改为 `SkillReviewPanel`：

- 展示待审核 skill 草稿。
- 展示 `createdBy`、`sourceSessionId`、`environments`、`useCount/viewCount/patchCount`、状态。
- 支持批准和拒绝。
- 不再展示 MEMORY、TOOL_POLICY、PROMPT、CODE_PATCH proposal。

## 配置

新增或调整配置：

```yaml
agent:
  workspace:
    path: ./.agentscope/workspace
  memory:
    enabled: true
  skill:
    storage: agentscope
    environment: prod
    canary-percent: 10
    manage-tool-enabled: true
    security-scan-enabled: true
    approval-mode: web
  deployment:
    mode: local # local | distributed
```

分布式配置仍使用 Redis：

```yaml
agent:
  deployment:
    mode: distributed
  state-store:
    type: redis
    redis:
      uri: redis://${REDIS_HOST:redis}:6379
      key-prefix: myagent:agent-state:
```

`approval-mode` 可取：

- `web`：Web 审核 gate，适合生产和集群。
- `local`：`LocalApprovalGate`，只适合本地调试。
- `reject`：`RejectAllGate`，禁止 agent-created skill 晋升。

## 删除旧实现

不考虑旧版本升级兼容，直接删除自建 MySQL skill、memory 和 evolution proposal 实现：

- 删除后端 `skill` 包中依赖 MySQL 的 entity、mapper、service、materializer 和相关测试。
- 保留或重写路径/frontmatter 校验逻辑，使其服务于 AgentScope workspace 文件 API。
- 删除后端 `memory` 包中的自建 memory entity、mapper、service、controller 和相关测试。
- 删除后端 `evolution` 包中的自建 proposal entity、mapper、service、controller 和相关测试，替换为 skill review/gate 相关实现。
- 从初始化 schema 中移除 `skills`、`skill_files`、`user_skill_settings`、`user_memories`、`agent_evolution_proposals`。
- 前端删除 memory API/store/panel。
- 前端 evolution API/store/panel 改为 skill review API/store/panel。

## 错误处理

- AgentScope workspace 读写失败返回清晰的 `500`，日志包含 workspace 路径或 remote store namespace。
- 非法 skill 路径返回 `400`。
- 缺少 `SKILL.md` 或 frontmatter 不合法返回 `400`。
- 分布式模式缺少 Redis store 时启动失败，不退回本地 workspace，避免多副本状态分裂。
- 生产环境若配置 `approval-mode=local`，启动失败或至少拒绝启用，避免服务阻塞在 stdin 审核。

## 测试策略

后端测试：

- `AgentScopeConfig` 启用 memory hooks/tools，不再关闭 memory。
- local 模式构建 local workspace filesystem。
- distributed 模式构建 `RemoteFilesystemSpec(IsolationScope.USER)` 并注入 Redis-backed `BaseStore`。
- skill runtime 不存在 `SkillMaterializer` 或 MySQL skill mapper 依赖。
- skill 文件 API 读写 AgentScope workspace。
- skill review API 从 `SkillUsageStore` 和 `_drafts` 读取草稿。
- approval gate 只有人工批准后才允许晋升。
- visibility filter 使用 `EnvironmentFilter + CanaryFilter`，canary 百分比来自配置。

前端测试或 smoke check：

- Chat 页面不再显示 MemoryPanel。
- Skill 面板展示 AgentScope workspace 中的正式 skill，并能创建、编辑、删除 workspace skill。
- Skill 审核面板能列出草稿并执行批准/拒绝。
- 不再出现 MEMORY/TOOL_POLICY/PROMPT/CODE_PATCH proposal 类型。

验收标准：

- 记忆由 AgentScope 在对话后自动维护，应用不再读写 `user_memories`。
- 新建和编辑 skill 不再写入 MySQL skill 表。
- Agent 自学习生成的 skill 先进入 `_drafts`，未审核不能成为正式 skill。
- 人工审核通过后，skill 经过 AgentScope promotion gate 晋升。
- 已晋升的 agent-created skill 受 environment 和 canary filter 控制。
- 本地和分布式部署都能读写同一语义的记忆、skill、usage record。
