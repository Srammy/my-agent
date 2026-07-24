package com.example.myagent.chat;

import com.example.myagent.agent.AgentExecution;
import reactor.core.publisher.Flux;

public interface ChatAgentGateway {

  Flux<StreamEventDto> stream(ChatAgentRequest request);

  AgentExecution<StreamEventDto> streamExecution(ChatAgentRequest request);

  Flux<StreamEventDto> confirm(ChatToolConfirmationRequest request);

  AgentExecution<StreamEventDto> confirmExecution(ChatToolConfirmationRequest request);
}
