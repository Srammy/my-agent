package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentPropertiesBindingTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

  @Test
  void bindsConfiguredModelProperties() {
    contextRunner
        .withPropertyValues("agent.model.provider=openai", "agent.model.name=gpt-4.1")
        .run(
            context -> {
              AgentProperties agentProperties = context.getBean(AgentProperties.class);
              assertThat(agentProperties.model().provider()).isEqualTo("openai");
              assertThat(agentProperties.model().name()).isEqualTo("gpt-4.1");
              assertThat(agentProperties.agentScope().enabled()).isFalse();
              assertThat(agentProperties.model().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
              assertThat(agentProperties.permission().defaultMode()).isEqualTo("DEFAULT");
            });
  }

  @Test
  void exposesFlywayOnClasspathForSchemaMigrations() {
    assertThatCode(() -> Class.forName("org.flywaydb.core.Flyway")).doesNotThrowAnyException();
  }

  @Test
  void appliesDefaultFallbackValues() {
    contextRunner.run(
        context -> {
          AgentProperties agentProperties = context.getBean(AgentProperties.class);
          assertThat(agentProperties.agentScope().enabled()).isFalse();
          assertThat(agentProperties.model().provider()).isEqualTo("dashscope");
          assertThat(agentProperties.model().name()).isEqualTo("dashscope:qwen-plus");
          assertThat(agentProperties.model().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
          assertThat(agentProperties.stateStore().type()).isEqualTo("redis");
          assertThat(agentProperties.stateStore().redis().uri())
              .isEqualTo("redis://localhost:6379");
          assertThat(agentProperties.stateStore().redis().keyPrefix())
              .isEqualTo("myagent:agent-state:");
          assertThat(agentProperties.workspace().path()).isEqualTo("./.agentscope/workspace");
          assertThat(agentProperties.skill().storage()).isEqualTo("agentscope");
          assertThat(agentProperties.skill().environment()).isEqualTo("prod");
          assertThat(agentProperties.skill().canaryPercent()).isEqualTo(10);
          assertThat(agentProperties.skill().manageToolEnabled()).isTrue();
          assertThat(agentProperties.skill().securityScanEnabled()).isTrue();
          assertThat(agentProperties.skill().approvalMode()).isEqualTo("web");
          assertThat(agentProperties.permission().defaultMode()).isEqualTo("DEFAULT");
          assertThat(agentProperties.tools().fileToolsEnabled()).isFalse();
          assertThat(agentProperties.tools().shellEnabled()).isFalse();
          assertThat(agentProperties.tools().httpFetchEnabled()).isFalse();
          assertThat(agentProperties.tools().mcpEnabled()).isFalse();
        });
  }

  @Test
  void bindsAgentScopeWorkspaceAndSkillDefaults() {
    this.contextRunner.run(
        context -> {
          AgentProperties properties = context.getBean(AgentProperties.class);
          assertThat(properties.workspace().path()).isEqualTo("./.agentscope/workspace");
          assertThat(properties.skill().storage()).isEqualTo("agentscope");
          assertThat(properties.skill().environment()).isEqualTo("prod");
          assertThat(properties.skill().canaryPercent()).isEqualTo(10);
          assertThat(properties.skill().manageToolEnabled()).isTrue();
          assertThat(properties.skill().securityScanEnabled()).isTrue();
          assertThat(properties.skill().approvalMode()).isEqualTo("web");
        });
  }

  @Test
  void bindsAgentScopeAndToolFlags() {
    contextRunner
        .withPropertyValues(
            "agent.agent-scope.enabled=true",
            "agent.tools.file-tools-enabled=true",
            "agent.tools.shell-enabled=true",
            "agent.tools.http-fetch-enabled=true",
            "agent.tools.mcp-enabled=true")
        .run(
            context -> {
              AgentProperties agentProperties = context.getBean(AgentProperties.class);
              assertThat(agentProperties.agentScope().enabled()).isTrue();
              assertThat(agentProperties.tools().fileToolsEnabled()).isTrue();
              assertThat(agentProperties.tools().shellEnabled()).isTrue();
              assertThat(agentProperties.tools().httpFetchEnabled()).isTrue();
              assertThat(agentProperties.tools().mcpEnabled()).isTrue();
            });
  }

  @SpringBootConfiguration
  @EnableConfigurationProperties(AgentProperties.class)
  static class TestConfiguration {}
}
