package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.chat.AgentEventMapper;
import com.example.myagent.chat.AgentScopeChatAgentGateway;
import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatAgentGateway;
import com.example.myagent.chat.StubChatAgentGateway;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.skillreview.SkillReviewDecisionStore;
import com.example.myagent.skillreview.WebApprovalGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Path;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

class AgentScopeConfigTest {

  @TempDir Path tempDir;

  private final AgentScopeConfig config = new AgentScopeConfig();
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(AgentScopeGatewayContextConfiguration.class);

  @Test
  void createsDashScopeModelByDefault() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model("dashscope", "qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    Model model =
        new AgentScopeConfig(name -> "DASHSCOPE_API_KEY".equals(name) ? "dashscope-test-key" : null)
            .agentScopeModel(properties);

    assertThat(model).isInstanceOf(DashScopeChatModel.class);
    assertThat(model.getModelName()).isEqualTo("dashscope:qwen-plus");
  }

  @Test
  void createsOpenAiCompatibleModelWhenConfigured() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model(
                "openai-compatible",
                "gpt-4.1-mini",
                "https://example.test/v1",
                "OPENAI_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    Model model =
        new AgentScopeConfig(name -> "OPENAI_API_KEY".equals(name) ? "openai-test-key" : null)
            .agentScopeModel(properties);

    assertThat(model).isInstanceOf(OpenAIChatModel.class);
    assertThat(model.getModelName()).isEqualTo("gpt-4.1-mini");
  }

