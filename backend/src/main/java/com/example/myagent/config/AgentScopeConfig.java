package com.example.myagent.config;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
  HarnessAgent harnessAgent(Model agentScopeModel, AgentProperties agentProperties) {
    HarnessAgent.Builder builder = HarnessAgent.builder().name("myagent").model(agentScopeModel);
    return configureHarnessAgentBuilder(builder, toolPolicy(agentProperties)).build();
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
  AgentScopeStreamExecutor agentScopeStreamExecutor(HarnessAgent harnessAgent) {
    return new AgentScopeStreamExecutor() {
      @Override
      public reactor.core.publisher.Flux<Object> stream(String message, Object runtimeContext) {
        return harnessAgent
            .streamEvents(message, (RuntimeContext) runtimeContext)
            .cast(Object.class);
      }
    };
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
    if (!toolPolicy.externalToolsEnabled()) {
      builder.disableToolsConfig();
    }
  }

  HarnessAgent.Builder configureHarnessAgentBuilder(
      HarnessAgent.Builder builder, AgentToolPolicy toolPolicy) {
    applyToolPolicy(builder, toolPolicy);

    return builder
        .disableDynamicSkills()
        .disableDefaultWorkspaceSkills()
        .disableMemoryTools()
        .disableMemoryHooks()
        .disableSubagents()
        .disableDynamicSubagents();
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

    AgentToolPolicy(AgentProperties.Tools tools) {
      this(
          tools.fileToolsEnabled(),
          tools.shellEnabled(),
          tools.httpFetchEnabled(),
          tools.mcpEnabled());
    }

    boolean externalToolsEnabled() {
      return httpFetchEnabled || mcpEnabled;
    }
  }
}
