package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = AgentPropertiesBindingTest.TestConfiguration.class,
    properties = {
      "agent.model.provider=dashscope",
      "agent.model.name=qwen-plus"
    })
class AgentPropertiesBindingTest {

  @Autowired private AgentProperties agentProperties;

  @Test
  void bindsConfiguredModelProperties() {
    assertThat(agentProperties.model().provider()).isEqualTo("dashscope");
    assertThat(agentProperties.model().name()).isEqualTo("qwen-plus");
    assertThat(agentProperties.model().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
    assertThat(agentProperties.permission().defaultMode()).isEqualTo("DEFAULT");
  }

  @SpringBootConfiguration
  @EnableConfigurationProperties(AgentProperties.class)
  static class TestConfiguration {}
}
