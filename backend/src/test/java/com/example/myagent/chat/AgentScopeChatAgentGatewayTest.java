package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolCallSnapshot;
import com.example.myagent.toolconfirmation.ToolConfirmationRecord;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import com.example.myagent.toolconfirmation.ToolConfirmationStatus;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AgentScopeChatAgentGatewayTest {

  private static final String PERMISSION_MODE_CONTEXT_KEY =
      ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY;

  @Mock private AgentScopeStreamExecutor executor;
  @Mock private ToolConfirmationService toolConfirmationService;

  @Test
  void mapsAgentScopeEventsAndBuildsRuntimeContextFromRequest() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(
            Flux.just(
                new AgentStartEvent("s_123", "reply-1", "assistant"),
                new TextBlockDeltaEvent("reply-1", "block-1", "world"),
                new AgentEndEvent("reply-1")));

    AgentScopeChatAgentGateway gateway = gateway();

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

    AgentScopeChatAgentGateway gateway = gateway();

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

    AgentScopeChatAgentGateway gateway = gateway();

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

  @Test
  void registersUserConfirmationAndPublishesStableMetadata() {
    Map<String, Object> input = Map.of("command", "Get-ChildItem", "timeout", 30);
    ToolUseBlock toolCall = new ToolUseBlock("call-1", "shell_command", input);
    ToolConfirmationRecord record = record("confirm-1", "reply-1", toolCall);
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(record));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("permission_required");
      assertThat(event.payload()).containsExactlyInAnyOrderEntriesOf(Map.of(
          "permission", "shell_command",
          "confirmationId", "confirm-1",
          "replyId", "reply-1",
          "toolCallId", "call-1",
          "toolName", "shell_command",
          "toolInput", input,
          "kind", "USER_CONFIRM"));
      assertThat(event.payload().get("toolInput")).isEqualTo(input);
    });
    verify(toolConfirmationService)
        .create(7L, "s_123", "reply-1", toolCall, ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void registersOnlyTheFirstToolFromAConfirmationEvent() {
    ToolUseBlock first = new ToolUseBlock("call-1", "first", Map.of("one", 1));
    ToolUseBlock second = new ToolUseBlock("call-2", "second", Map.of("two", 2));
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of(first, second))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(record("confirm-1", "reply-1", first)));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event ->
        assertThat(event.payload()).containsEntry("toolCallId", "call-1"));
    verify(toolConfirmationService, times(1))
        .create(7L, "s_123", "reply-1", first, ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void emitsErrorWithoutRegisteringWhenConfirmationHasNoTool() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of())));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry(
          "message", "AgentScope confirmation event did not include a tool call");
    });
    verify(toolConfirmationService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void convertsConfirmationRegistrationFailureToProtocolError() {
    ToolUseBlock toolCall = new ToolUseBlock("call-1", "shell_command", Map.of());
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry("message", "redis unavailable");
    });
  }

  private AgentScopeChatAgentGateway gateway() {
    return new AgentScopeChatAgentGateway(executor, new AgentEventMapper(), toolConfirmationService);
  }

  private ChatAgentRequest request() {
    return new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.DEFAULT);
  }

  private ToolConfirmationRecord record(
      String confirmationId, String replyId, ToolUseBlock toolCall) {
    return new ToolConfirmationRecord(
        confirmationId,
        7L,
        "s_123",
        replyId,
        new ToolCallSnapshot(toolCall.getId(), toolCall.getName(), toolCall.getInput()),
        ConfirmationKind.USER_CONFIRM,
        Instant.parse("2026-07-11T00:00:00Z"),
        ToolConfirmationStatus.PENDING,
        null,
        null,
        null);
  }
}
