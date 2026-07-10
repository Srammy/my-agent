# Skill 自我进化完整交互流程

## 触发阶段

**用户和 agent 对话时**，agent 判断需要创建或改进 skill（通过 `SkillManageTool`）。

agent 把草稿写入：
```
{userId}/skills/_drafts/{skillName}/SKILL.md
```
（通过 `workspaceFilesystem` + `IsolationScope.USER`，自动加用户前缀）

---

## 审核阶段

**前端调用** `GET /api/skill-reviews`

→ `SkillReviewController.list(currentUser)`  
→ `SkillReviewService.list(userId)`  
→ 读 `{userId}/skills/_drafts/` 目录  
→ 返回该用户所有待审核 skill 列表（名称、描述、状态、创建者、使用统计）

**审核员批准：** `POST /api/skill-reviews/{skillName}/approve`

→ `SkillReviewService.approve(skillName, request, userId)`  
→ `SkillReviewDecisionStore.approve(..., userId)`  
→ 决定持久化到 `{userId}/skill-reviews/{skillName}.json`

**或拒绝：** `POST /api/skill-reviews/{skillName}/reject` — 同理

---

## 晋升阶段

**AgentScope `SkillCurator` 后台定期运行**（默认每 7 天，`SkillCuratorConfig.defaults()`）

curator 发现 `{userId}/skills/_drafts/` 下有待晋升的 skill candidate，调用：

```java
WebApprovalGate.review(candidate, ctx)   // ctx.getUserId() = 该用户 ID
```

→ `SkillReviewDecisionStore.find(skillName, ctx.getUserId())`  
→ 读 `{userId}/skill-reviews/{skillName}.json`

| 决定状态 | 返回值 | 结果 |
|---|---|---|
| 无记录 | `Defer(5 分钟后重试)` | curator 等待 |
| APPROVED | `Approve(reviewerId, environments, decidedAt)` | 草稿晋升为正式 skill |
| REJECTED | `Reject(reason, reviewerId)` | 草稿不晋升 |

---

## 生效阶段

晋升后的 skill 还需通过两层过滤器（`AgentScopeConfig.applySkillLearning`）才对 agent 可见：

1. **`EnvironmentFilter`** — skill 的 `environments` 列表是否包含当前部署环境（`skill.environment` 配置）
2. **`CanaryFilter`** — 按 `canaryPercent` 比例灰度放量，通过 `SkillUsageStore` 决定当前用户是否进入灰度

两层都通过 → skill 注入 agent 工具链 → 下次对话时 agent 可以使用该 skill

---

## 完整时序

```
用户对话
   ↓
Agent (SkillManageTool)
   ↓ 写草稿
{userId}/skills/_drafts/skillName/
   ↓
GET /api/skill-reviews          ← 前端展示待审核列表
   ↓
POST /api/skill-reviews/{name}/approve  ← 审核员操作
   ↓ 存决定
{userId}/skill-reviews/skillName.json
   ↓
SkillCurator（后台，默认每 7 天）
   ↓ WebApprovalGate 读决定
APPROVED → 草稿晋升
   ↓
EnvironmentFilter + CanaryFilter
   ↓ 通过
skill 注入 agent → 用户下次对话生效
```

---

## 用户隔离保证

全程 `userId` 贯穿，每个阶段都在 `{userId}/` 命名空间下操作：

| 阶段 | Redis 路径 |
|---|---|
| 草稿写入 | `{userId}/skills/_drafts/{skillName}/` |
| 草稿读取（审核列表）| `{userId}/skills/_drafts/` |
| 审核决定存储 | `{userId}/skill-reviews/{skillName}.json` |
| 审核决定查询（WebApprovalGate）| `{userId}/skill-reviews/{skillName}.json` |

各用户的草稿和审核决定完全隔离，互不可见。

---

## 关键类索引

| 类 | 职责 |
|---|---|
| `AgentScopeWorkspaceService` | 工作区 skill 的 CRUD（用户上传的 skill） |
| `SkillReviewController` | 审核 REST API，注入 `@AuthenticationPrincipal` |
| `SkillReviewService` | 读草稿目录，调用 decisionStore |
| `SkillReviewDecisionStore` | 持久化审核决定到 `workspaceFilesystem` |
| `WebApprovalGate` | AgentScope curator 调用的晋升闸门 |
| `AgentScopeConfig.applySkillLearning` | 注册 `SkillManageTool`、`WebApprovalGate`、`SkillCurator` |
