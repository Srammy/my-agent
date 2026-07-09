# 删除本地部署模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除所有本地部署回退逻辑，Agent 只走分布式（Redis + RemoteFilesystem）路径。

**Architecture:** 修改 `AgentScopeConfig` 去掉所有 `isDistributed()` 条件分支，固定使用 `RemoteFilesystem`、`RedisAgentStateStore`、`RedisBaseStore`；同步删除 `AgentProperties.Deployment` record；更新测试和配置文件。

**Tech Stack:** Java 21、Spring Boot 3 WebFlux、AgentScope Java Harness 2.0.0-RC4、Maven。

## Global Constraints

- 不修改业务逻辑（chat、skill、skillreview、permission）
- 不引入新的部署模式
- 不升级 AgentScope 版本
- 全量 `mvn test` 必须 PASS

---

## File Structure

修改文件：
- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- `backend/src/main/java/com/example/myagent/config/AgentProperties.java`
- `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- `backend/src/test/java/com/example/myagent/config/AgentPropertiesBindingTest.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-docker.yml`

---

### Task 1: 删除本地部署模式

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentProperties.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentPropertiesBindingTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-docker.yml`

**Interfaces:**
- Consumes: `RedisBaseStore`, `RedisAgentStateStore`, `ReactiveStringRedisTemplate`
- Produces: `AgentScopeConfig` 无条件分支；`AgentProperties` 无 `Deployment` record

- [ ] **Step 1: 更新 AgentProperties.java — 删除 Deployment record**

将 `AgentProperties.java` 第一个参数 `@DefaultValue Deployment deployment` 和 `Deployment` record 完整删除，结果如下：

