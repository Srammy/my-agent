package com.example.myagent.agent;

import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatToolConfirmationRequest;
import reactor.core.publisher.Flux;

public interface AgentScopeStreamExecutor {

  Flux<Object> stream(ChatAgentRequest request, Object runtimeContext);

  Flux<Object> confirm(ChatToolConfirmationRequest request, Object runtimeContext);
}
