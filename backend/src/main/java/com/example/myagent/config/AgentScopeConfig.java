package com.example.myagent.config;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.agent.AgentExecution;
import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatToolConfirmationRequest;
import com.example.myagent.skillreview.BaseStoreSkillDraftLock;
import com.example.myagent.skillreview.SkillDraftLock;
import com.example.myagent.skillreview.SkillPromotionGuard;
import com.example.myagent.skillreview.SkillReviewDecisionStore;
import com.example.myagent.skillreview.WebApprovalGate;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
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
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.curator.CanaryFilter;
import io.agentscope.harness.agent.skill.curator.CompositeFilter;
import io.agentscope.harness.agent.skill.curator.EnvironmentFilter;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Configuration
public class AgentScopeConfig {

  static final String CANCELLATION_REQUESTED_CONTEXT_KEY =
      "myagent.sessionExecution.cancellationRequested";

  static final class ExecutionCancellationGate {
    private boolean cancelled;
    private int actingBatches;

    synchronized void cancel() {
      cancelled = true;
    }

    synchronized boolean tryBeginActing() {
      if (cancelled) {
        return false;
      }
      actingBatches++;
      return true;
    }

    synchronized void finishActing() {
      actingBatches--;
    }
  }

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
      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
      UserScopedFilesystemFactory filesystemFactory,
      WebApprovalGate webApprovalGate) {
    return new AgentScopeStreamExecutor() {
      @Override
      public reactor.core.publisher.Flux<Object> stream(ChatAgentRequest request, Object runtimeContext) {
        return streamExecution(request, runtimeContext).events();
      }

      @Override
      public AgentExecution<Object> streamExecution(
          ChatAgentRequest request, Object runtimeContext) {
        RuntimeContext context = (RuntimeContext) runtimeContext;
        return agentExecution(
            () ->
                buildHarnessAgent(
                    agentScopeModel,
                    agentProperties,
                    redisTemplateProvider,
                    requestScope(request),
                    filesystemFactory,
                    webApprovalGate),
            harnessAgent ->
                harnessAgent
                    .streamEvents(request.message(), context)
                    .cast(Object.class),
            context);
      }

      @Override
      public reactor.core.publisher.Flux<Object> confirm(
          ChatToolConfirmationRequest request, Object runtimeContext) {
        return confirmExecution(request, runtimeContext).events();
      }

      @Override
      public AgentExecution<Object> confirmExecution(
          ChatToolConfirmationRequest request, Object runtimeContext) {
        RuntimeContext context = (RuntimeContext) runtimeContext;
        return agentExecution(
            () ->
                buildHarnessAgent(
                    agentScopeModel,
                    agentProperties,
                    redisTemplateProvider,
                    requestScope(request),
                    filesystemFactory,
                    webApprovalGate),
            harnessAgent ->
                harnessAgent
                    .streamEvents(confirmationMessage(request), context)
                    .cast(Object.class),
            context);
      }
    };
  }

  <T> AgentExecution<T> agentExecution(
      Supplier<HarnessAgent> agentSupplier,
      Function<HarnessAgent, Flux<T>> sourceFactory,
      RuntimeContext runtimeContext) {
    Sinks.Empty<Void> completion = Sinks.empty();
    AtomicBoolean subscribed = new AtomicBoolean();
    ExecutionCancellationGate cancellationGate = new ExecutionCancellationGate();
    runtimeContext.put(CANCELLATION_REQUESTED_CONTEXT_KEY, cancellationGate);
    Flux<T> events = Flux.create(sink -> {
      if (!subscribed.compareAndSet(false, true)) {
        sink.error(new IllegalStateException("Agent execution supports only one event subscriber"));
        return;
      }
      if (sink.isCancelled()) {
        completion.tryEmitEmpty();
        return;
      }

      final HarnessAgent harnessAgent;
      try {
        harnessAgent = agentSupplier.get();
      } catch (Throwable error) {
        sink.error(error);
        completion.tryEmitEmpty();
        return;
      }

      AtomicBoolean terminated = new AtomicBoolean();
      sink.onCancel(() -> {
        cancellationGate.cancel();
        if (terminated.get()) {
          return;
        }
        try {
          harnessAgent.getDelegate().interrupt(runtimeContext);
        } catch (Throwable ignored) {
          // Completion is determined by the underlying stream and resource close, not interrupt().
        }
      });
      if (sink.isCancelled()) {
        closeWithoutExecution(harnessAgent, completion);
        return;
      }

      class Termination {
        void finish(Throwable error) {
          if (!terminated.compareAndSet(false, true)) {
            return;
          }
          try {
            harnessAgent.close();
          } catch (Throwable closeError) {
            if (!sink.isCancelled()) {
              sink.error(closeError);
            }
            return;
          }
          if (!sink.isCancelled()) {
            if (error == null) {
              sink.complete();
            } else {
              sink.error(error);
            }
          }
          completion.tryEmitEmpty();
        }
      }
      Termination termination = new Termination();
      try {
        sourceFactory.apply(harnessAgent)
            .contextWrite(sink.contextView())
            .subscribe(
                sink::next,
                termination::finish,
                () -> termination.finish(null));
      } catch (Throwable error) {
        termination.finish(error);
      }
    });
    return new AgentExecution<>(events, completion.asMono());
  }

  MiddlewareBase cancellationAwareActingMiddleware() {
    return new MiddlewareBase() {
      @Override
      public Flux<AgentEvent> onActing(
          Agent agent,
          RuntimeContext runtimeContext,
          ActingInput input,
          Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
          ExecutionCancellationGate cancellationGate = runtimeContext == null
              ? null
              : runtimeContext.get(
                  CANCELLATION_REQUESTED_CONTEXT_KEY, ExecutionCancellationGate.class);
          if (cancellationGate != null && !cancellationGate.tryBeginActing()) {
            return Flux.error(new InterruptedException("Agent execution interrupted"));
          }
          if (cancellationGate == null) {
            return next.apply(input);
          }
          final Flux<AgentEvent> acting;
          try {
            acting = Objects.requireNonNull(next.apply(input), "acting middleware returned null");
          } catch (Throwable error) {
            cancellationGate.finishActing();
            return Flux.error(error);
          }
          return acting.doFinally(ignored -> cancellationGate.finishActing());
        });
      }
    };
  }

  private void closeWithoutExecution(
      HarnessAgent harnessAgent, Sinks.Empty<Void> completion) {
    try {
      harnessAgent.close();
      completion.tryEmitEmpty();
    } catch (Throwable ignored) {
      // Fail closed: an uncertain resource shutdown must not be reported as completed.
    }
  }

  @Bean
  BaseStore workspaceBaseStore(
      AgentProperties agentProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    return buildBaseStore(agentProperties, redisTemplateProvider);
  }

  @Bean
  AbstractFilesystem workspaceFilesystem(BaseStore workspaceBaseStore) {
    return new BinarySafeRemoteFilesystem(
        workspaceBaseStore, IsolationScope.USER.toNamespaceFactory());
  }

  @Bean
  SkillDraftLock skillDraftLock(BaseStore workspaceBaseStore) {
    return new BaseStoreSkillDraftLock(workspaceBaseStore);
  }

  @Bean
  UserScopedFilesystemFactory userScopedFilesystemFactory(
      BaseStore workspaceBaseStore,
      SkillDraftLock skillDraftLock,
      SkillReviewDecisionStore decisionStore) {
    return new UserScopedFilesystemFactory(
        workspaceBaseStore,
        skillDraftLock,
        new SkillPromotionGuard(decisionStore));
  }

  AgentToolPolicy toolPolicy(AgentProperties agentProperties) {
    return new AgentToolPolicy(agentProperties.tools());
  }

  /**
   * 将 AgentProperties.tools 的四个开关翻译成 HarnessAgent.Builder 配置。
   *
   * <p>第一步：文件和 shell 工具（直接控制 agent 能力）
   * <ul>
   *   <li>fileToolsEnabled=false → disableFilesystemTools()，agent 无法使用 read_file/write_file 等
   *   <li>shellEnabled=false     → disableShellTool()，agent 无法执行 shell 命令
   * </ul>
   *
   * <p>第二步：toolsConfig（细粒度网络和 MCP 配置）
   * <ul>
   *   <li>httpFetchEnabled=true  → allow ["http_fetch","web_fetch"]
   *   <li>httpFetchEnabled=false → deny  ["http_fetch","web_fetch"]
   *   <li>mcpEnabled=false       → mcpServers={}，不挂载任何 MCP server
   * </ul>
   *
   * <p>链路：application.yml (agent.tools.*) → AgentProperties.Tools → AgentToolPolicy → 此方法 → HarnessAgent.Builder
   */
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
    builder.middleware(cancellationAwareActingMiddleware());
    builder.memory(MemoryConfig.defaults());
    builder.enablePendingToolRecovery(true);
    // 自动压缩配置：
    //   - 消息数 ≥ 50 → 触发 LLM summary 压缩（保留最近 20 条原始消息）
    //   - 预压缩参数截断：消息数 ≥ 25 或 token 数 ≥ 40000 时，把 tool 调用参数截断至 2000 字符
    //   - 大工具结果卸载：tool 结果 > 80000 字符时卸载到 /large_tool_results，保留 2000 字符预览
    builder.compaction(
        CompactionConfig.builder()
            .truncateArgs(CompactionConfig.TruncateArgsConfig.builder().build())
            .build());
    builder.toolResultEviction(ToolResultEvictionConfig.defaults());

    return builder
            // 关掉”每次推理前重新合并”，改成 build 时合并一次。什么时候用 disableDynamicSkills()：单次任务，跑完就退出；或市场后端慢、不想每轮拉。平时不用动这个开关。
