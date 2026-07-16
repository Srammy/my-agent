# Skill 审批绑定草稿指纹设计

## 背景

当前 Skill 审核决定只按 `userId + skillName` 保存。审批接口不确认草稿是否存在，决定中也不记录被审核草稿的具体版本。因此，审核员可以提前批准不存在的 Skill；已经批准或拒绝的草稿被修改后，新内容仍会复用旧决定。

本设计将每个审批决定绑定到该用户完整草稿包的 SHA-256 指纹。任何文件路径或内容发生变化后，旧决定都失效，草稿重新进入 `PENDING` 状态。

## 目标

- 批准和拒绝操作都必须针对真实存在、可完整读取的草稿。
- 审批决定只对审核时的完整草稿包有效。
- 修改 `SKILL.md`、`references/`、`scripts/`、`assets/` 中任一文件路径或内容都会使旧决定失效。
- 保持现有用户级隔离和跨会话共享语义。
- 旧 JSON 审批记录能够读取，但因没有指纹而不再生效。

## 非目标

- 不建设多版本审批历史和历史查询界面。
- 不修改前端 API 或页面。
- 不修改 Skill 创建、草稿生成和 AgentScope 晋升流程。
- 不修改 Redis 路径、用户隔离策略或聊天会话语义。

## 用户隔离与上下文

`skills/_drafts/<skillName>` 是用户命名空间内的逻辑路径，不是全局共享路径。实际寻址由以下信息共同决定：

```text
RuntimeContext.userId
    + IsolationScope.USER.toNamespaceFactory()
    + skills/_drafts/<skillName>/
```

指纹计算的所有 `exists`、`glob` 和 `read` 操作必须显式传入包含当前 `userId` 的 `RuntimeContext`，禁止使用 `RuntimeContext.empty()`。

Skill 保持用户级、跨会话共享。审核 HTTP 接口不携带产生草稿的聊天 session，使用固定的 `sessionId("skill-review")` 作为操作标识；该值不参与当前 `IsolationScope.USER` 的隔离键。审批有效性由 `userId + skillName + draftHash` 决定。

## 核心组件

新增 `SkillDraftFingerprint`，集中负责草稿存在性验证和稳定指纹计算。建议接口：

```java
String computeDraftHash(RuntimeContext context, String skillName);
```

该组件读取当前用户命名空间中的 `skills/_drafts/<skillName>/`。草稿目录或 `SKILL.md` 不存在、目录无法枚举、任一文件无法读取时，计算失败，不返回部分指纹。

组件使用一个带原因类型的领域异常区分 `NOT_FOUND` 和 `READ_FAILURE`。它不直接抛 Web 层的 `ResponseStatusException`：`SkillReviewService` 将 `NOT_FOUND` 映射为 HTTP 404、将 `READ_FAILURE` 映射为服务器错误；`WebApprovalGate` 捕获这两类失败并统一安全降级为 `Defer`。

`SkillReviewService` 和 `WebApprovalGate` 必须共用该组件，避免审批端和晋升端采用不同算法。

## 指纹算法

1. 枚举草稿目录下的全部普通文件，包括 `SKILL.md` 及所有支持文件。
2. 将每个文件路径转换为相对于草稿根目录的路径，并统一使用 `/` 分隔符。
3. 按相对路径字典序排序，消除文件系统枚举顺序差异。
4. 对每个文件依次向 SHA-256 输入：
   - UTF-8 路径的字节长度；
   - UTF-8 路径字节；
   - 文件内容的字节长度；
   - 文件内容按 UTF-8 编码后的字节。
5. 输出小写十六进制 SHA-256。

长度字段使用固定宽度编码，避免不同路径与内容组合在直接拼接时产生边界歧义。哈希覆盖路径和内容，因此重命名、增加、删除或修改文件都会改变结果。当前 AgentScope RC4 的 `AbstractFilesystem.read` 和 `AgentSkill` 资源模型以字符串承载 Skill 文件，本设计与该存储语义一致，不额外引入二进制文件协议。

## 数据模型

`SkillReviewDecision` 增加：

```java
String draftHash
```

每个 Skill 仍只保存最新一条决定，新审核覆盖旧决定。现有 JSON 没有 `draftHash` 时，Jackson 将其读取为 `null`。`null` 表示旧决定没有绑定草稿版本，必须视为无效，不能继续批准或拒绝当前草稿。

## 审批与拒绝流程

