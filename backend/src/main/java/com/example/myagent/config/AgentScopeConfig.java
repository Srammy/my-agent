package com.example.myagent.config;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class AgentScopeConfig {

  @Bean
  Model agentScopeModel(AgentProperties agentProperties, Environment environment) {
    AgentProperties.Model modelProperties = agentProperties.model();
    String apiKey = resolveRequiredSecret(modelProperties.apiKeyEnv(), environment);

    return switch (modelProperties.provider()) {
      case "dashscope" -> buildDashScopeModel(modelProperties, apiKey);
      case "openai-compatible" -> buildOpenAiCompatibleModel(modelProperties, apiKey);
      default ->
          throw new IllegalArgumentException(
              "Unsupported agent.model.provider: " + modelProperties.provider());
    };
  }

  @Bean(destroyMethod = "close")
  HarnessAgent harnessAgent(Model agentScopeModel, AgentProperties agentProperties) {
    HarnessAgent.Builder builder = HarnessAgent.builder().name("myagent").model(agentScopeModel);

    if (!agentProperties.tools().fileToolsEnabled()) {
      builder.disableFilesystemTools();
    }
    if (!agentProperties.tools().shellEnabled()) {
      builder.disableShellTool();
    }

    builder
        .disableDynamicSkills()
        .disableDefaultWorkspaceSkills()
        .disableMemoryTools()
        .disableMemoryHooks()
        .disableSubagents()
        .disableDynamicSubagents()
        .disableToolsConfig();

    return builder.build();
  }

  @Bean
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

  private Model buildDashScopeModel(AgentProperties.Model modelProperties, String apiKey) {
    DashScopeChatModel.Builder builder =
        DashScopeChatModel.builder().apiKey(apiKey).modelName(modelProperties.name()).stream(true);
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

  private String resolveRequiredSecret(String secretEnvName, Environment environment) {
    String environmentValue = System.getenv(secretEnvName);
    if (environmentValue != null && !environmentValue.isBlank()) {
      return environmentValue;
    }

    String propertyValue = environment.getProperty(secretEnvName);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return propertyValue;
    }

    throw new IllegalStateException("Missing required API key in environment variable: " + secretEnvName);
  }
}
