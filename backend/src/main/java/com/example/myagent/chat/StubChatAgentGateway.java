package com.example.myagent.chat;

import com.example.myagent.agent.AgentExecution;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
@ConditionalOnMissingBean(ChatAgentGateway.class)
public class StubChatAgentGateway implements ChatAgentGateway {

  @Override
  public Flux<StreamEventDto> stream(ChatAgentRequest request) {
    return Flux.just(
        StreamEventDto.replyStart(), StreamEventDto.textDelta(request.message()), StreamEventDto.done());
  }

  @Override
  public AgentExecution<StreamEventDto> streamExecution(ChatAgentRequest request) {
    return execution(stream(request));
  }

  @Override
  public Flux<StreamEventDto> confirm(ChatToolConfirmationRequest request) {
    return Flux.just(StreamEventDto.replyStart(), StreamEventDto.done());
  }

  @Override
  public AgentExecution<StreamEventDto> confirmExecution(ChatToolConfirmationRequest request) {
    return execution(confirm(request));
  }

  private AgentExecution<StreamEventDto> execution(Flux<StreamEventDto> source) {
    Sinks.Empty<Void> completion = Sinks.empty();
    Flux<StreamEventDto> events =
        source.doFinally(ignored -> completion.tryEmitEmpty()).cache();
    return new AgentExecution<>(events, completion.asMono());
  }
}