  @Test
  void rejectsMissingApiKey() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model("dashscope", "qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    assertThatThrownBy(() -> config.agentScopeModel(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DASHSCOPE_API_KEY");
  }

  @Test
  void rejectsApiKeyProvidedOnlyAsSpringProperty() {
    AgentProperties properties =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    assertThatThrownBy(
            () ->
                new AgentScopeConfig(name -> null)
                    .agentScopeModel(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DASHSCOPE_API_KEY");
  }

  @Test
  void toolFlagsDefaultToDisabledAndCloseHarnessBoundaries() throws Exception {
    AgentScopeConfig.AgentToolPolicy policy = config.toolPolicy(properties(false, false, false, false));
    HarnessAgent.Builder builder = HarnessAgent.builder();

    config.applyToolPolicy(builder, policy);

    assertThat(policy.fileToolsEnabled()).isFalse();
    assertThat(policy.shellEnabled()).isFalse();
    assertThat(policy.httpFetchEnabled()).isFalse();
    assertThat(policy.mcpEnabled()).isFalse();
    assertThat(booleanField(builder, "disableFilesystemTools")).isTrue();
    assertThat(booleanField(builder, "disableShellTool")).isTrue();
    assertThat(booleanField(builder, "disableToolsConfig")).isFalse();
    assertThat(toolsConfig(builder).getDeny()).containsExactly("http_fetch", "web_fetch");
    assertThat(toolsConfig(builder).getAllow()).isNullOrEmpty();
    assertThat(toolsConfig(builder).getMcpServers()).isEmpty();
  }

  @Test
  void enablingHttpFetchKeepsMcpDisabled() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();

    config.applyToolPolicy(builder, config.toolPolicy(properties(false, false, true, false)));

    assertThat(booleanField(builder, "disableToolsConfig")).isFalse();
    assertThat(toolsConfig(builder).getAllow()).containsExactly("http_fetch", "web_fetch");
    assertThat(toolsConfig(builder).getDeny()).isNullOrEmpty();
    assertThat(toolsConfig(builder).getMcpServers()).isEmpty();
  }

  @Test
  void enablingMcpKeepsHttpFetchDisabled() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();

    config.applyToolPolicy(builder, config.toolPolicy(properties(false, false, false, true)));

    assertThat(booleanField(builder, "disableToolsConfig")).isFalse();
    assertThat(toolsConfig(builder).getDeny()).containsExactly("http_fetch", "web_fetch");
    assertThat(toolsConfig(builder).getAllow()).isNullOrEmpty();
    assertThat(toolsConfig(builder).getMcpServers()).isNull();
  }

  @Test
  void productionHarnessAgentBuilderUsesFineGrainedToolsConfig() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties properties = properties(false, false, true, true);

    config.configureHarnessAgentBuilder(builder, config.toolPolicy(properties), properties);

    assertThat(booleanField(builder, "disableFilesystemTools")).isTrue();
    assertThat(booleanField(builder, "disableShellTool")).isTrue();
    assertThat(booleanField(builder, "disableToolsConfig")).isFalse();
    assertThat(toolsConfig(builder).getAllow()).containsExactly("http_fetch", "web_fetch");
    assertThat(toolsConfig(builder).getMcpServers()).isNull();
  }

  @Test
  void productionHarnessKeepsMemoryHooksAndToolsEnabled() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties properties = properties(false, false, false, false);

    config.configureHarnessAgentBuilder(builder, config.toolPolicy(properties), properties);

    assertThat(booleanField(builder, "disableMemoryTools")).isFalse();
    assertThat(booleanField(builder, "disableMemoryHooks")).isFalse();
    assertThat(objectField(builder, "memoryConfig")).isNotNull();
  }

  @Test
  void enabledFileAndShellFlagsAreReflectedOnHarnessBuilder() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();

    config.applyToolPolicy(builder, config.toolPolicy(properties(true, true, false, false)));

    assertThat(booleanField(builder, "disableFilesystemTools")).isFalse();
    assertThat(booleanField(builder, "disableShellTool")).isFalse();
    assertThat(booleanField(builder, "disableToolsConfig")).isFalse();
    assertThat(toolsConfig(builder).getDeny()).containsExactly("http_fetch", "web_fetch");
    assertThat(toolsConfig(builder).getMcpServers()).isEmpty();
  }

  @Test
  void deploymentRequiresRedisBackedRemoteFilesystem() {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties props =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    assertThatThrownBy(() -> config.applyDistributedStore(builder, props, emptyRedisProvider()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Redis");
  }

  @Test
  void deploymentRejectsNonRedisStateStore() {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties props =
        new AgentProperties(
            new AgentProperties.AgentScope(true),
            new AgentProperties.Workspace(tempDir.toString()),
            new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
            new AgentProperties.StateStore(
                "file", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
            new AgentProperties.Skill("agentscope", "prod", 10, true, true),
            new AgentProperties.Permission("DEFAULT"),
            new AgentProperties.Tools(false, false, false, false));

    assertThatThrownBy(() -> config.applyDistributedStore(builder, props, emptyRedisProvider()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agent.state-store.type=redis");
  }

  @Test
  void requestScopeAddsPermissionContextToHarnessBuilder() {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    ChatAgentRequest request = new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.ACCEPT_EDITS);

    config.applyRequestScope(builder, request);

    PermissionContextState permissionContext = config.permissionContext(request);
    assertThat(permissionContext.getMode())
        .isEqualTo(io.agentscope.core.permission.PermissionMode.ACCEPT_EDITS);
  }

  @Test
  void contextUsesStubGatewayWhenAgentScopeDisabledWithoutApiKey() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ChatAgentGateway.class);
          assertThat(context).hasSingleBean(StubChatAgentGateway.class);
          assertThat(context).doesNotHaveBean(AgentScopeChatAgentGateway.class);
          assertThat(context).doesNotHaveBean(Model.class);
        });
  }

  @Test
  void contextFailsClearlyWhenAgentScopeEnabledWithoutApiKey() {
    contextRunner
        .withPropertyValues("agent.agent-scope.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("Missing required API key")
                  .hasMessageContaining("DASHSCOPE_API_KEY");
            });
  }

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

  private AgentProperties properties(
      boolean fileToolsEnabled,
      boolean shellEnabled,
      boolean httpFetchEnabled,
      boolean mcpEnabled) {
    return new AgentProperties(
        new AgentProperties.AgentScope(true),
        new AgentProperties.Workspace(tempDir.toString()),
        new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
        new AgentProperties.StateStore(
            "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
        new AgentProperties.Skill("agentscope", "prod", 10, true, true),
        new AgentProperties.Permission("DEFAULT"),
        new AgentProperties.Tools(
            fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled));
  }

  private org.springframework.beans.factory.ObjectProvider<ReactiveStringRedisTemplate>
      emptyRedisProvider() {
    return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
        .getBeanProvider(ReactiveStringRedisTemplate.class);
  }

  private boolean booleanField(Object target, String fieldName) throws Exception {
    return field(target, fieldName).getBoolean(target);
  }

  private ToolsConfig toolsConfig(HarnessAgent.Builder builder) throws Exception {
    return (ToolsConfig) objectField(builder, "toolsConfigOverride");
  }

  private Object objectField(Object target, String fieldName) throws Exception {
    return field(target, fieldName).get(target);
  }

  private Field field(Object target, String fieldName) throws Exception {
    Class<?> type = target.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException exception) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentProperties.class)
  @Import({
    AgentScopeConfig.class,
    AgentScopeChatAgentGateway.class,
    AgentEventMapper.class,
    StubChatAgentGateway.class
  })
  static class AgentScopeGatewayContextConfiguration {

    @org.springframework.context.annotation.Bean
    ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
      return org.mockito.Mockito.mock(ReactiveStringRedisTemplate.class);
    }
  }
}
