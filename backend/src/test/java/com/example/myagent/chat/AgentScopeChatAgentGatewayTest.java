package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.permission.PermissionMode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class AgentScopeChatAgentGatewayTest {

  private static final String PERMISSION_MODE_CONTEXT_KEY =
      ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY;

  @Mock private AgentScopeStreamExecutor executor;

  @Test
  void mapsAgentScopeEventsAndBuildsRuntimeContextFromRequest() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(
            Flux.just(
                new AgentStartEvent("s_123", "reply-1", "assistant"),
                new TextBlockDeltaEvent("reply-1", "block-1", "world"),
                new AgentEndEvent("reply-1")));

    AgentScopeChatAgentGateway gateway = new AgentScopeChatAgentGateway(executor, new AgentEventMapper());

    var events =
        gateway
            .stream(new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.DEFAULT))
            .collectList()
            .block();

    assertThat(events)
        .extracting(StreamEventDto::type)
        .containsExactly("reply_start", "text_delta", "done");
    assertThat(events.get(1).payload()).containsEntry("delta", "world");

    ArgumentCaptor<Object> runtimeContextCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<ChatAgentRequest> requestCaptor = ArgumentCaptor.forClass(ChatAgentRequest.class);
    verify(executor).stream(requestCaptor.capture(), runtimeContextCaptor.capture());
    assertThat(requestCaptor.getValue().message()).isEqualTo("hello");
    assertThat(runtimeContextCaptor.getValue()).isInstanceOf(RuntimeContext.class);
    RuntimeContext runtimeContext = (RuntimeContext) runtimeContextCaptor.getValue();
    assertThat(runtimeContext.getUserId()).isEqualTo("7");
    assertThat(runtimeContext.getSessionId()).isEqualTo("s_123");
    assertThat(runtimeContext.get("materializedSkillRoots", java.util.List.class)).isNull();
    assertThat(runtimeContext.get(PERMISSION_MODE_CONTEXT_KEY, String.class))
        .isEqualTo(PermissionMode.DEFAULT.name());
  }

  @Test
  void convertsAgentFailuresToProtocolErrorEvent() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.error(new IllegalStateException("boom")));

    AgentScopeChatAgentGateway gateway = new AgentScopeChatAgentGateway(executor, new AgentEventMapper());

    var events =
        gateway
            .stream(new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.BYPASS))
            .collectList()
            .block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry("message", "boom");
    });
  }

  @Test
  void convertsSdkThrowableEventsToProtocolErrorEventInsteadOfDroppingThem() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new IllegalArgumentException("sdk error event")));

    AgentScopeChatAgentGateway gateway = new AgentScopeChatAgentGateway(executor, new AgentEventMapper());

    var events =
        gateway
            .stream(new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.EXPLORE))
            .collectList()
            .block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry("message", "sdk error event");
    });
  }
}
