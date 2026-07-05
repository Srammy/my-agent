package com.example.myagent.agent;

import com.example.myagent.chat.ChatAgentRequest;
import reactor.core.publisher.Flux;

public interface AgentScopeStreamExecutor {

  Flux<Object> stream(ChatAgentRequest request, Object runtimeContext);
}
