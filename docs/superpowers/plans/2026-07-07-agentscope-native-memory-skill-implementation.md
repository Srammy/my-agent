# AgentScope 原生记忆与 Skill 体系切换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除应用自建 MySQL memory/skill/evolution 实现，改用 AgentScope Harness 原生 memory、workspace skill、SkillUsageStore、promotion gate 和 visibility filter。

**Architecture:** Spring Boot 后端保留登录、会话、权限和聊天流，新增 AgentScope workspace 服务作为 skill 文件 API 和 review API 的唯一数据源。`HarnessAgent` 构建时启用 memory、skill manage tool、promotion gate 和 visibility filter；本地使用 local filesystem，分布式使用 Redis-backed remote filesystem with `IsolationScope.USER`。

**Tech Stack:** Java 21、Spring Boot 3 WebFlux、MyBatis-Plus、Redis Reactive、AgentScope Java Harness `2.0.0-RC4`、Vue 3、TypeScript、Pinia、Element Plus、Maven。

## Global Constraints

- 使用中文对话和中文文档。
- 不再使用自建 MySQL `skills`、`skill_files`、`user_skill_settings` 体系。
- 不再使用自建 MySQL `user_memories` 记忆体系。
- 不再使用自建 `agent_evolution_proposals` proposal 体系。
- 记忆由 AgentScope Harness 自动维护 `MEMORY.md` 和 `memory/YYYY-MM-DD.md`。
- Skill 面板展示 AgentScope workspace 中的正式 skill。
- 自学习审核面板展示 AgentScope `skills/_drafts/**` 和 `SkillUsageStore` 中的 agent-created skill。
- 草稿晋升必须经过 `SkillPromotionGate`，不能由业务代码直接绕过 gate。
- 已晋升 agent-created skill 必须经过 `EnvironmentFilter` 和 `CanaryFilter` 可见性控制。
- 本地和分布式部署都必须支持同一套 memory、skill、usage record 语义。
- 不考虑旧版本升级兼容，直接删除旧 MySQL memory/skill/evolution 代码和 schema。
- 高权限工具默认关闭，自学习流程不能自动开启高权限工具。

---

## File Structure

后端删除：

- `backend/src/main/java/com/example/myagent/memory/**`
- `backend/src/test/java/com/example/myagent/memory/**`
- `backend/src/main/java/com/example/myagent/evolution/**`
- `backend/src/test/java/com/example/myagent/evolution/**`
- `backend/src/main/java/com/example/myagent/skill/SkillEntity.java`
- `backend/src/main/java/com/example/myagent/skill/SkillFileEntity.java`
- `backend/src/main/java/com/example/myagent/skill/UserSkillSettingEntity.java`
- `backend/src/main/java/com/example/myagent/skill/SkillMapper.java`
- `backend/src/main/java/com/example/myagent/skill/SkillFileMapper.java`
- `backend/src/main/java/com/example/myagent/skill/UserSkillSettingMapper.java`
- `backend/src/main/java/com/example/myagent/skill/SkillMaterializer.java`
- MySQL-backed `SkillService` implementation and related tests.

后端新增或重写：

