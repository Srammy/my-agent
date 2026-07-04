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
          assertThat(agentProperties.deployment().mode()).isEqualTo("local");
          assertThat(agentProperties.model().provider()).isEqualTo("dashscope");
          assertThat(agentProperties.model().name()).isEqualTo("dashscope:qwen-plus");
          assertThat(agentProperties.model().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
          assertThat(agentProperties.stateStore().type()).isEqualTo("redis");
          assertThat(agentProperties.stateStore().redis().uri())
              .isEqualTo("redis://localhost:6379");
          assertThat(agentProperties.stateStore().redis().keyPrefix())
              .isEqualTo("myagent:agent-state:");
          assertThat(agentProperties.skill().storage()).isEqualTo("mysql");
          assertThat(agentProperties.permission().defaultMode()).isEqualTo("DEFAULT");
        });
  }

  @SpringBootConfiguration
  @EnableConfigurationProperties(AgentProperties.class)
  static class TestConfiguration {}
}
