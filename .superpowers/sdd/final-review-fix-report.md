# Final review fixes report

## 2026-07-30 Binary Workspace Storage

- Scope: no reads, writes, staging, or commits under `.claude/`.
- Root cause: AgentScope 2.0.0-RC4 decodes bytes with the replacement-mode
  UTF-8 `String` constructor, leaving its Base64 fallback unreachable for
  malformed binary input.
- RED: the real `InMemoryStore` and production workspace bean changed PNG-like
  bytes `[-119, ..., -1]` into `[-17, -65, -67, ..., -17, -65, -67]` on
  `SkillResources.readBinary`. Nested `references/SKILL.md`,
  `scripts/SKILL.md`, and `assets/SKILL.md` were accepted, and an escaped YAML
  name did not trigger duplicate detection on its canonical name.
- GREEN: `AgentScopeConfigTest`, `AgentScopeWorkspaceServiceTest`, and
  `SkillControllerTest` passed with 59 tests, 0 failures, 0 errors, and 0
  skipped after the project-owned remote filesystem adapter, canonical-name
  storage flow, marker validation, and 64 KiB multipart memory threshold.
- Full suite: `mvn -q -f backend/pom.xml test` exited 0; Surefire XML reports
  33 suites, 283 tests, 0 failures, 0 errors, and 0 skipped.
- Diff checks: pre-commit `git diff --check` and post-commit
  `git diff --check ac5a631..HEAD` both exited 0.

- 分支：`fix-tool-permission-hitl`
- 基线：`d8efbc715edeea51d0cfd65bdedc40b449c9ea5e`
- 日期：2026-07-13（Asia/Shanghai）
- 边界：未读取、修改、暂存或提交 `.claude/`

## Finding 1（Important）：null-valued tool inputs

根因：`ToolCallSnapshot.from` 对 AgentScope 提供的普通输入 map 调用 `Map.copyOf`；显式 null 值在快照和 Redis 写入前触发 `NullPointerException`。

RED 1：

```powershell
cd backend
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q '-Dtest=ToolConfirmationServiceTest#snapshotRoundTripPreservesNullInputAndIsDefensive' test
```

退出码 1；1 test / 1 error；栈顶业务位置为 `ToolCallSnapshot.from(ToolCallSnapshot.java:8)`，原因是 `Map.copyOf` 的 `NullPointerException`。

RED 2：

```powershell
mvn -q '-Dtest=AgentScopeChatAgentGatewayTest#registersAndPublishesConfirmationWithNullToolInput' test
```

退出码 1；期望 `permission_required`，实际为 `error`。

修复：用 `new LinkedHashMap<>(input)` 建立保序防御副本，并以 `Collections.unmodifiableMap` 包装。测试同时验证显式 null、另一非 null 项、源 map 后续修改不影响快照、快照 map 不可修改，以及 `toToolUseBlock()` 的真实往返。网关测试验证含 null 输入的事件仍调用注册服务并发布确认元数据。

GREEN：组合后端命令（见“最终验证”）退出码 0，两项覆盖测试均通过。

## Finding 2（Minor）：claim lease 使用应用节点时钟

根因：claim 将 `System.currentTimeMillis()` 作为 `ARGV[3]` 传给 Lua，租约过期判断受应用节点时钟偏差影响。该时间仅在私有 claim Lua 内用于比较和加 30,000 ms，能够局部修复而不改变公开语义。

RED：

```powershell
mvn -q '-Dtest=ToolConfirmationServiceTest#claimUsesRedisTimeAndPassesOwnerTokenAndLease' test
```

退出码 1；断言显示 claim 脚本不包含 `redis.call('TIME')`，仍使用应用传入的时间参数。

修复：claim Lua 调用 Redis `TIME`，以秒和微秒计算 epoch ms；Java 参数从 owner/session/time/token/lease 改为 owner/session/token/lease。租约仍为 30,000 ms，TTL、状态和 token 流程未变。集成测试的租约区间与人工过期值均改用 Redis TIME。

GREEN：

```powershell
mvn -q '-Dtest=ToolConfirmationRedisIntegrationTest' test
```

退出码 0；真实 Redis 7 Testcontainers 覆盖通过，包括租约、过期重领、TTL、consume/release/token 行为。

## Finding 3（Minor）：consumed duplicate submit

新增直接回归测试：先设 `event.consumed = true`，再调用 `store.confirmTool(...)`，断言 `confirmToolCall` 未调用。首次运行即通过，证明现有前置守卫已满足需求；未修改生产代码。

```powershell
cd frontend
npm test -- --run src/stores/__tests__/chat.spec.ts
```

退出码 0；1 file，12 tests passed。

## Finding 4（报告可复现性 Minor）

`.superpowers/sdd/task-4-report.md` 仅保留了设置 cutoff、枚举 XML 和筛选时间的命令，聚合 tests/failures/errors/skipped 的部分已被占位注释替代。该报告没有可用 Git 历史版本，仓库其余 `.superpowers/sdd` 内容也没有保存完整命令。无法可靠恢复，因此有意保留为文档 minor；未伪造、未追加猜测命令。

## 最终验证

后端：

```powershell
cd backend
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q '-Dtest=ToolConfirmationServiceTest,ToolConfirmationRedisIntegrationTest,AgentScopeChatAgentGatewayTest' test
```

退出码 0；Surefire XML 汇总：3 suites，28 tests，0 failures，0 errors，0 skipped。Redis Testcontainers 连接 Docker Desktop 29.5.3 并启动 `redis:7-alpine`。仅有既存的 Byte Buddy 动态 agent/JVM class-data-sharing 警告。

前端：

```powershell
cd frontend
npm test -- --run src/stores/__tests__/chat.spec.ts
```

退出码 0；1 file，12 tests passed。

边界：`git diff --check` 退出码 0。`git status --short` 在本次六个代码/测试文件外仅显示既存未跟踪 `.claude/`；该目录未触碰。

## 自审

- 每一处生产修改均对应 finding：null-safe 防御快照、Redis server time；无相邻重构或新配置。
- grouped confirmation 的列表、顺序和一次注册语义未改变。
- Lua 的 owner/session、CONSUMED、PROCESSING、token、30 秒 lease、PTTL/SET PX 分支保持原样，仅替换时间来源并顺移参数。
- null 测试使用可变 `LinkedHashMap`，覆盖保序、显式 null、源变更隔离、不可修改和网关发布。
- consumed 测试验证现有行为，因此没有制造无必要的前端生产改动。
- Finding 4 缺少可恢复证据，明确保留为文档 minor，不伪造命令。
