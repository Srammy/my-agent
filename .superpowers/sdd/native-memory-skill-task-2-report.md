# Task 2 Report: 删除自建 Memory 并启用 AgentScope Harness Memory

## 状态

已完成，实现基于 `AgentProperties.Memory.enabled()` 启用/关闭 AgentScope Harness Memory，并删除前后端自建 Memory 实现。

额外处理了一处直接相关依赖：`EvolutionService` 原先会写入已删除的 `myagent.memory` 包。由于 brief 仅要求启用 Harness memory、未提供业务层写入 Harness memory 的集成方式，这里将 `EvolutionProposalType.MEMORY` 的 apply 行为改为返回 `409 CONFLICT`，并同步更新测试。

## 改动文件列表

- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- `backend/src/main/java/com/example/myagent/evolution/EvolutionService.java`
- `backend/src/main/resources/db/migration/V2__permission_and_memory.sql`
- `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- `backend/src/test/java/com/example/myagent/evolution/EvolutionServiceTest.java`
- `frontend/src/style.css`
- `frontend/src/views/ChatView.vue`
- 删除 `backend/src/main/java/com/example/myagent/memory/MemoryController.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/MemoryDailyDto.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/MemoryDailyListDto.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/MemoryService.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/MemorySummaryDto.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/UserMemoryEntity.java`
- 删除 `backend/src/main/java/com/example/myagent/memory/UserMemoryMapper.java`
- 删除 `backend/src/test/java/com/example/myagent/memory/MemoryControllerTest.java`
- 删除 `backend/src/test/java/com/example/myagent/memory/MemoryServiceTest.java`
- 删除 `frontend/src/api/memory.ts`
- 删除 `frontend/src/components/MemoryPanel.vue`

## 提交 hash

`da85f42`

## 运行过的测试命令和结果

1. `mvn -q -Dtest=AgentScopeConfigTest#productionHarnessKeepsMemoryHooksAndToolsEnabled test`
   - 首次运行：失败，原因符合预期，`configureHarnessAgentBuilder` 仍为旧签名
   - 实现后再次运行：通过
2. `mvn -q "-Dtest=AgentScopeConfigTest,EvolutionServiceTest" test`
   - 通过
3. `mvn -q -Dtest=AgentScopeConfigTest test`
   - 通过
4. `npm run build`
   - 通过；保留现有 Vite chunk size warning，不是这次改动引入

## 自检

- `AgentScopeConfig` 仅在 `agentProperties.memory().enabled()` 为 `true` 时注入 `MemoryConfig.defaults()`，为 `false` 时显式关闭 memory tools/hooks
- `buildHarnessAgent` 已改为传入 `AgentProperties`
- 自建 memory 后端包、对应测试、前端面板/API、迁移表定义均已删除
- `ChatView` 已移除 Memory 标签页
- 样式中仅删掉 `MemoryPanel` 独占选择器，没有顺手改其它 UI
- 全仓搜索已无 `com.example.myagent.memory`、`/api/memory`、`MemoryPanel`、`user_memories` 残留引用
## Reviewer follow-up

reviewer 指出 `MEMORY` proposal 仍然能创建/审批，但 apply 已经变成 409，形成死路径。这里补了创建侧拦截：`EvolutionService#createProposal` 现在对 `EvolutionProposalType.MEMORY` 直接返回 `400 BAD_REQUEST`，消息为 `Memory is managed by AgentScope Harness`。同时前端 `EvolutionProposalType` 已移除 `MEMORY`，避免 UI/类型层继续发出该值。

新增/更新的测试：

- `EvolutionServiceTest#createProposalRejectsMemoryProposalType`
- 现有 `AgentScopeConfigTest` / `EvolutionServiceTest` 继续通过

## 本次补充验证

1. `mvn -q "-Dtest=AgentScopeConfigTest,EvolutionServiceTest" test`
   - 通过
2. `npm run build`
   - 通过

## 最新提交 hash

见最终回复
