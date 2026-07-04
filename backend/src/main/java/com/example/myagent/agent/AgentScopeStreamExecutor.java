package com.example.myagent.agent;

import reactor.core.publisher.Flux;

public interface AgentScopeStreamExecutor {

  Flux<Object> stream(String message, Object runtimeContext);
}
