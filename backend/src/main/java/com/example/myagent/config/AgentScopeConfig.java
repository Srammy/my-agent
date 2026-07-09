package com.example.myagent.config;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.skillreview.WebApprovalGate;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.skill.curator.CanaryFilter;
import io.agentscope.harness.agent.skill.curator.CompositeFilter;
import io.agentscope.harness.agent.skill.curator.EnvironmentFilter;
import io.agentscope.harness.agent.skill.curator.LocalApprovalGate;
import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration
public class AgentScopeConfig {

  private final Function<String, String> environmentVariableResolver;

  public AgentScopeConfig() {
    this(System::getenv);
  }

  AgentScopeConfig(Function<String, String> environmentVariableResolver) {
    this.environmentVariableResolver = environmentVariableResolver;
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
  Model agentScopeModel(AgentProperties agentProperties) {
    AgentProperties.Model modelProperties = agentProperties.model();
    String apiKey = resolveRequiredSecret(modelProperties.apiKeyEnv());

    return switch (modelProperties.provider()) {
      case "dashscope" -> buildDashScopeModel(modelProperties, apiKey);
      case "openai-compatible" -> buildOpenAiCompatibleModel(modelProperties, apiKey);
      default ->
          throw new IllegalArgumentException(
              "Unsupported agent.model.provider: " + modelProperties.provider());
    };
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
  AgentScopeStreamExecutor agentScopeStreamExecutor(
      Model agentScopeModel,
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider,
      SkillUsageStore skillUsageStore,
      WebApprovalGate webApprovalGate) {
    return new AgentScopeStreamExecutor() {
      @Override
      public reactor.core.publisher.Flux<Object> stream(ChatAgentRequest request, Object runtimeContext) {
        return reactor.core.publisher.Flux.using(
            () -> buildHarnessAgent(agentScopeModel, agentProperties, redisTemplateProvider, request, skillUsageStore, webApprovalGate),
            harnessAgent ->
                harnessAgent
                    .streamEvents(request.message(), (RuntimeContext) runtimeContext)
                    .cast(Object.class),
            HarnessAgent::close);
      }
    };
  }

  @Bean
  AbstractFilesystem workspaceFilesystem(
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
    return new RemoteFilesystem(buildBaseStore(agentProperties, redisTemplateProvider));
  }

  @Bean
  SkillUsageStore skillUsageStore(AbstractFilesystem workspaceFilesystem) {
    return new SkillUsageStore(workspaceFilesystem);
  }

  AgentToolPolicy toolPolicy(AgentProperties agentProperties) {
    return new AgentToolPolicy(agentProperties.tools());
  }

  void applyToolPolicy(HarnessAgent.Builder builder, AgentToolPolicy toolPolicy) {
    if (!toolPolicy.fileToolsEnabled()) {
      builder.disableFilesystemTools();
    }
    if (!toolPolicy.shellEnabled()) {
      builder.disableShellTool();
    }
    builder.toolsConfig(toolPolicy.toolsConfig());
  }

  HarnessAgent.Builder configureHarnessAgentBuilder(
      HarnessAgent.Builder builder, AgentToolPolicy toolPolicy, AgentProperties agentProperties) {
    applyToolPolicy(builder, toolPolicy);
    if (agentProperties.memory().enabled()) {
      builder.memory(MemoryConfig.defaults());
    } else {
      builder.disableMemoryTools();
      builder.disableMemoryHooks();
    }

    return builder
        .disableDynamicSkills()
        .disableDefaultWorkspaceSkills()
        .disableSubagents()
        .disableDynamicSubagents();
  }

  HarnessAgent buildHarnessAgent(
      Model agentScopeModel,
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider,
      ChatAgentRequest request,
      SkillUsageStore skillUsageStore,
      WebApprovalGate webApprovalGate) {
    HarnessAgent.Builder builder = HarnessAgent.builder().name("myagent").model(agentScopeModel);
    configureHarnessAgentBuilder(builder, toolPolicy(agentProperties), agentProperties);
    applyRequestScope(builder, request);
    applyFilesystem(builder, agentProperties, redisTemplateProvider);
    applyStateStore(builder, agentProperties, redisTemplateProvider);
    applySkillLearning(builder, agentProperties, skillUsageStore, webApprovalGate);
    return builder.build();
  }

  void applyRequestScope(HarnessAgent.Builder builder, ChatAgentRequest request) {
    builder.permissionContext(permissionContext(request));
  }

  void applyFilesystem(
      HarnessAgent.Builder builder,
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
    builder.workspace(agentProperties.workspace().path());
    builder.filesystem(
        new RemoteFilesystemSpec(buildBaseStore(agentProperties, redisTemplateProvider))
            .isolationScope(IsolationScope.USER));
  }

  PermissionContextState permissionContext(ChatAgentRequest request) {
    return PermissionContextState.builder()
        .mode(PermissionMode.valueOf(request.permissionMode().name()))
        .build();
  }

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

  SkillPromotionGate promotionGate(String mode, WebApprovalGate webApprovalGate) {
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "web" -> webApprovalGate;
      case "local" -> new LocalApprovalGate();
      default -> new RejectAllGate();
    };
  }

  void applySkillLearning(
      HarnessAgent.Builder builder,
      AgentProperties agentProperties,
      SkillUsageStore skillUsageStore,
      WebApprovalGate webApprovalGate) {
    AgentProperties.Skill skill = agentProperties.skill();
    if (!skill.manageToolEnabled()) {
      return;
    }
    SkillManageConfig skillManageConfig =
        SkillManageConfig.builder()
            .autoPromote(false)
            .securityScan(skill.securityScanEnabled())
            .build();
    SkillVisibilityFilter visibilityFilter =
        new CompositeFilter(
            new EnvironmentFilter(skill.environment(), skillUsageStore),
            new CanaryFilter(skill.canaryPercent(), skillUsageStore));
    SkillPromotionGate gate = promotionGate(skill.approvalMode(), webApprovalGate);
    builder
        .environment(skill.environment())
        .enableSkillManageTool(skillManageConfig)
        .enableSkillPromotionGate(gate, visibilityFilter)
        .enableSkillCurator(SkillCuratorConfig.defaults());
  }

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

  private Model buildDashScopeModel(AgentProperties.Model modelProperties, String apiKey) {
    DashScopeChatModel.Builder builder =
        DashScopeChatModel.builder()
            .apiKey(apiKey)
            .modelName(resolveDashScopeModelName(modelProperties))
            .stream(true);
    if (!modelProperties.baseUrl().isBlank()) {
      builder.baseUrl(modelProperties.baseUrl());
    }
    return builder.build();
  }

  private Model buildOpenAiCompatibleModel(AgentProperties.Model modelProperties, String apiKey) {
    if (modelProperties.baseUrl().isBlank()) {
      throw new IllegalArgumentException(
          "agent.model.base-url is required for openai-compatible provider");
    }
    return OpenAIChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelProperties.name())
        .baseUrl(modelProperties.baseUrl())
        .stream(true)
        .build();
  }

