package com.example.myagent.chat;

import reactor.core.publisher.Flux;

public interface ChatAgentGateway {

  Flux<StreamEventDto> stream(ChatAgentRequest request);

  Flux<StreamEventDto> confirm(ChatToolConfirmationRequest request);
}
