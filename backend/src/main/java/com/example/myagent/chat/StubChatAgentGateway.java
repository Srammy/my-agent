package com.example.myagent.chat;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnMissingBean(ChatAgentGateway.class)
public class StubChatAgentGateway implements ChatAgentGateway {

  @Override
  public Flux<StreamEventDto> stream(ChatAgentRequest request) {
    return Flux.just(
        StreamEventDto.replyStart(), StreamEventDto.textDelta(request.message()), StreamEventDto.done());
  }
}