  private String resolveRequiredSecret(String secretEnvName) {
    String environmentValue = environmentVariableResolver.apply(secretEnvName);
    if (environmentValue != null && !environmentValue.isBlank()) {
      return environmentValue;
    }

    throw new IllegalStateException("Missing required API key in environment variable: " + secretEnvName);
  }

  private String resolveDashScopeModelName(AgentProperties.Model modelProperties) {
    String configuredName = modelProperties.name();
    String providerPrefix = modelProperties.provider().toLowerCase(Locale.ROOT) + ":";
    if (configuredName.toLowerCase(Locale.ROOT).startsWith(providerPrefix)) {
      return configuredName;
    }
    return modelProperties.provider() + ":" + configuredName;
  }

  record AgentToolPolicy(
      boolean fileToolsEnabled,
      boolean shellEnabled,
      boolean httpFetchEnabled,
      boolean mcpEnabled) {

    // AgentScope tools.json boundary: keep HTTP fetch policy and MCP server registration
    // separate so the two flags do not collapse into one external-tools gate.
    private static final List<String> HTTP_FETCH_TOOL_NAMES = List.of("http_fetch", "web_fetch");

    AgentToolPolicy(AgentProperties.Tools tools) {
      this(
          tools.fileToolsEnabled(),
          tools.shellEnabled(),
          tools.httpFetchEnabled(),
          tools.mcpEnabled());
    }

    ToolsConfig toolsConfig() {
      ToolsConfig toolsConfig = new ToolsConfig();
      if (httpFetchEnabled) {
        toolsConfig.setAllow(HTTP_FETCH_TOOL_NAMES);
      } else {
        toolsConfig.setDeny(HTTP_FETCH_TOOL_NAMES);
      }
      if (!mcpEnabled) {
        toolsConfig.setMcpServers(Collections.emptyMap());
      }
      return toolsConfig;
    }
  }
}
