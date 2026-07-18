package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.chat.AgentEventMapper;
import com.example.myagent.chat.AgentScopeChatAgentGateway;
import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatToolConfirmationRequest;
import com.example.myagent.chat.ChatAgentGateway;
import com.example.myagent.chat.StubChatAgentGateway;
import com.example.myagent.agent.AgentExecution;
import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.skillreview.BaseStoreSkillDraftLock;
import com.example.myagent.skillreview.SkillDraftFingerprint;
import com.example.myagent.skillreview.SkillPromotionGuard;
import com.example.myagent.skillreview.SkillReviewDecisionStore;
import com.example.myagent.skillreview.WebApprovalGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Map;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Answers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class AgentScopeConfigTest {

  @TempDir Path tempDir;

  private final AgentScopeConfig config = new AgentScopeConfig();
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(AgentScopeGatewayContextConfiguration.class);

  @Test
  void cancellationWaitsForUnderlyingAgentStreamBeforeReportingCompletion() {
    HarnessAgent agent = mock(HarnessAgent.class);
    io.agentscope.core.ReActAgent delegate = mock(io.agentscope.core.ReActAgent.class);
    when(agent.getDelegate()).thenReturn(delegate);
    RuntimeContext runtimeContext =
        RuntimeContext.builder().userId("7").sessionId("s_123").build();
    Sinks.Many<Object> underlying = Sinks.many().unicast().onBackpressureBuffer();
    AgentExecution<Object> execution = config.agentExecution(
        () -> agent, ignored -> underlying.asFlux(), runtimeContext);
    java.util.concurrent.atomic.AtomicBoolean completed =
        new java.util.concurrent.atomic.AtomicBoolean();
    reactor.core.Disposable completionSubscription =
        execution.completion().subscribe(ignored -> {}, ignored -> {}, () -> completed.set(true));
    reactor.core.Disposable eventSubscription = execution.events().subscribe();

    eventSubscription.dispose();

    verify(delegate).interrupt(runtimeContext);
    assertThat(completed).isFalse();
    verify(agent, org.mockito.Mockito.never()).close();

    underlying.tryEmitComplete();

    assertThat(completed).isTrue();
    verify(agent).close();
    completionSubscription.dispose();
  }

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
  void productionHarnessEnablesAutoCompaction() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties properties = properties(false, false, false, false);

    config.configureHarnessAgentBuilder(builder, config.toolPolicy(properties), properties);

    assertThat(booleanField(builder, "disableCompaction")).isFalse();
    assertThat(objectField(builder, "compactionConfig")).isNotNull();
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
  void confirmationRequestScopeUsesTheSamePermissionContext() {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    ChatToolConfirmationRequest request =
        new ChatToolConfirmationRequest(
            7L,
            "s_123",
            PermissionMode.ACCEPT_EDITS,
            java.util.List.of(new com.example.myagent.chat.ToolCallDecision(
                new com.example.myagent.toolconfirmation.ToolCallSnapshot(
                    "call-1", "shell_command", Map.of("command", "Get-ChildItem")), true)));

    config.applyRequestScope(builder, request);

    assertThat(config.permissionContext(request).getMode())
        .isEqualTo(io.agentscope.core.permission.PermissionMode.ACCEPT_EDITS);
  }

  @Test
  void confirmationResultCarriesTrustedToolSnapshotForApprovalAndRejection() {
    com.example.myagent.toolconfirmation.ToolCallSnapshot snapshot =
        new com.example.myagent.toolconfirmation.ToolCallSnapshot(
            "call-1", "shell_command", Map.of("command", "Get-ChildItem"));

    for (boolean confirmed : java.util.List.of(true, false)) {
      ConfirmResult result = config.confirmResults(confirmationRequest(confirmed, snapshot)).getFirst();
      assertThat(result.isConfirmed()).isEqualTo(confirmed);
      assertThat(result.getRules()).isEmpty();
      assertThat(result.getToolCall().getId()).isEqualTo("call-1");
      assertThat(result.getToolCall().getName()).isEqualTo("shell_command");
      assertThat(result.getToolCall().getInput()).containsEntry("command", "Get-ChildItem");
    }
  }

  @Test
  void confirmationMessageUsesAgentScopeConfirmResultsMetadata() {
    UserMessage message = config.confirmationMessage(confirmationRequest(true, new com.example.myagent.toolconfirmation.ToolCallSnapshot(
        "call-1", "shell_command", Map.of())));

    assertThat(message.getMetadata()).containsOnlyKeys(Msg.METADATA_CONFIRM_RESULTS);
    assertThat(message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
        .asList()
        .singleElement()
        .isInstanceOf(ConfirmResult.class);
  }

  @Test
  void confirmationExecutorResumesGroupedDecisionsWithOneMessageAndRequestScope() {
    HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class, Answers.RETURNS_SELF);
    HarnessAgent agent = mock(HarnessAgent.class);
    ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore workspaceStore =
        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
    UserScopedFilesystemFactory filesystemFactory =
        new UserScopedFilesystemFactory(
            workspaceStore,
            new BaseStoreSkillDraftLock(workspaceStore),
            new SkillPromotionGuard(
                new SkillReviewDecisionStore(
                    new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
                        workspaceStore,
                        io.agentscope.harness.agent.IsolationScope.USER.toNamespaceFactory()))));
    org.springframework.beans.factory.support.DefaultListableBeanFactory beanFactory =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beanFactory.registerSingleton("redisTemplate", redisTemplate);
    ArgumentCaptor<UserMessage> messageCaptor = ArgumentCaptor.forClass(UserMessage.class);
    ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
    when(builder.build()).thenReturn(agent);
    when(agent.streamEvents(messageCaptor.capture(), contextCaptor.capture())).thenReturn(Flux.empty());

    try (MockedStatic<HarnessAgent> harnessAgent = org.mockito.Mockito.mockStatic(HarnessAgent.class)) {
      harnessAgent.when(HarnessAgent::builder).thenReturn(builder);
      AgentScopeStreamExecutor executor =
          config.agentScopeStreamExecutor(
              mock(Model.class),
              properties(false, false, false, false),
              beanFactory.getBeanProvider(ReactiveStringRedisTemplate.class),
              filesystemFactory,
              mock(WebApprovalGate.class));

      ChatToolConfirmationRequest request = new ChatToolConfirmationRequest(
          7L, "s_123", PermissionMode.ACCEPT_EDITS, java.util.List.of(
              new com.example.myagent.chat.ToolCallDecision(
                  new com.example.myagent.toolconfirmation.ToolCallSnapshot(
                      "call-1", "read_file", Map.of("path", "a.md")), true),
              new com.example.myagent.chat.ToolCallDecision(
                  new com.example.myagent.toolconfirmation.ToolCallSnapshot(
                      "call-2", "shell_command", Map.of("command", "npm test")), false)));
        RuntimeContext context =
            RuntimeContext.builder().userId("7").sessionId("s_123").build();
        context.put(ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY, PermissionMode.ACCEPT_EDITS.name());

        executor.confirm(request, context).blockLast();

        UserMessage message = messageCaptor.getValue();
        assertThat(message.getMetadata()).containsOnlyKeys(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS)).asList()
            .hasSize(2)
            .extracting(result -> ((ConfirmResult) result).isConfirmed())
            .containsExactly(true, false);
        assertThat(message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS)).asList()
            .allSatisfy(result -> assertThat(((ConfirmResult) result).getRules()).isEmpty());
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("7");
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("s_123");
        assertThat(
                contextCaptor
                    .getValue()
                    .get(ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY, String.class))
            .isEqualTo(PermissionMode.ACCEPT_EDITS.name());
    }

    verify(builder).build();
    verify(agent).streamEvents(any(UserMessage.class), any(RuntimeContext.class));
    verify(agent).close();
    ArgumentCaptor<io.agentscope.harness.agent.filesystem.AbstractFilesystem> filesystemCaptor =
        ArgumentCaptor.forClass(io.agentscope.harness.agent.filesystem.AbstractFilesystem.class);
    verify(builder).abstractFilesystem(filesystemCaptor.capture());
    assertThat(
            filesystemCaptor
                .getValue()
                .write(RuntimeContext.empty(), "skills/request-scope.txt", "bound")
                .isSuccess())
        .isTrue();

    io.agentscope.harness.agent.filesystem.AbstractFilesystem sharedFilesystem =
        new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            workspaceStore,
            io.agentscope.harness.agent.IsolationScope.USER.toNamespaceFactory());
    RuntimeContext anotherUserSevenSession =
        RuntimeContext.builder().userId("7").sessionId("another-session").build();
    assertThat(
            sharedFilesystem
                .read(anotherUserSevenSession, "skills/request-scope.txt", 0, 100)
                .isSuccess())
        .isTrue();
    assertThat(
            sharedFilesystem
                .read(
                    RuntimeContext.builder().userId("8").sessionId("s_123").build(),
                    "skills/request-scope.txt",
                    0,
                    100)
                .isSuccess())
        .isFalse();
  }

  @Test
  void productionHarnessEnablesPendingToolRecovery() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();

    config.configureHarnessAgentBuilder(builder, config.toolPolicy(properties(false, false, false, false)), properties(false, false, false, false));

    assertThat(booleanField(objectField(builder, "inner"), "enablePendingToolRecovery")).isTrue();
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
    WebApprovalGate webApprovalGate =
        new WebApprovalGate(decisionStore, mock(SkillDraftFingerprint.class));

    assertThatNoException().isThrownBy(() ->
        config.applySkillLearning(builder, props, usageStore, webApprovalGate));

    assertThat(booleanField(builder, "skillManageToolEnabled")).isTrue();
    assertThat(booleanField(builder, "skillCuratorEnabled")).isTrue();
  }

  @Test
  void workspaceSkillsRemainVisibleWhenManagementIsDisabled() throws Exception {
    HarnessAgent.Builder builder = HarnessAgent.builder();
    AgentProperties props = properties(false, false, false, false, false);
    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
    io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem fs =
        new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store);
    SkillUsageStore usageStore = new SkillUsageStore(fs);
    SkillReviewDecisionStore decisionStore = new SkillReviewDecisionStore(fs);
    WebApprovalGate webApprovalGate =
        new WebApprovalGate(decisionStore, mock(SkillDraftFingerprint.class));

    config.configureHarnessAgentBuilder(builder, config.toolPolicy(props), props);
    config.applySkillLearning(builder, props, usageStore, webApprovalGate);

    assertThat(booleanField(builder, "disableDefaultWorkspaceSkills")).isFalse();
    assertThat(booleanField(builder, "skillManageToolEnabled")).isFalse();
    assertThat(booleanField(builder, "skillCuratorEnabled")).isFalse();
  }

  @Test
  void workspaceFilesystemIsolatesUsersByNamespace() {
    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
    io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem fs =
        new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, io.agentscope.harness.agent.IsolationScope.USER.toNamespaceFactory());

    io.agentscope.core.agent.RuntimeContext alice =
        io.agentscope.core.agent.RuntimeContext.builder().userId("1").sessionId("s").build();
    io.agentscope.core.agent.RuntimeContext bob =
        io.agentscope.core.agent.RuntimeContext.builder().userId("2").sessionId("s").build();

    fs.write(alice, "skills/test/SKILL.md", "---\nname: test\ndescription: t\n---\n");

    assertThat(fs.exists(bob, "skills/test/SKILL.md")).isFalse();
  }

  private AgentProperties properties(
      boolean fileToolsEnabled,
      boolean shellEnabled,
      boolean httpFetchEnabled,
      boolean mcpEnabled) {
    return properties(
        fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled, true);
  }

  private AgentProperties properties(
      boolean fileToolsEnabled,
      boolean shellEnabled,
      boolean httpFetchEnabled,
      boolean mcpEnabled,
      boolean manageToolEnabled) {
    return new AgentProperties(
        new AgentProperties.AgentScope(true),
        new AgentProperties.Workspace(tempDir.toString()),
        new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
        new AgentProperties.StateStore(
            "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
        new AgentProperties.Skill("agentscope", "prod", 10, manageToolEnabled, true),
        new AgentProperties.Permission("DEFAULT"),
        new AgentProperties.Tools(
            fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled));
  }

  private ChatToolConfirmationRequest confirmationRequest(
      boolean confirmed, com.example.myagent.toolconfirmation.ToolCallSnapshot snapshot) {
    return new ChatToolConfirmationRequest(
        7L, "s_123", PermissionMode.DEFAULT,
        java.util.List.of(new com.example.myagent.chat.ToolCallDecision(snapshot, confirmed)));
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
    SkillReviewDecisionStore.class,
    StubChatAgentGateway.class
  })
  static class AgentScopeGatewayContextConfiguration {

    @org.springframework.context.annotation.Bean
    ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
      return org.mockito.Mockito.mock(ReactiveStringRedisTemplate.class);
    }
  }
}
