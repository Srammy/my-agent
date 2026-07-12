package com.example.myagent.chat;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
            agentEvent -> {
              if (agentEvent instanceof RequireUserConfirmEvent confirmationEvent) {
                return registerUserConfirmation(
                    request.userId(), request.sessionId(), confirmationEvent);
              }
              StreamEventDto mapped = agentEventMapper.map(agentEvent);
              if (mapped != null) {
                return Flux.just(mapped);
              }
              if (agentEvent instanceof Throwable throwable) {
                return Flux.just(StreamEventDto.error(errorMessage(throwable)));
              }
              return Flux.empty();
            })
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
            agentEvent -> {
              if (agentEvent instanceof Throwable throwable) {
                // AgentScope may surface SDK failures as event values; confirmation recovery
                // routes them through ChatService's reactive error handling.
                return Flux.error(throwable);
              }
              if (agentEvent instanceof RequireUserConfirmEvent confirmationEvent) {
                return registerUserConfirmation(
                    request.userId(), request.sessionId(), confirmationEvent);
              }
              StreamEventDto mapped = agentEventMapper.map(agentEvent);
              return mapped == null ? Flux.empty() : Flux.just(mapped);
            });
  }

  private Flux<StreamEventDto> registerUserConfirmation(
      Long userId, String sessionId, RequireUserConfirmEvent confirmationEvent) {
    if (confirmationEvent.getToolCalls() == null || confirmationEvent.getToolCalls().isEmpty()) {
      return Flux.just(
          StreamEventDto.error("AgentScope confirmation event did not include a tool call"));
    }
    return toolConfirmationService
        .create(
            userId,
            sessionId,
            confirmationEvent.getReplyId(),
            confirmationEvent.getToolCalls().getFirst(),
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