1. 根据登录用户构造用户级 `RuntimeContext`。
2. 校验 Skill 名称。
3. 计算完整草稿包的 `draftHash`。
4. 草稿目录或 `SKILL.md` 不存在时返回 HTTP 404，不保存决定。
5. 文件系统读取失败时返回服务器错误，不保存决定。
6. 保存 `status`、审核人、环境或拒绝原因、时间和 `draftHash`。
7. 返回当前决定 DTO。

批准和拒绝遵循相同的版本绑定规则。被拒绝的草稿修改后也重新进入待审，而不是永久继承旧拒绝。

## 审核列表的有效状态

审核列表不能直接展示持久化决定的 `status`，而应展示相对当前草稿计算出的有效状态：

- 没有决定：`PENDING`。
- 当前 hash 与决定的 `draftHash` 相同：展示 `APPROVED` 或 `REJECTED`。
- hash 不同：`PENDING`。
- 决定的 `draftHash` 为 `null`：`PENDING`。
- 草稿无法完整读取：不把旧决定显示为有效，状态为 `PENDING`，描述可为空。

旧决定不主动删除，以便排查；下一次有效审核会覆盖它。

## PromotionGate 流程

`WebApprovalGate.review(candidate, context)` 按以下顺序处理：

1. 按 `candidate.name()` 和 `context.userId` 查询决定。
2. 没有决定时返回 `Defer("Pending web review")`。
3. 决定没有 `draftHash` 时返回 `Defer("Draft version requires review")`。
4. 通过相同的用户 `RuntimeContext` 重新计算当前完整草稿包 hash。
5. hash 不一致时返回 `Defer("Draft changed after review")`。
6. hash 一致时，才将持久化的 `APPROVED` 或 `REJECTED` 转换成 AgentScope 的 `Approve` 或 `Reject`。
7. 草稿缺失或读取失败时安全降级为 `Defer`，不允许晋升，也不使 curator 因审核辅助组件异常而中断。

AgentScope RC4 没有提供草稿目录的事务快照。本设计在返回决定前立即复核当前内容；若复核后又发生并发修改，后续调用会检测到新 hash。该限制不扩大现有文件系统的一致性承诺。

## 错误处理

- 无效 Skill 名称：HTTP 400。
- 草稿目录或 `SKILL.md` 不存在：HTTP 404。
- 审批阶段的文件枚举或读取失败：服务器错误，且不写决定。
- Gate 阶段的草稿缺失、枚举失败或读取失败：返回 `Defer`。
- 旧决定、未知状态或 hash 不匹配：不抛出晋升异常，返回 `Defer`。

## 修改范围

- 新增 `SkillDraftFingerprint`。
- 修改 `SkillReviewDecision`，增加 `draftHash`。
- 修改 `SkillReviewDecisionStore.approve/reject`，接收并保存 hash。
- 修改 `SkillReviewService`，在审批前生成 hash，并在列表中解析有效状态。
- 修改 `WebApprovalGate`，在应用决定前复核 hash。
- 通过构造器注入共享指纹组件。
- 更新相关单元测试。

不修改前端、Redis 路径、AgentScope Skill 仓库、Skill 管理工具或 curator 的其他配置。

## 测试策略

### 指纹组件

- 相同文件以不同枚举顺序返回相同 hash。
- 修改 `SKILL.md` 改变 hash。
- 修改 `scripts/`、`references/` 或 `assets/` 改变 hash。
- 用户 A 和用户 B 的同名草稿分别读取，不发生串用。
- 草稿目录或 `SKILL.md` 缺失时失败。
- 任一文件读取失败时不生成部分 hash。

### SkillReviewService

- 不存在的草稿不能批准或拒绝。
- 批准和拒绝都保存当前 `draftHash`。
- hash 一致时列表展示实际决定。
- hash 不一致时列表展示 `PENDING`。
- 旧决定的 hash 为 `null` 时列表展示 `PENDING`。

### WebApprovalGate

- hash 一致时才批准或拒绝。
- 草稿改变后返回 `Defer`。
- 旧决定没有 hash 时返回 `Defer`。
- 草稿缺失或读取失败时返回 `Defer`。

### 决定存储

- `draftHash` 能够序列化和反序列化。
- 旧 JSON 缺少 `draftHash` 时仍可读取，字段值为 `null`。

## 成功标准

- 失败测试能够复现“无草稿可审批”和“修改后复用旧决定”。
- 所有 Skill 相关测试通过。
- 后端完整测试通过。
- 旧审批记录不导致反序列化异常，也不再对任何当前草稿生效。
- 两个用户的同名草稿及审批指纹互不影响。
