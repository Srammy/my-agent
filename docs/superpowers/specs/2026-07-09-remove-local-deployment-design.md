# 删除本地部署模式设计

日期：2026-07-09

## 目标

Agent 只支持分布式部署（Redis + RemoteFilesystem）。删除所有本地部署的回退逻辑，让代码路径唯一、行为确定。

## 非目标

- 不修改业务逻辑（chat、skill、skillreview、permission 等）
- 不引入新的部署模式
- 不升级 AgentScope 版本

## 背景

当前 `AgentScopeConfig` 有 `isDistributed()` 条件分支，在本地模式下使用 `LocalFilesystem`、`JsonFileAgentStateStore`、`InMemoryStore`；在分布式模式下使用 `RemoteFilesystem`、`RedisAgentStateStore`、`RedisBaseStore`。由于 Agent 只需要支持分布式部署，本地路径成为死代码，应直接删除。

## 改动方案

### `AgentScopeConfig.java`

删除 `isDistributed()` 方法及所有条件分支，固定使用分布式路径：

**`workspaceFilesystem` bean**
```java
// 删前
if (isDistributed(agentProperties)) {
    return new RemoteFilesystem(buildBaseStore(agentProperties, redisTemplateProvider));
}
return new LocalFilesystem(Path.of(agentProperties.workspace().path()));

// 删后
return new RemoteFilesystem(buildBaseStore(agentProperties, redisTemplateProvider));
```

**`applyFilesystem`**
```java
// 删前
if (isDistributed(agentProperties)) {
    builder.filesystem(new RemoteFilesystemSpec(...).isolationScope(IsolationScope.USER));
    return;
}
builder.filesystem(new LocalFilesystemSpec());

// 删后
builder.filesystem(new RemoteFilesystemSpec(buildBaseStore(agentProperties, redisTemplateProvider))
    .isolationScope(IsolationScope.USER));
```

**`buildAgentStateStore`**
```java
// 删前：无 Redis 时 fallback 到 JsonFileAgentStateStore
// 删后：无 Redis 直接抛 IllegalStateException
ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
if (redisTemplate == null || !"redis".equalsIgnoreCase(agentProperties.stateStore().type())) {
    throw new IllegalStateException(
        "Distributed deployment requires agent.state-store.type=redis and a Redis bean");
}
return new RedisAgentStateStore(redisTemplate, agentProperties.stateStore().redis().keyPrefix());
```

**`buildBaseStore`**
```java
// 删前：无 Redis 时 fallback 到 InMemoryStore
// 删后：无 Redis 直接抛 IllegalStateException
ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
if (redisTemplate == null || !"redis".equalsIgnoreCase(agentProperties.stateStore().type())) {
    throw new IllegalStateException(
        "Distributed deployment requires agent.state-store.type=redis and a Redis bean");
}
return new RedisBaseStore(redisTemplate, agentProperties.stateStore().redis().keyPrefix() + "base:");
```

删除 imports：
- `io.agentscope.harness.agent.filesystem.local.LocalFilesystem`
- `io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec`
- `io.agentscope.core.state.JsonFileAgentStateStore`（如有）
- `io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore`（如有）

### `AgentProperties.java`

删除 `Deployment` record 和构造器中的 `Deployment deployment` 参数：

```java
// 删前
public record AgentProperties(
    @DefaultValue Deployment deployment,
    ...
)
public record Deployment(@DefaultValue("local") String mode) {}

// 删后：整个 Deployment record 和 deployment 字段删除
public record AgentProperties(
    @DefaultValue AgentScope agentScope,
    @DefaultValue Workspace workspace,
    ...
)
```

### 测试文件

**`AgentScopeConfigTest.java`**

- 删除 `localDeploymentUsesLocalWorkspaceFilesystem` 测试
- `distributedDeploymentRequiresRedisBackedRemoteFilesystem` 和 `distributedDeploymentRejectsNonRedisStateStore`：去掉 `new AgentProperties.Deployment("distributed")` 参数，改为直接调用无 Deployment 的构造器，验证无 Redis 时抛 `IllegalStateException`
- `applySkillLearning_enablesSkillManageTool_whenConfigured`：将 `new LocalFilesystem(tempDir)` 改为 `new RemoteFilesystem(new InMemoryStore())` 用于单元测试（`InMemoryStore` 仍可在测试中使用，只是不在生产路径中）
- `properties()` helper 方法：删除 `new AgentProperties.Deployment("local")` 参数
- 所有直接构造 `AgentProperties` 的地方同步删除 `Deployment` 参数

**`AgentPropertiesBindingTest.java`**

- `appliesDefaultFallbackValues`：删除 `assertThat(agentProperties.deployment().mode()).isEqualTo("local")` 一行

### 配置文件

**`application.yml`**
```yaml
# 删除
agent:
  deployment:
    mode: local
```

**`application-docker.yml`**
```yaml
# 删除
agent:
  deployment:
    mode: distributed
```

## 错误处理

无 Redis 时，`buildAgentStateStore` 和 `buildBaseStore` 抛 `IllegalStateException`，消息均含 `"agent.state-store.type=redis"` 以便排查。Spring Boot 应用启动失败，日志清晰指向 Redis 未配置。

## 测试策略

- 删除本地路径的测试（`localDeploymentUsesLocalWorkspaceFilesystem`）
- 保留并更新分布式路径的测试，验证无 Redis 时启动失败
- 全量 `mvn test` 必须 PASS

## 验收标准

- `AgentScopeConfig` 中不再有 `isDistributed()` 或 `LocalFilesystem`/`LocalFilesystemSpec`/`JsonFileAgentStateStore`/`InMemoryStore` 的生产路径引用
- `AgentProperties` 不再有 `Deployment` record
- `application.yml` 和 `application-docker.yml` 不再有 `agent.deployment.mode`
- 全量后端测试 PASS
