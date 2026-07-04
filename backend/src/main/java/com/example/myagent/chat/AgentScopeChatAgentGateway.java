package com.example.myagent.chat;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import io.agentscope.core.agent.RuntimeContext;
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

  public AgentScopeChatAgentGateway(
      AgentScopeStreamExecutor executor, AgentEventMapper agentEventMapper) {
    this.executor = executor;
    this.agentEventMapper = agentEventMapper;
  }

  @Override
  public Flux<StreamEventDto> stream(ChatAgentRequest request) {
    RuntimeContext runtimeContext =
        RuntimeContext.builder()
            .userId(request.userId().toString())
            .sessionId(request.sessionId())
            .build();

    return executor
        .stream(request.message(), runtimeContext)
        .flatMap(
            agentEvent -> {
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

  private String errorMessage(Throwable throwable) {
    return throwable.getMessage() == null || throwable.getMessage().isBlank()
        ? throwable.getClass().getSimpleName()
        : throwable.getMessage();
  }
}