- `backend/src/main/java/com/example/myagent/config/AgentProperties.java`：增加 workspace、memory、AgentScope skill 配置。
- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`：启用 AgentScope memory/skill/gate/filter/filesystem。
- `backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java`：封装 AgentScope `AbstractFilesystem` 对 workspace skill 的读写。
- `backend/src/main/java/com/example/myagent/skill/AgentScopeSkillController.java`：保留 `/api/skills/**` 路径，改为 workspace 数据源。
- `backend/src/main/java/com/example/myagent/skill/SkillPathValidator.java`：校验 skill name 和相对文件路径。
- `backend/src/main/java/com/example/myagent/skill/SkillDto.java`：改为基于 workspace skill 的 DTO。
- `backend/src/main/java/com/example/myagent/skill/SkillFileDto.java`：保留文件 DTO。
- `backend/src/main/java/com/example/myagent/skillreview/SkillReviewController.java`：Web 审核 API。
- `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`：读取 `_drafts` 和 `SkillUsageStore`。
- `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDto.java`：审核列表 DTO。
- `backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java`：实现 `SkillPromotionGate`，对未决审核返回 `Defer`，审批后返回 `Approve` 或 `Reject`。
- `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecisionStore.java`：用 AgentScope filesystem 保存 Web 审核决定。
- `backend/src/main/resources/db/migration/V1__init_schema.sql`：删除旧 skill/evolution 表定义。
- `backend/src/main/resources/db/migration/V2__permission_and_memory.sql`：删除 `user_memories` 表定义，只保留 `session_permission_modes`。

前端删除：

- `frontend/src/api/memory.ts`
- `frontend/src/components/MemoryPanel.vue`
- `frontend/src/api/evolution.ts`
- `frontend/src/stores/evolution.ts`
- `frontend/src/components/EvolutionPanel.vue`

前端新增或重写：

- `frontend/src/api/skillReviews.ts`
- `frontend/src/stores/skillReviews.ts`
- `frontend/src/components/SkillReviewPanel.vue`
- `frontend/src/api/skills.ts`：类型改成 workspace skill。
- `frontend/src/stores/skills.ts`：仍驱动 Skill 面板，但数据来自 AgentScope API。
- `frontend/src/components/SkillPanel.vue`：显示 AgentScope 正式 skill。
- `frontend/src/views/ChatView.vue`：移除 MemoryPanel，替换 EvolutionPanel 为 SkillReviewPanel。

---

### Task 1: AgentScope Runtime 配置与 Workspace 抽象

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentProperties.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatAgentRequest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentPropertiesBindingTest.java`

**Interfaces:**
- Consumes: existing `RedisBaseStore`, `RedisAgentStateStore`, `AgentProperties`.
- Produces:
  - `AgentProperties.Workspace(String path)`
  - `AgentProperties.Memory(boolean enabled)`
  - `AgentProperties.Skill(String storage, String environment, int canaryPercent, boolean manageToolEnabled, boolean securityScanEnabled, String approvalMode)`
  - `AgentScopeConfig.buildBaseStore(...) : BaseStore`
  - `AgentScopeConfig.applyFilesystem(...) : void`

- [ ] **Step 1: Write failing config binding test**

Add assertions in `AgentPropertiesBindingTest`:

```java
@Test
void bindsAgentScopeWorkspaceMemoryAndSkillDefaults() {
  this.contextRunner.run(context -> {
    AgentProperties properties = context.getBean(AgentProperties.class);
    assertThat(properties.workspace().path()).isEqualTo("./.agentscope/workspace");
    assertThat(properties.memory().enabled()).isTrue();
    assertThat(properties.skill().storage()).isEqualTo("agentscope");
    assertThat(properties.skill().environment()).isEqualTo("prod");
    assertThat(properties.skill().canaryPercent()).isEqualTo(10);
    assertThat(properties.skill().manageToolEnabled()).isTrue();
    assertThat(properties.skill().securityScanEnabled()).isTrue();
    assertThat(properties.skill().approvalMode()).isEqualTo("web");
  });
}
```

- [ ] **Step 2: Run binding test and verify failure**

Run:

```powershell
mvn -q -Dtest=AgentPropertiesBindingTest test
```

Expected: FAIL because `workspace()` and `memory()` accessors do not exist and `Skill` still has old fields.

- [ ] **Step 3: Implement config records**

Replace the bottom half of `AgentProperties` with these records:

```java
public record AgentProperties(
    @DefaultValue Deployment deployment,
    @DefaultValue AgentScope agentScope,
    @DefaultValue Workspace workspace,
    @DefaultValue Memory memory,
    @DefaultValue Model model,
    @DefaultValue StateStore stateStore,
    @DefaultValue Skill skill,
    @DefaultValue Permission permission,
    @DefaultValue Tools tools) {

  public record Deployment(@DefaultValue("local") String mode) {}

  public record AgentScope(@DefaultValue("false") boolean enabled) {}

  public record Workspace(@DefaultValue("./.agentscope/workspace") String path) {}

  public record Memory(@DefaultValue("true") boolean enabled) {}

  public record Model(
      @DefaultValue("dashscope") String provider,
      @DefaultValue("dashscope:qwen-plus") String name,
      @DefaultValue("") String baseUrl,
      @DefaultValue("DASHSCOPE_API_KEY") String apiKeyEnv) {}

  public record StateStore(
      @DefaultValue("redis") String type, @DefaultValue Redis redis) {

    public record Redis(
        @DefaultValue("redis://localhost:6379") String uri,
        @DefaultValue("myagent:agent-state:") String keyPrefix) {}
  }

  public record Skill(
      @DefaultValue("agentscope") String storage,
      @DefaultValue("prod") String environment,
      @DefaultValue("10") int canaryPercent,
      @DefaultValue("true") boolean manageToolEnabled,
      @DefaultValue("true") boolean securityScanEnabled,
      @DefaultValue("web") String approvalMode) {}

  public record Permission(@DefaultValue("DEFAULT") String defaultMode) {}

  public record Tools(
      @DefaultValue("false") boolean fileToolsEnabled,
      @DefaultValue("false") boolean shellEnabled,
      @DefaultValue("false") boolean httpFetchEnabled,
      @DefaultValue("false") boolean mcpEnabled) {}
}
```

- [ ] **Step 4: Update tests that instantiate AgentProperties**

Every direct constructor call in tests must include the new `Workspace` and `Memory` records:

```java
new AgentProperties(
    new AgentProperties.Deployment("local"),
    new AgentProperties.AgentScope(true),
    new AgentProperties.Workspace(tempDir.toString()),
    new AgentProperties.Memory(true),
    new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
    new AgentProperties.StateStore("redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
    new AgentProperties.Skill("agentscope", "prod", 10, true, true, "web"),
    new AgentProperties.Permission("DEFAULT"),
    new AgentProperties.Tools(false, false, false, false));
```

- [ ] **Step 5: Add filesystem builder tests**

In `AgentScopeConfigTest`, add:

```java
@Test
void localDeploymentUsesLocalWorkspaceFilesystem() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties properties = properties(false, false, false, false);

  config.applyFilesystem(builder, properties, emptyRedisProvider());

  assertThat(objectField(builder, "localFilesystemSpec")).isNotNull();
  assertThat(objectField(builder, "remoteFilesystemSpec")).isNull();
}

@Test
void distributedDeploymentRequiresRedisBackedRemoteFilesystem() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties distributed =
      new AgentProperties(
          new AgentProperties.Deployment("distributed"),
          new AgentProperties.AgentScope(true),
          new AgentProperties.Workspace(tempDir.toString()),
          new AgentProperties.Memory(true),
          new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
          new AgentProperties.StateStore("redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
          new AgentProperties.Skill("agentscope", "prod", 10, true, true, "web"),
          new AgentProperties.Permission("DEFAULT"),
          new AgentProperties.Tools(false, false, false, false));

  assertThatThrownBy(() -> config.applyFilesystem(builder, distributed, emptyRedisProvider()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Redis");
}
```

Add this helper to `AgentScopeConfigTest`:

```java
private org.springframework.beans.factory.ObjectProvider<ReactiveStringRedisTemplate> emptyRedisProvider() {
  return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
      .getBeanProvider(ReactiveStringRedisTemplate.class);
}
```

- [ ] **Step 6: Implement filesystem wiring**

Add imports:

```java
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
```

Add this method in `AgentScopeConfig`:

```java
void applyFilesystem(
    HarnessAgent.Builder builder,
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  builder.workspace(agentProperties.workspace().path());
  if ("distributed".equalsIgnoreCase(agentProperties.deployment().mode())) {
    ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
    if (redisTemplate == null) {
      throw new IllegalStateException("Redis is required for distributed AgentScope filesystem");
    }
    builder.filesystem(
        new RemoteFilesystemSpec(buildBaseStore(agentProperties, redisTemplateProvider))
            .isolationScope(IsolationScope.USER));
    return;
  }
  builder.filesystem(new LocalFilesystemSpec());
}
```

Call it from `buildHarnessAgent` before `applyStateStore`.

- [ ] **Step 7: Remove materialized skill roots from request model**

Change `ChatAgentRequest` to:

```java
public record ChatAgentRequest(
    Long userId,
    String sessionId,
    String message,
    PermissionMode permissionMode) {

  public static final String PERMISSION_MODE_CONTEXT_KEY = "permissionMode";

  public ChatAgentRequest {
    permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
  }
}
```

- [ ] **Step 8: Run config tests**

Run:

```powershell
mvn -q -Dtest=AgentPropertiesBindingTest,AgentScopeConfigTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/example/myagent/config/AgentProperties.java backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/main/java/com/example/myagent/chat/ChatAgentRequest.java backend/src/test/java/com/example/myagent/config
git commit -m "feat: 配置 AgentScope workspace runtime"
```

### Task 2: 删除自建 Memory 并启用 Harness Memory

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Delete: `backend/src/main/java/com/example/myagent/memory/**`
- Delete: `backend/src/test/java/com/example/myagent/memory/**`
- Modify: `backend/src/main/resources/db/migration/V2__permission_and_memory.sql`
- Modify: `frontend/src/views/ChatView.vue`
- Delete: `frontend/src/components/MemoryPanel.vue`
- Delete: `frontend/src/api/memory.ts`

**Interfaces:**
- Consumes: `AgentProperties.Memory.enabled()`.
- Produces: Harness memory hooks/tools enabled by default.

- [ ] **Step 1: Write failing AgentScope memory builder test**

Add to `AgentScopeConfigTest`:

```java
@Test
void productionHarnessKeepsMemoryHooksAndToolsEnabled() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();

  config.configureHarnessAgentBuilder(builder, config.toolPolicy(properties(false, false, false, false)), properties(false, false, false, false));

  assertThat(booleanField(builder, "disableMemoryTools")).isFalse();
  assertThat(booleanField(builder, "disableMemoryHooks")).isFalse();
  assertThat(objectField(builder, "memoryConfig")).isNotNull();
}
```

Update `configureHarnessAgentBuilder` signature in tests to include `AgentProperties`; this test should fail before implementation.

- [ ] **Step 2: Run memory config test and verify failure**

Run:

```powershell
mvn -q -Dtest=AgentScopeConfigTest#productionHarnessKeepsMemoryHooksAndToolsEnabled test
```

Expected: FAIL because memory is still disabled or `memoryConfig` is null.

- [ ] **Step 3: Enable memory in AgentScopeConfig**

Import:

```java
import io.agentscope.harness.agent.memory.MemoryConfig;
```

Change `configureHarnessAgentBuilder` signature:

```java
HarnessAgent.Builder configureHarnessAgentBuilder(
    HarnessAgent.Builder builder, AgentToolPolicy toolPolicy, AgentProperties agentProperties) {
  applyToolPolicy(builder, toolPolicy);
  if (agentProperties.memory().enabled()) {
    builder.memory(MemoryConfig.defaults());
  } else {
    builder.disableMemoryTools();
    builder.disableMemoryHooks();
  }
  return builder.disableSubagents().disableDynamicSubagents();
}
```

Update `buildHarnessAgent`:

```java
configureHarnessAgentBuilder(builder, toolPolicy(agentProperties), agentProperties);
```

- [ ] **Step 4: Delete backend memory code and tests**

Remove:

```text
backend/src/main/java/com/example/myagent/memory
backend/src/test/java/com/example/myagent/memory
```

Remove `user_memories` table from `V2__permission_and_memory.sql`; leave only `session_permission_modes`.

- [ ] **Step 5: Delete frontend memory panel**

In `frontend/src/views/ChatView.vue`, remove:

```ts
import MemoryPanel from '../components/MemoryPanel.vue'
```

Remove the panel usage:

```vue
<MemoryPanel />
```

Delete `frontend/src/components/MemoryPanel.vue` and `frontend/src/api/memory.ts`.

- [ ] **Step 6: Run backend and frontend checks**

Run:

```powershell
mvn -q -Dtest=AgentScopeConfigTest test
```

Expected: PASS.

Run:

```powershell
npm run build
```

from `frontend`.

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/main/resources/db/migration/V2__permission_and_memory.sql frontend/src/views/ChatView.vue
git add -u backend/src/main/java/com/example/myagent/memory backend/src/test/java/com/example/myagent/memory frontend/src/components/MemoryPanel.vue frontend/src/api/memory.ts
git commit -m "feat: 使用 AgentScope Harness 记忆"
```

### Task 3: 删除 MySQL Skill 体系并实现 Workspace Skill API

**Files:**
- Create: `backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java`
- Create: `backend/src/main/java/com/example/myagent/skill/SkillPathValidator.java`
- Modify: `backend/src/main/java/com/example/myagent/skill/SkillController.java`
- Modify: `backend/src/main/java/com/example/myagent/skill/SkillDto.java`
- Modify: `backend/src/main/java/com/example/myagent/skill/SkillFileDto.java`
- Delete: MySQL skill entities, mappers, service, materializer.
- Modify: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Replace tests under `backend/src/test/java/com/example/myagent/skill`.

**Interfaces:**
- Consumes: `AbstractFilesystem`, `RuntimeContext`, `CurrentUser`.
- Produces:
  - `AgentScopeWorkspaceService.listSkills(CurrentUser): List<SkillDto>`
  - `AgentScopeWorkspaceService.createSkill(CurrentUser, SkillCreateRequest): SkillDto`
  - `AgentScopeWorkspaceService.updateSkill(CurrentUser, String skillName, SkillCreateRequest): SkillDto`
  - `AgentScopeWorkspaceService.deleteSkill(CurrentUser, String skillName): void`
  - `AgentScopeWorkspaceService.listFiles(CurrentUser, String skillName): List<SkillFileDto>`
  - `AgentScopeWorkspaceService.upsertFile(CurrentUser, String skillName, String path, String content): SkillFileDto`
  - `AgentScopeWorkspaceService.deleteFile(CurrentUser, String skillName, String path): void`

- [ ] **Step 1: Write failing path validator tests**

Create `backend/src/test/java/com/example/myagent/skill/SkillPathValidatorTest.java`:

```java
class SkillPathValidatorTest {
  @Test
  void acceptsSafeSkillNamesAndFiles() {
    assertThat(SkillPathValidator.validateSkillName("java-helper")).isEqualTo("java-helper");
    assertThat(SkillPathValidator.validateFilePath("SKILL.md")).isEqualTo("SKILL.md");
    assertThat(SkillPathValidator.validateFilePath("references/checklist.md")).isEqualTo("references/checklist.md");
    assertThat(SkillPathValidator.validateFilePath("scripts/analyze.java")).isEqualTo("scripts/analyze.java");
  }

  @Test
  void rejectsUnsafeSkillNamesAndFiles() {
    assertThatThrownBy(() -> SkillPathValidator.validateSkillName("../secret")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateSkillName("C:\\Users\\a")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("../secret")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("C:\\Users\\a")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Implement SkillPathValidator**

Create:

```java
package com.example.myagent.skill;

import java.util.Set;
import org.springframework.util.StringUtils;

public final class SkillPathValidator {
  private static final Set<String> ALLOWED_ROOTS = Set.of("SKILL.md", "references", "scripts", "assets");

  private SkillPathValidator() {}

  public static String validateSkillName(String name) {
    if (!StringUtils.hasText(name)) {
      throw new IllegalArgumentException("Skill name is required");
    }
    String value = name.trim();
    if (value.contains("/") || value.contains("\\") || value.contains("..") || value.contains(":")) {
      throw new IllegalArgumentException("Invalid skill name");
    }
    return value;
  }

  public static String validateFilePath(String path) {
    if (!StringUtils.hasText(path)) {
      throw new IllegalArgumentException("Skill file path is required");
    }
    String value = path.trim().replace('\\', '/');
    if (value.startsWith("/") || value.contains("../") || value.contains("..") || value.contains(":")) {
      throw new IllegalArgumentException("Invalid skill file path");
    }
    String root = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
    if (!ALLOWED_ROOTS.contains(root)) {
      throw new IllegalArgumentException("Unsupported skill file root");
    }
    return value;
  }
}
```

- [ ] **Step 3: Write failing workspace service tests**

Create `AgentScopeWorkspaceServiceTest` using a fake `AbstractFilesystem` that stores files in a map. Test:

```java
@Test
void createSkillWritesSkillMarkdownToWorkspace() {
  service.createSkill(USER, new SkillCreateRequest("java-helper", "Java helper"));

  assertThat(files.get("skills/java-helper/SKILL.md"))
      .contains("name: java-helper")
      .contains("description: Java helper");
}

@Test
void listSkillsReadsWorkspaceSkillMarkdown() {
  files.put("skills/java-helper/SKILL.md", "---\nname: java-helper\ndescription: Java helper\n---\n");

  assertThat(service.listSkills(USER))
      .extracting(SkillDto::name)
      .containsExactly("java-helper");
}
```

- [ ] **Step 4: Implement AgentScopeWorkspaceService**

Key implementation details:

```java
private RuntimeContext runtimeContext(CurrentUser user) {
  return RuntimeContext.builder().userId(user.id().toString()).sessionId("workspace-api").build();
}

private String skillRoot(String skillName) {
  return "skills/" + SkillPathValidator.validateSkillName(skillName);
}

private void requireSuccess(WriteResult result) {
  if (!result.isSuccess()) {
    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, result.error());
  }
}
```

Use:

```java
filesystem.ls(runtimeContext(user), "skills")
filesystem.read(runtimeContext(user), "skills/" + skillName + "/SKILL.md", 0, 200_000)
filesystem.write(runtimeContext(user), "skills/" + skillName + "/SKILL.md", skillMarkdown)
filesystem.write(runtimeContext(user), "skills/" + skillName + "/" + filePath, content)
filesystem.delete(runtimeContext(user), "skills/" + skillName)
```

- [ ] **Step 5: Rewrite SkillController to delegate to AgentScopeWorkspaceService**

Keep route names where possible:

```java
@GetMapping("/mine")
public Mono<List<SkillDto>> listMine(@AuthenticationPrincipal CurrentUser currentUser) {
  return Mono.fromCallable(() -> workspaceService.listSkills(currentUser))
      .subscribeOn(Schedulers.boundedElastic());
}
```

Use `String skillName` path variables instead of `Long skillId`.

- [ ] **Step 6: Delete MySQL skill classes and schema**

Delete MySQL-backed files listed in File Structure. Remove these table blocks from `V1__init_schema.sql`:

```text
skills
skill_files
user_skill_settings
```

- [ ] **Step 7: Run skill tests**

Run:

```powershell
mvn -q -Dtest=SkillPathValidatorTest,AgentScopeWorkspaceServiceTest,SkillControllerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/example/myagent/skill backend/src/test/java/com/example/myagent/skill backend/src/main/resources/db/migration/V1__init_schema.sql
git add -u backend/src/main/java/com/example/myagent/skill backend/src/test/java/com/example/myagent/skill
git commit -m "feat: 使用 AgentScope workspace 管理 skill"
```

### Task 4: Web 审核 Gate 与 SkillUsageStore 接入

**Files:**
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecision.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecisionStore.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDto.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewController.java`
- Create tests under `backend/src/test/java/com/example/myagent/skillreview`.

**Interfaces:**
- Consumes: `SkillUsageStore`, `AbstractFilesystem`, `SkillPromotionGate`.
- Produces:
  - `WebApprovalGate.review(SkillCandidate, RuntimeContext): Mono<PromotionDecision>`
  - `SkillReviewService.list(CurrentUser): List<SkillReviewDto>`
  - `SkillReviewService.approve(CurrentUser, String skillName, List<String> environments): SkillReviewDto`
  - `SkillReviewService.reject(CurrentUser, String skillName, String reason): SkillReviewDto`

- [ ] **Step 1: Write WebApprovalGate tests**

Create:

```java
@Test
void reviewDefersWhenNoDecisionExists() {
  WebApprovalGate gate = new WebApprovalGate(decisionStore, Duration.ofSeconds(30));

  PromotionDecision decision = gate.review(candidate("java-helper"), runtimeContext("7")).block();

  assertThat(decision).isInstanceOf(PromotionDecision.Defer.class);
}

@Test
void reviewApprovesWhenDecisionIsApproved() {
  decisionStore.approve("java-helper", "7", List.of("prod"));

  PromotionDecision decision = gate.review(candidate("java-helper"), runtimeContext("7")).block();

  assertThat(decision).isEqualTo(new PromotionDecision.Approve("7", List.of("prod"), decisionStore.decidedAt("java-helper")));
}
```

- [ ] **Step 2: Implement decision store**

Persist decisions through AgentScope filesystem:

```text
skill-reviews/<skillName>.json
```

`SkillReviewDecision` fields:

```java
public record SkillReviewDecision(
    String skillName,
    String status,
    String reviewerId,
    String reason,
    List<String> environments,
    Instant decidedAt) {}
```

Supported statuses: `APPROVED`, `REJECTED`.

- [ ] **Step 3: Implement WebApprovalGate**

Use exact decisions:

```java
if (decision == null) {
  return Mono.just(new PromotionDecision.Defer(retryAfter, "Waiting for Web approval"));
}
if ("APPROVED".equals(decision.status())) {
  return Mono.just(new PromotionDecision.Approve(decision.reviewerId(), decision.environments(), decision.decidedAt()));
}
return Mono.just(new PromotionDecision.Reject(decision.reason(), decision.reviewerId()));
```

- [ ] **Step 4: Write SkillReviewService tests**

Test list shape:

```java
@Test
void listReturnsAgentCreatedDraftsFromUsageStore() {
  usageStore.markAgentDraft("java-helper", "s_1");

  assertThat(service.list(USER))
      .extracting(SkillReviewDto::skillName)
      .containsExactly("java-helper");
}
```

Test approval:

```java
@Test
void approveStoresDecisionForCurrentUser() {
  SkillReviewDto dto = service.approve(USER, "java-helper", List.of("prod"));

  assertThat(dto.status()).isEqualTo("APPROVED");
  assertThat(decisionStore.find("java-helper", USER.id().toString())).isPresent();
}
```

- [ ] **Step 5: Implement SkillReviewService and Controller**

Routes:

```java
@GetMapping
public Mono<List<SkillReviewDto>> list(@AuthenticationPrincipal CurrentUser user)

@PostMapping("/{skillName}/approve")
public Mono<SkillReviewDto> approve(@AuthenticationPrincipal CurrentUser user, @PathVariable String skillName, @RequestBody ApproveSkillReviewRequest request)

@PostMapping("/{skillName}/reject")
public Mono<SkillReviewDto> reject(@AuthenticationPrincipal CurrentUser user, @PathVariable String skillName, @RequestBody RejectSkillReviewRequest request)
```

DTO:

```java
public record SkillReviewDto(
    String skillName,
    String description,
    String status,
    String createdBy,
    String sourceSessionId,
    List<String> environments,
    long useCount,
    long viewCount,
    long patchCount) {}
```

- [ ] **Step 6: Run review tests**

Run:

```powershell
mvn -q -Dtest=WebApprovalGateTest,SkillReviewServiceTest,SkillReviewControllerTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/skillreview backend/src/test/java/com/example/myagent/skillreview
git commit -m "feat: 添加 AgentScope skill Web 审核"
```

### Task 5: Harness Skill Manage、Promotion Gate、Visibility Filter 接线

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`

**Interfaces:**
- Consumes: `WebApprovalGate`, `SkillUsageStore`, `SkillReviewDecisionStore`, `AgentScopeWorkspaceService`.
- Produces: Harness builder with `enableSkillManageTool`, `enableSkillPromotionGate`, `environment`.

- [ ] **Step 1: Write failing builder test**

Add:

```java
@Test
void productionHarnessEnablesSkillManageGateAndVisibility() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();

  config.applySkillLearning(builder, properties(false, false, false, false), skillUsageStore, webApprovalGate);

  assertThat(booleanField(builder, "skillManageToolEnabled")).isTrue();
  assertThat(objectField(builder, "promotionGate")).isSameAs(webApprovalGate);
  assertThat(objectField(builder, "visibilityFilter")).isNotNull();
  assertThat(objectField(builder, "environment")).isEqualTo("prod");
}
```

- [ ] **Step 2: Implement SkillUsageStore bean**

In `AgentScopeConfig`:

```java
@Bean
@ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
SkillUsageStore skillUsageStore(AbstractFilesystem agentScopeFilesystem) {
  return new SkillUsageStore(agentScopeFilesystem);
}
```

If `AbstractFilesystem` is not already a bean after Task 1, add:

```java
@Bean
@ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
AbstractFilesystem agentScopeFilesystem(AgentProperties properties, ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  Path workspace = Path.of(properties.workspace().path());
  if ("distributed".equalsIgnoreCase(properties.deployment().mode())) {
    return new RemoteFilesystemSpec(buildBaseStore(properties, redisTemplateProvider))
        .isolationScope(IsolationScope.USER)
        .toFilesystem(workspace, "myagent", IsolationScope.USER.toNamespaceFactory());
  }
  return new LocalFilesystemSpec()
      .isolationScope(IsolationScope.USER)
      .toFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
}
```

- [ ] **Step 3: Implement promotion mode selection**

Add:

```java
SkillPromotionGate promotionGate(AgentProperties properties, WebApprovalGate webApprovalGate) {
  return switch (properties.skill().approvalMode()) {
    case "web" -> webApprovalGate;
    case "reject" -> new RejectAllGate();
    case "local" -> {
      if ("prod".equalsIgnoreCase(properties.skill().environment())
          || "distributed".equalsIgnoreCase(properties.deployment().mode())) {
        throw new IllegalStateException("local skill approval is not allowed in production or distributed mode");
      }
      yield new LocalApprovalGate();
    }
    default -> throw new IllegalArgumentException("Unsupported agent.skill.approval-mode: " + properties.skill().approvalMode());
  };
}
```

- [ ] **Step 4: Implement visibility filter and skill manage config**

Add:

```java
void applySkillLearning(
    HarnessAgent.Builder builder,
    AgentProperties properties,
    SkillUsageStore skillUsageStore,
    SkillPromotionGate promotionGate) {
  if (!properties.skill().manageToolEnabled()) {
    builder.disableDynamicSkills();
    return;
  }
  SkillManageConfig manageConfig =
      SkillManageConfig.builder()
          .autoPromote(false)
          .securityScan(properties.skill().securityScanEnabled())
          .build();
  SkillVisibilityFilter visibilityFilter =
      new CompositeFilter(
          List.of(
              new EnvironmentFilter(properties.skill().environment(), skillUsageStore),
              new CanaryFilter(properties.skill().canaryPercent(), skillUsageStore)));
  builder.enableSkillManageTool(manageConfig);
  builder.enableSkillPromotionGate(promotionGate, visibilityFilter);
  builder.environment(properties.skill().environment());
}
```

- [ ] **Step 5: Remove SkillMaterializer from ChatService**

Constructor should no longer inject `SkillMaterializer`. Build request:

```java
return new ChatAgentRequest(
    currentUser.id(),
    sessionId,
    message,
    permissionService.getModeForOwnedSession(sessionId));
```

- [ ] **Step 6: Run config and chat tests**

Run:

```powershell
mvn -q -Dtest=AgentScopeConfigTest,ChatServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/main/java/com/example/myagent/chat/ChatService.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java
git commit -m "feat: 接入 AgentScope skill 自学习闭环"
```

### Task 6: 删除自建 Evolution Proposal 体系并清理 Schema

**Files:**
- Delete: `backend/src/main/java/com/example/myagent/evolution/**`
- Delete: `backend/src/test/java/com/example/myagent/evolution/**`
- Modify: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Modify: `frontend/src/api/evolution.ts`
- Modify: `frontend/src/stores/evolution.ts`
- Delete: `frontend/src/components/EvolutionPanel.vue`

**Interfaces:**
- Consumes: Skill review API from Task 4.
- Produces: no `/api/evolution/proposals/**` backend routes.

- [ ] **Step 1: Remove backend evolution package**

Delete:

```text
backend/src/main/java/com/example/myagent/evolution
backend/src/test/java/com/example/myagent/evolution
```

- [ ] **Step 2: Remove schema table**

Remove the `agent_evolution_proposals` table from `V1__init_schema.sql`.

- [ ] **Step 3: Verify route removal with test**

Add or update a security/controller smoke test:

```java
@Test
void oldEvolutionRouteDoesNotExist() {
  webTestClient.get()
      .uri("/api/evolution/proposals")
      .headers(headers -> headers.setBearerAuth(tokenFor(USER)))
      .exchange()
      .expectStatus()
      .isNotFound();
}
```

- [ ] **Step 4: Remove frontend evolution module**

Delete:

```text
frontend/src/api/evolution.ts
frontend/src/stores/evolution.ts
frontend/src/components/EvolutionPanel.vue
```

Do not leave imports in `ChatView.vue`.

- [ ] **Step 5: Run backend tests**

Run:

```powershell
mvn -q test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V1__init_schema.sql
git add -u backend/src/main/java/com/example/myagent/evolution backend/src/test/java/com/example/myagent/evolution frontend/src/api/evolution.ts frontend/src/stores/evolution.ts frontend/src/components/EvolutionPanel.vue
git commit -m "refactor: 删除自建进化提案体系"
```

### Task 7: Frontend Skill 与 Skill Review 面板切换

**Files:**
- Modify: `frontend/src/api/skills.ts`
- Modify: `frontend/src/stores/skills.ts`
- Modify: `frontend/src/components/SkillPanel.vue`
- Create: `frontend/src/api/skillReviews.ts`
- Create: `frontend/src/stores/skillReviews.ts`
- Create: `frontend/src/components/SkillReviewPanel.vue`
- Modify: `frontend/src/views/ChatView.vue`

**Interfaces:**
- Consumes:
  - `GET /api/skills/mine`
  - `POST /api/skills/mine`
  - `PUT /api/skills/mine/{skillName}`
  - `GET /api/skill-reviews`
  - `POST /api/skill-reviews/{skillName}/approve`
  - `POST /api/skill-reviews/{skillName}/reject`
- Produces: Vue UI that shows AgentScope skills and review queue.

- [ ] **Step 1: Update skills API types**

`frontend/src/api/skills.ts` should define:

```ts
export interface Skill {
  name: string
  description: string
  enabled: boolean
  ownerType: 'USER' | 'SYSTEM' | 'AGENT'
}

export interface SkillFile {
  path: string
  content: string
  updatedAt?: string
}
```

Path functions use `skill.name`, not numeric id.

- [ ] **Step 2: Update skills store**

Replace `filesBySkillId` with:

```ts
filesBySkillName: Record<string, SkillFile[]>
```

All actions should accept `skillName: string`.

- [ ] **Step 3: Update SkillPanel**

Replace numeric selected id state:

```ts
const selectedSkillName = ref('')
const selectedSkill = computed(() => skills.mySkills.find(skill => skill.name === selectedSkillName.value) || null)
```

The panel title should read `Skill` and `自学习审核` should be separate in the review panel, not mixed into manual skill editing.

- [ ] **Step 4: Add skill review API**

Create `frontend/src/api/skillReviews.ts`:

```ts
import { apiGet, apiPost } from './client'

export interface SkillReview {
  skillName: string
  description: string
  status: string
  createdBy: string
  sourceSessionId?: string
  environments: string[]
  useCount: number
  viewCount: number
  patchCount: number
}

export function listSkillReviews() {
  return apiGet<SkillReview[]>('/api/skill-reviews')
}

export function approveSkillReview(skillName: string, environments: string[]) {
  return apiPost<SkillReview>(`/api/skill-reviews/${encodeURIComponent(skillName)}/approve`, { environments })
}

export function rejectSkillReview(skillName: string, reason: string) {
  return apiPost<SkillReview>(`/api/skill-reviews/${encodeURIComponent(skillName)}/reject`, { reason })
}
```

- [ ] **Step 5: Add SkillReviewPanel**

Create panel that loads `useSkillReviewsStore()` on mount and renders:

```vue
<strong>自学习 Skill 审核</strong>
<p>Agent 创建的草稿必须人工审核后才能晋升为正式 Skill。</p>
```

Buttons:

```vue
<el-button size="small" type="primary" @click="reviews.approve(item.skillName, ['prod'])">批准</el-button>
<el-button size="small" type="danger" @click="reviews.reject(item.skillName, 'Rejected from Web review')">拒绝</el-button>
```

- [ ] **Step 6: Wire ChatView**

Remove `MemoryPanel` and `EvolutionPanel` imports. Add:

```ts
import SkillReviewPanel from '../components/SkillReviewPanel.vue'
```

Render near `SkillPanel`:

```vue
<SkillPanel />
<SkillReviewPanel />
```

- [ ] **Step 7: Run frontend build**

Run:

```powershell
npm run build
```

from `frontend`.

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/skills.ts frontend/src/stores/skills.ts frontend/src/components/SkillPanel.vue frontend/src/api/skillReviews.ts frontend/src/stores/skillReviews.ts frontend/src/components/SkillReviewPanel.vue frontend/src/views/ChatView.vue
git commit -m "feat: 切换前端 Skill 和审核面板"
```

### Task 8: 全量验证与 README 更新

**Files:**
- Modify: `README.md`
- Modify: `.env.example`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-docker.yml`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: documented local/distributed runtime settings.

- [ ] **Step 1: Update application config**

Set:

```yaml
agent:
  workspace:
    path: ./.agentscope/workspace
  memory:
    enabled: true
  skill:
    storage: agentscope
    environment: ${AGENT_SKILL_ENVIRONMENT:prod}
    canary-percent: ${AGENT_SKILL_CANARY_PERCENT:10}
    manage-tool-enabled: ${AGENT_SKILL_MANAGE_TOOL_ENABLED:true}
    security-scan-enabled: ${AGENT_SKILL_SECURITY_SCAN_ENABLED:true}
    approval-mode: ${AGENT_SKILL_APPROVAL_MODE:web}
```

In docker profile, keep `deployment.mode=distributed`.

- [ ] **Step 2: Update .env.example**

Add:

```text
AGENT_SKILL_ENVIRONMENT=prod
AGENT_SKILL_CANARY_PERCENT=10
AGENT_SKILL_MANAGE_TOOL_ENABLED=true
AGENT_SKILL_SECURITY_SCAN_ENABLED=true
AGENT_SKILL_APPROVAL_MODE=web
```

- [ ] **Step 3: Update README**

Document:

```text
Memory is maintained by AgentScope Harness in MEMORY.md and memory/YYYY-MM-DD.md.
Skills are stored in AgentScope workspace/filesystem, not MySQL.
Agent-created skills are drafted under skills/_drafts and require Web approval through SkillPromotionGate.
Distributed mode uses Redis-backed remote filesystem with IsolationScope.USER.
```

- [ ] **Step 4: Run backend full tests**

Run:

```powershell
mvn -q test
```

Expected: PASS.

- [ ] **Step 5: Run frontend build**

Run:

```powershell
npm run build
```

Expected: PASS.

- [ ] **Step 6: Run Docker config check**

Run:

```powershell
docker compose config
```

Expected: PASS and generated config includes `AGENT_SKILL_APPROVAL_MODE`.

- [ ] **Step 7: Final status check**

Run:

```powershell
git status --short
```

Expected: only intentional README/config changes before commit.

- [ ] **Step 8: Commit**

```bash
git add README.md .env.example backend/src/main/resources/application.yml backend/src/main/resources/application-docker.yml
git commit -m "docs: 说明 AgentScope 原生记忆和 skill 配置"
```

## Self-Review

Spec coverage:

- 记忆切换由 Task 2 和 Task 8 覆盖。
- MySQL skill 删除与 AgentScope workspace skill API 由 Task 3 覆盖。
- 自建 evolution proposal 删除由 Task 6 覆盖。
- Web 人工审核、`SkillPromotionGate`、`SkillUsageStore` 由 Task 4 和 Task 5 覆盖。
- `EnvironmentFilter` 与 `CanaryFilter` 由 Task 5 覆盖。
- 本地和分布式 deployment 由 Task 1、Task 5、Task 8 覆盖。
- 前端 Skill 面板和审核面板由 Task 7 覆盖。
- 配置与文档由 Task 8 覆盖。

Placeholder scan:

- No placeholder markers or vague validation instructions remain.

Type consistency:

- Backend review uses `skillName: String`.
- Frontend review uses `skillName: string`.
- `ChatAgentRequest` no longer carries materialized skill roots.
- Skill API no longer uses numeric `skillId`.
