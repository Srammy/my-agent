package com.example.myagent.agent;

import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatToolConfirmationRequest;
import reactor.core.publisher.Flux;

public interface AgentScopeStreamExecutor {

  /**
   * Returns the raw AgentScope event stream. Some AgentScope paths surface SDK failures as
   * {@link Throwable} values inside the stream, not only as reactive error signals.
   */
  Flux<Object> stream(ChatAgentRequest request, Object runtimeContext);

  AgentExecution<Object> streamExecution(ChatAgentRequest request, Object runtimeContext);

  /**
   * Returns the raw AgentScope recovery event stream with the same event contract as
   * {@link #stream(ChatAgentRequest, Object)}.
   */
  Flux<Object> confirm(ChatToolConfirmationRequest request, Object runtimeContext);

  AgentExecution<Object> confirmExecution(
      ChatToolConfirmationRequest request, Object runtimeContext);
}
