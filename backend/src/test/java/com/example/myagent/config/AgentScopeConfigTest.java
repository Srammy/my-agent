package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AgentScopeConfigTest {

  private final AgentScopeConfig config = new AgentScopeConfig();

  @Test
  void createsDashScopeModelByDefault() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.Deployment("local"),
            new AgentProperties.Model("dashscope", "qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("mysql", "./.agentscope/cache/skills"),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    Model model =
        config.agentScopeModel(
            properties, new MockEnvironment().withProperty("DASHSCOPE_API_KEY", "dashscope-test-key"));

    assertThat(model).isInstanceOf(DashScopeChatModel.class);
    assertThat(model.getModelName()).isEqualTo("qwen-plus");
  }

  @Test
  void createsOpenAiCompatibleModelWhenConfigured() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.Deployment("local"),
            new AgentProperties.Model(
                "openai-compatible",
                "gpt-4.1-mini",
                "https://example.test/v1",
                "OPENAI_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("mysql", "./.agentscope/cache/skills"),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    Model model =
        config.agentScopeModel(
            properties, new MockEnvironment().withProperty("OPENAI_API_KEY", "openai-test-key"));

    assertThat(model).isInstanceOf(OpenAIChatModel.class);
    assertThat(model.getModelName()).isEqualTo("gpt-4.1-mini");
  }

  @Test
  void rejectsMissingApiKey() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.Deployment("local"),
            new AgentProperties.Model("dashscope", "qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("mysql", "./.agentscope/cache/skills"),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    assertThatThrownBy(() -> config.agentScopeModel(properties, new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DASHSCOPE_API_KEY");
  }
}
