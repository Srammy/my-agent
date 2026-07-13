package com.example.myagent.chat;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Primary
@ConditionalOnBean(AgentScopeStreamExecutor.class)
@ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
public class AgentScopeChatAgentGateway implements ChatAgentGateway {
  private final AgentScopeStreamExecutor executor;
  private final AgentEventMapper agentEventMapper;
  private final ToolConfirmationService toolConfirmationService;

  public AgentScopeChatAgentGateway(
      AgentScopeStreamExecutor executor,
      AgentEventMapper agentEventMapper,
      ToolConfirmationService toolConfirmationService) {
    this.executor = executor;
    this.agentEventMapper = agentEventMapper;
    this.toolConfirmationService = toolConfirmationService;
  }

  @Override
  public Flux<StreamEventDto> stream(ChatAgentRequest request) {
    RuntimeContext runtimeContext =
        runtimeContext(request.userId(), request.sessionId(), request.permissionMode());

    return executor
        .stream(request, runtimeContext)
        .concatMap(
            agentEvent -> mapAgentEvent(request.userId(), request.sessionId(), agentEvent))
        .onErrorResume(
            throwable -> Flux.just(StreamEventDto.error(errorMessage(throwable))));
  }

  @Override
  public Flux<StreamEventDto> confirm(ChatToolConfirmationRequest request) {
    RuntimeContext runtimeContext =
        runtimeContext(request.userId(), request.sessionId(), request.permissionMode());

    return executor
        .confirm(request, runtimeContext)
        .concatMap(
            agentEvent -> mapAgentEvent(request.userId(), request.sessionId(), agentEvent));
  }

  private Flux<StreamEventDto> mapAgentEvent(
      Long userId, String sessionId, Object agentEvent) {
    if (agentEvent instanceof Throwable throwable) {
      return Flux.error(throwable);
    }
    if (agentEvent instanceof RequireUserConfirmEvent confirmationEvent) {
      return registerUserConfirmation(userId, sessionId, confirmationEvent);
    }
    StreamEventDto mapped = agentEventMapper.map(agentEvent);
    return mapped == null ? Flux.empty() : Flux.just(mapped);
  }

  private Flux<StreamEventDto> registerUserConfirmation(
      Long userId, String sessionId, RequireUserConfirmEvent confirmationEvent) {
    List<ToolUseBlock> toolCalls = confirmationEvent.getToolCalls();
    if (toolCalls == null || toolCalls.isEmpty()) {
      return Flux.just(
          StreamEventDto.error("AgentScope confirmation event did not include a tool call"));
    }
    Set<String> ids = new HashSet<>();
    if (toolCalls.stream().anyMatch(tool ->
        tool.getId() == null || tool.getId().isBlank() || !ids.add(tool.getId()))) {
      return Flux.just(StreamEventDto.error(
          "AgentScope confirmation event included invalid or duplicate tool call ids"));
    }
    return toolConfirmationService
        .create(
            userId,
            sessionId,
            confirmationEvent.getReplyId(),
            toolCalls,
            ConfirmationKind.USER_CONFIRM)
        .map(StreamEventDto::permissionRequired)
        .flux();
  }

  private RuntimeContext runtimeContext(
      Long userId, String sessionId, com.example.myagent.permission.PermissionMode permissionMode) {
    RuntimeContext runtimeContext =
        RuntimeContext.builder().userId(userId.toString()).sessionId(sessionId).build();
    runtimeContext.put(ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY, permissionMode.name());
    return runtimeContext;
  }

  private String errorMessage(Throwable throwable) {
    return throwable.getMessage() == null || throwable.getMessage().isBlank()
        ? throwable.getClass().getSimpleName()
        : throwable.getMessage();
  }
}
