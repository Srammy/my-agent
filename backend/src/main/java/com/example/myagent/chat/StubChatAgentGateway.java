package com.example.myagent.chat;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class StubChatAgentGateway implements ChatAgentGateway {

  @Override
  public Flux<StreamEventDto> stream(ChatAgentRequest request) {
    return Flux.just(
        StreamEventDto.replyStart(), StreamEventDto.textDelta(request.message()), StreamEventDto.done());
  }
}