```java
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
    @DefaultValue AgentScope agentScope,
    @DefaultValue Workspace workspace,
    @DefaultValue Memory memory,
    @DefaultValue Model model,
    @DefaultValue StateStore stateStore,
    @DefaultValue Skill skill,
    @DefaultValue Permission permission,
    @DefaultValue Tools tools) {

  public record AgentScope(@DefaultValue("false") boolean enabled) {}
  public record Workspace(@DefaultValue("./.agentscope/workspace") String path) {}
  public record Memory(@DefaultValue("true") boolean enabled) {}
  public record Model(
      @DefaultValue("dashscope") String provider,
      @DefaultValue("dashscope:qwen-plus") String name,
      @DefaultValue("") String baseUrl,
      @DefaultValue("DASHSCOPE_API_KEY") String apiKeyEnv) {}
  public record StateStore(@DefaultValue("redis") String type, @DefaultValue Redis redis) {
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

- [ ] **Step 2: 更新 AgentScopeConfig.java — 固定分布式路径**

**2a) `workspaceFilesystem` bean — 删除 LocalFilesystem 分支：**
```java
@Bean
AbstractFilesystem workspaceFilesystem(
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  return new RemoteFilesystem(buildBaseStore(agentProperties, redisTemplateProvider));
}
```

**2b) `applyFilesystem` — 删除 LocalFilesystemSpec 分支：**
```java
void applyFilesystem(
    HarnessAgent.Builder builder,
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  builder.workspace(agentProperties.workspace().path());
  builder.filesystem(
      new RemoteFilesystemSpec(buildBaseStore(agentProperties, redisTemplateProvider))
          .isolationScope(IsolationScope.USER));
}
```

**2c) `applyStateStore` — 删除 if(isDistributed) 条件，直接调用 distributedStore：**
```java
void applyStateStore(
    HarnessAgent.Builder builder,
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  AgentStateStore stateStore = buildAgentStateStore(agentProperties, redisTemplateProvider);
  builder.stateStore(stateStore);
  builder.distributedStore(
      DistributedStore.builder()
          .agentStateStore(stateStore)
          .baseStore(buildBaseStore(agentProperties, redisTemplateProvider))
          .build());
}
```

**2d) `buildAgentStateStore` — 删除 JsonFileAgentStateStore 回退：**
```java
AgentStateStore buildAgentStateStore(
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
  if (redisTemplate == null || !"redis".equalsIgnoreCase(agentProperties.stateStore().type())) {
    throw new IllegalStateException(
        "Distributed deployment requires agent.state-store.type=redis and a Redis bean");
  }
  return new RedisAgentStateStore(redisTemplate, agentProperties.stateStore().redis().keyPrefix());
}
```

**2e) `buildBaseStore` — 删除 InMemoryStore 回退：**
```java
BaseStore buildBaseStore(
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
  if (redisTemplate == null || !"redis".equalsIgnoreCase(agentProperties.stateStore().type())) {
    throw new IllegalStateException(
        "Distributed deployment requires agent.state-store.type=redis and a Redis bean");
  }
  return new RedisBaseStore(
      redisTemplate, agentProperties.stateStore().redis().keyPrefix() + "base:");
}
```

**2f) 删除 `isDistributed()` 方法（整个方法）：**
```java
// 删除:
private boolean isDistributed(AgentProperties agentProperties) {
  return "distributed".equalsIgnoreCase(agentProperties.deployment().mode());
}
```

**2g) 删除以下 import 行（如存在）：**
- `import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;`
- `import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;`
- `import io.agentscope.core.state.JsonFileAgentStateStore;`
- `import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;`

- [ ] **Step 3: 验证编译**

```powershell
cd D:\ideaccproj\myagent\.worktrees\agent-assistant-impl\backend
mvn compile -q 2>&1
```

期望：BUILD SUCCESS。如有编译错误说明还有 `deployment()` 调用未处理，搜索并修复：
```powershell
Select-String -Path "src\main\java\**\*.java" -Pattern "deployment()" -Recurse
```

- [ ] **Step 4: 更新 AgentScopeConfigTest.java**

**4a) 删除 `import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;`**

**4b) 删除整个 `localDeploymentUsesLocalWorkspaceFilesystem` 测试方法。**

**4c) 将 `distributedDeploymentRequiresRedisBackedRemoteFilesystem` 改名并更新：**
```java
@Test
void deploymentRequiresRedisBackedRemoteFilesystem() {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties props =
      new AgentProperties(
          new AgentProperties.AgentScope(true),
          new AgentProperties.Workspace(tempDir.toString()),
          new AgentProperties.Memory(true),
          new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
          new AgentProperties.StateStore(
              "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
          new AgentProperties.Skill("agentscope", "prod", 10, true, true, "web"),
          new AgentProperties.Permission("DEFAULT"),
          new AgentProperties.Tools(false, false, false, false));

  assertThatThrownBy(() -> config.applyFilesystem(builder, props, emptyRedisProvider()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Redis");
}
```

**4d) 将 `distributedDeploymentRejectsNonRedisStateStore` 改名并更新：**
```java
@Test
void deploymentRejectsNonRedisStateStore() {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties props =
      new AgentProperties(
          new AgentProperties.AgentScope(true),
          new AgentProperties.Workspace(tempDir.toString()),
          new AgentProperties.Memory(true),
          new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
          new AgentProperties.StateStore(
              "file", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
          new AgentProperties.Skill("agentscope", "prod", 10, true, true, "web"),
          new AgentProperties.Permission("DEFAULT"),
          new AgentProperties.Tools(false, false, false, false));

  assertThatThrownBy(() -> config.applyFilesystem(builder, props, emptyRedisProvider()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("agent.state-store.type=redis");
}
```

**4e) 更新 `applySkillLearning_enablesSkillManageTool_whenConfigured`（将 `LocalFilesystem` 改为 `RemoteFilesystem + InMemoryStore`）：**
```java
@Test
void applySkillLearning_enablesSkillManageTool_whenConfigured() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties props = properties(false, false, false, false);
  io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
      new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
  io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem fs =
      new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store);
  SkillUsageStore usageStore = new SkillUsageStore(fs);
  SkillReviewDecisionStore decisionStore = new SkillReviewDecisionStore(fs);
  WebApprovalGate webApprovalGate = new WebApprovalGate(decisionStore);

  assertThatNoException().isThrownBy(() ->
      config.applySkillLearning(builder, props, usageStore, webApprovalGate));

  assertThat(booleanField(builder, "skillManageToolEnabled")).isTrue();
  assertThat(booleanField(builder, "skillCuratorEnabled")).isTrue();
}
```

**4f) 更新 `properties()` helper — 删除 `Deployment` 参数：**
```java
private AgentProperties properties(
    boolean fileToolsEnabled, boolean shellEnabled,
    boolean httpFetchEnabled, boolean mcpEnabled) {
  return new AgentProperties(
      new AgentProperties.AgentScope(true),
      new AgentProperties.Workspace(tempDir.toString()),
      new AgentProperties.Memory(true),
      new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
      new AgentProperties.StateStore(
          "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
      new AgentProperties.Skill("agentscope", "prod", 10, true, true, "web"),
      new AgentProperties.Permission("DEFAULT"),
      new AgentProperties.Tools(fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled));
}
```

**4g) 更新所有直接构造 `AgentProperties` 的测试**（`createsDashScopeModelByDefault`、`createsOpenAiCompatibleModelWhenConfigured`、`rejectsMissingApiKey`、`rejectsApiKeyProvidedOnlyAsSpringProperty`、`distributedDeployment*` 两个）— 删除第一个 `new AgentProperties.Deployment(...)` 参数。

- [ ] **Step 5: 更新 AgentPropertiesBindingTest.java**

在 `appliesDefaultFallbackValues` 测试中，删除这一行：
```java
assertThat(agentProperties.deployment().mode()).isEqualTo("local");
```

- [ ] **Step 6: 更新 application.yml**

删除 `agent:` 块内的 `deployment:` 子块（约第 23-24 行）：
```yaml
# 删除这两行:
  deployment:
    mode: local
```

- [ ] **Step 7: 更新 application-docker.yml**

删除 `agent:` 块内的 `deployment:` 子块（约第 17-18 行）：
```yaml
# 删除这两行:
  deployment:
    mode: distributed
```

- [ ] **Step 8: 全量测试**

```powershell
cd D:\ideaccproj\myagent\.worktrees\agent-assistant-impl\backend
mvn -q test 2>&1
```

期望：BUILD SUCCESS，0 failures，0 errors。如果失败，根据错误修复后重新运行。

- [ ] **Step 9: Commit**

```powershell
cd D:\ideaccproj\myagent\.worktrees\agent-assistant-impl
git add backend/src/main/java/com/example/myagent/config/AgentProperties.java
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java
git add backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git add backend/src/test/java/com/example/myagent/config/AgentPropertiesBindingTest.java
git add backend/src/main/resources/application.yml
git add backend/src/main/resources/application-docker.yml
git commit -m "refactor: 删除本地部署模式，仅保留分布式路径"
```