//        .disableDynamicSkills()
            // 禁用 subagent 能力（本项目不需要 agent 派生子 agent）
        .disableSubagents()
            // 禁用运行时动态创建 subagent（与 disableSubagents 配合，彻底关闭子 agent 功能）
        .disableDynamicSubagents();
  }

  HarnessAgent buildHarnessAgent(
      Model agentScopeModel,
      AgentProperties agentProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
      AgentRequestScope requestScope,
      UserScopedFilesystemFactory filesystemFactory,
      WebApprovalGate webApprovalGate) {
    HarnessAgent.Builder builder = HarnessAgent.builder().name("myagent").model(agentScopeModel);
    String userId = requestScope.userId().toString();
    AbstractFilesystem userFilesystem = filesystemFactory.create(userId);
    configureHarnessAgentBuilder(builder, toolPolicy(agentProperties), agentProperties);
    applyRequestScope(builder, requestScope);
    applyDistributedStore(builder, agentProperties, redisTemplateProvider);
    applyFilesystem(builder, agentProperties, userFilesystem);
    applySkillLearning(
        builder, agentProperties, filesystemFactory.usageStore(userId), webApprovalGate);
    return builder.build();
  }

  void applyRequestScope(HarnessAgent.Builder builder, ChatAgentRequest request) {
    applyRequestScope(builder, requestScope(request));
  }

  void applyRequestScope(HarnessAgent.Builder builder, ChatToolConfirmationRequest request) {
    applyRequestScope(builder, requestScope(request));
  }

  private void applyRequestScope(HarnessAgent.Builder builder, AgentRequestScope requestScope) {
    builder.permissionContext(permissionContext(requestScope));
  }

  void applyFilesystem(
      HarnessAgent.Builder builder,
      AgentProperties agentProperties,
      AbstractFilesystem userFilesystem) {
    builder.workspace(agentProperties.workspace().path());
    builder.abstractFilesystem(userFilesystem);
  }

  PermissionContextState permissionContext(ChatAgentRequest request) {
    return permissionContext(requestScope(request));
  }

  PermissionContextState permissionContext(ChatToolConfirmationRequest request) {
    return permissionContext(requestScope(request));
  }

  private PermissionContextState permissionContext(AgentRequestScope requestScope) {
    return PermissionContextState.builder()
        .mode(io.agentscope.core.permission.PermissionMode.valueOf(requestScope.permissionMode().name()))
        .build();
  }

  List<ConfirmResult> confirmResults(ChatToolConfirmationRequest request) {
    return request.decisions().stream()
        .map(decision -> new ConfirmResult(
            decision.confirmed(), decision.toolCall().toToolUseBlock(), Collections.emptyList()))
        .toList();
  }

  UserMessage confirmationMessage(ChatToolConfirmationRequest request) {
    return UserMessage.builder()
        .metadata(java.util.Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults(request)))
        .build();
  }

  private AgentRequestScope requestScope(ChatAgentRequest request) {
    return new AgentRequestScope(request.userId(), request.sessionId(), request.permissionMode());
  }

  private AgentRequestScope requestScope(ChatToolConfirmationRequest request) {
    return new AgentRequestScope(request.userId(), request.sessionId(), request.permissionMode());
  }

  private record AgentRequestScope(
      Long userId,
      String sessionId,
      com.example.myagent.permission.PermissionMode permissionMode) {}

  void applyDistributedStore(
      HarnessAgent.Builder builder,
      AgentProperties agentProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    builder.distributedStore(DistributedStore.builder()
            .agentStateStore(buildAgentStateStore(agentProperties, redisTemplateProvider))
            .baseStore(buildBaseStore(agentProperties, redisTemplateProvider))
            .build());
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
            .securityScan(skill.securityScanEnabled())  //  是 AgentScope SkillManageConfig 的一个构建选项，控制 Agent 通过 SkillManageTool 自动创建或修改 skill 草稿时，是否对草稿内容做安全扫描。true（当前默认）— AgentScope 在把 skill 草稿写入 _drafts/ 之前，会扫描草稿内容，检测潜在的危险代码（如 shell 注入、危险系统调用等），不通过则拒绝写入
            .build();
    SkillVisibilityFilter visibilityFilter =
        new CompositeFilter(
            new EnvironmentFilter(skill.environment(), skillUsageStore),
            new CanaryFilter(skill.canaryPercent(), skillUsageStore));
    builder
        .environment(skill.environment())
        .enableSkillManageTool(skillManageConfig)
        .enableSkillPromotionGate(webApprovalGate, visibilityFilter)
            /**  SkillCuratorConfig.defaults()的默认配置如下
             * │       字段       │    默认值    │                                   含义                                    │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ enabled          │ true         │ curator 后台任务启用                                                      │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ intervalHours    │ 168（= 7天） │ curator 每隔多少小时运行一次维护扫描                                      │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ minIdleHours     │ 2            │ skill 至少空闲多少小时才允许 curator 操作（防误删刚用过的）               │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ staleAfterDays   │ 30           │ skill 超过 30 天未被使用，标记为"过时"                                    │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ archiveAfterDays │ 90           │ skill 超过 90 天未被使用，归档（staleAfterDays 的上限保证archive≥ stale） │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ umbrellaPassMode │ DRY_RUN_ONLY │ umbrella pass（整体维护扫描）默认只模拟运行，不实际执行变更               │
             *   ├──────────────────┼──────────────┼───────────────────────────────────────────────────────────────────────────┤
             *   │ backupRetention  │ 5            │ 保留最近 5 个 skill 版本备份                                              │
             *   └──────────────────┴──────────────┴───────────────────────────────────────────────────────────────────────────┘
             *
             *   SkillCurator 是做什么的
             *
             *   它是 AgentScope 的后台维护进程，定期（默认每 7 天）扫描 skill 仓库，按生命周期规则对 skill 打标签或清理：active →
             *   stale（30天）→ archived（90天）。同时维护版本备份，最多保留 5 份。
             *
             *   DRY_RUN_ONLY 的意义
             *
             *   umbrellaPassMode 控制全局扫描时是否真正执行变更。DRY_RUN_ONLY 表示 umbrella pass 只记录将要发生的操作，不实际修改任何 skill
             *   状态——相当于"只读审计模式"。如果想让它真正执行清理，需要显式改为 APPLY。
             *
             *   对当前项目来说，7 天维护 + DRY_RUN_ONLY 是保守的默认配置，skill 不会被自动删除，只会记录哪些应该清理。
             */
        .enableSkillCurator(SkillCuratorConfig.defaults());
  }

  AgentStateStore buildAgentStateStore(
      AgentProperties agentProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
    if (redisTemplate == null || !"redis".equalsIgnoreCase(agentProperties.stateStore().type())) {
      throw new IllegalStateException(
          "Distributed deployment requires agent.state-store.type=redis and a Redis bean");
    }
    return new RedisAgentStateStore(redisTemplate, agentProperties.stateStore().redis().keyPrefix());
  }

  BaseStore buildBaseStore(
      AgentProperties agentProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
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
      return configuredName.substring(providerPrefix.length());
    }
    return configuredName;
  }

  record AgentToolPolicy(
          /**
           * 让agent 默认无法读写宿主机文件系统.在Docker 容器里运行时，agent 的文件工具操作的是容器内的workspace（.agentscope/workspace），而不是宿主机路径。默认关闭可以防止配置错误导致 agent 意外访问容器外的挂载路径。
           */
      boolean fileToolsEnabled,
          /**
           *   shell 工具让 agent 可以直接执行任意系统命令。一旦 prompt 被注入（用户输入里夹带了恶意指令），agent 可能执行 rm
           *   -rf、数据泄露命令、网络扫描等。文件工具至少限制在固定路径，shell 没有任何范围限制。
           *
           *   Docker 容器里的影响
           *
           *   容器里执行 shell 命令可以：
           *   - 访问挂载的卷
           *   - 通过 /proc、/etc 探测宿主机信息
           *   - 如果容器有 --privileged 或特定 capability，甚至可以逃逸
           *
           *   业务上不需要
           *
           *   这个项目的 agent 主要做对话、skill 管理、文件读写——不需要执行 shell 命令。没有需求就不开，不是为了禁而禁。
           */
      boolean shellEnabled,
          /**
           * 数据泄露风险
           *
           *   http_fetch 和 web_fetch 让 agent 可以主动向任意外部 URL 发起请求。如果 agent 被恶意 prompt
           *   操控，它可以把对话内容、用户数据、内部系统信息通过 HTTP 请求偷传到外部服务器。这是 LLM agent 里最常见的数据外泄路径之一。
           *
           *   内网探测
           *
           *   在服务器或容器环境里，agent 可以用 HTTP fetch 扫描内网：
           *   http://192.168.1.1   # 路由器管理页面
           *   http://10.0.0.x      # 内网其他服务
           *   http://169.254.169.254/latest/meta-data/  # AWS EC2 实例元数据（包含 IAM 凭证）
           *
           *   和 file/shell 的区别
           *
           *   file 和 shell 工具的影响范围在容器内部，http fetch 的影响范围是整个互联网和内网——出口更宽，后果更难审计。
           *
           *   开启场景
           *
           *   如果 agent 需要联网（搜索、调用外部 API），通过 AGENT_TOOLS_HTTP_FETCH_ENABLED=true 显式开启，职责清晰。目前项目里 agent
           *   的主要场景是基于 skill 和对话完成任务，不需要主动联
           */
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
