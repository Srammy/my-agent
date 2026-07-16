# Skill 审批列表路径解析修复设计

日期：2026-07-16

## 背景

AgentScope `RemoteFilesystem.ls("skills/_drafts")` 返回的目录项 `FileInfo.path` 是完整逻辑路径，
例如 `/skills/_drafts/my-skill`。当前 `SkillReviewService.list` 直接把该字段当作 skill 名称，随后会
拼出重复路径 `skills/_drafts//skills/_drafts/my-skill/SKILL.md`，审批决定和草稿指纹查询也会使用
错误名称。

## 目标

- 将 RemoteFilesystem 返回的完整目录路径解析为直接子目录名称。
- DTO、SKILL.md 读取、审批决定和指纹查询始终使用规范化后的 skill 名称。
- 兼容只返回裸目录名的文件系统实现或测试替身。
- 忽略空白、非直接子目录或非法 skill 名称，不让异常目录项中断整个列表。

## 非目标

- 不修改 AgentScope 或 `AbstractFilesystem.ls` 的全局返回契约。
- 不修改审批状态、指纹算法、提升流程或异常降级策略。
- 不修复审批到移动之间的 TOCTOU 竞态。

## 方案

在 `SkillReviewService` 增加一个只负责列表边界转换的私有方法：

```java
private static Optional<String> draftSkillName(String path)
```

处理规则：

1. `null` 或空白路径返回 `Optional.empty()`。
2. 将反斜杠统一为 `/`，去掉首尾 `/`。
3. 若路径以 `skills/_drafts/` 开头，只接受其后的单个直接子目录名。
4. 若路径完全不包含 `/`，将其作为兼容的裸目录名。
5. 其他路径、嵌套路径或非法名称通过 `SkillPathValidator.validateSkillName` 过滤掉。

列表流水线先过滤目录，再把 `FileInfo.path` 转换成上述 `Optional<String>`，之后的排序、DTO 构建、
审批查询和指纹计算全部使用该名称。

## 测试策略

- 将列表测试的目录项改为 `/skills/_drafts/my-skill`，验证 DTO 名称仍为 `my-skill`。
- 验证 SKILL.md 读取路径是 `skills/_drafts/my-skill/SKILL.md`，不存在重复前缀。
- 验证审批决定和指纹查询使用 `my-skill`。
- 验证裸目录名 `my-skill` 仍可解析。
- 验证其他根路径、嵌套目录和非法名称被忽略。
- 运行 `SkillReviewServiceTest` 和后端完整测试套件。

## 验收标准

- RemoteFilesystem 完整路径输入能够得到正确的审批列表。
- 同名审批决定能够被正确关联，不再因为完整路径显示为 `PENDING`。
- 不产生重复的 `_drafts` 路径。
- 后端完整测试通过。
