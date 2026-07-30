package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentScopeStreamExecutor;
import com.example.myagent.agent.AgentExecution;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AgentScopeChatAgentGatewayTest {

  private static final String PERMISSION_MODE_CONTEXT_KEY =
      ChatAgentRequest.PERMISSION_MODE_CONTEXT_KEY;

  @Mock private AgentScopeStreamExecutor executor;
  @Mock private ToolConfirmationService toolConfirmationService;

  @Test
  void preservesUnderlyingCompletionWhenMappingAgentEvents() {
    Sinks.Empty<Void> completion = Sinks.empty();
    when(executor.streamExecution(any(ChatAgentRequest.class), any()))
        .thenReturn(new AgentExecution<>(
            Flux.just(new AgentEndEvent("reply-1")), completion.asMono()));

    AgentExecution<StreamEventDto> execution = gateway().streamExecution(request());

    assertThat(execution.events().collectList().block())
        .containsExactly(StreamEventDto.done());
    StepVerifier.create(execution.completion())
        .expectSubscription()
        .then(() -> completion.tryEmitEmpty())
        .verifyComplete();
  }

  @Test
  void preservesUnderlyingCompletionWhenMappingConfirmationEvents() {
    Sinks.Empty<Void> completion = Sinks.empty();
    when(executor.confirmExecution(any(ChatToolConfirmationRequest.class), any()))
        .thenReturn(new AgentExecution<>(
            Flux.just(new AgentEndEvent("reply-1")), completion.asMono()));

    AgentExecution<StreamEventDto> execution =
        gateway().confirmExecution(confirmationRequest(true));

    assertThat(execution.events().collectList().block())
        .containsExactly(StreamEventDto.done());
    StepVerifier.create(execution.completion())
        .expectSubscription()
        .then(() -> completion.tryEmitEmpty())
        .verifyComplete();
  }

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
  void sdkThrowableEventTerminatesStreamBeforeLaterEvents() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(
            new TextBlockDeltaEvent("reply-1", "block-1", "before error"),
            new IllegalArgumentException("sdk error event"),
            new AgentEndEvent("reply-1")));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events)
        .extracting(StreamEventDto::type)
        .containsExactly("text_delta", "error");
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
          "toolCalls", List.of(Map.of(
              "toolCallId", "call-1", "toolName", "shell_command", "toolInput", input)),
          "kind", "USER_CONFIRM"));
    });
    verify(toolConfirmationService)
        .create(7L, "s_123", "reply-1", List.of(toolCall), ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void registersAndPublishesConfirmationWithNullToolInput() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("optional", null);
    input.put("command", "Get-ChildItem");
    ToolUseBlock toolCall = new ToolUseBlock("call-1", "shell_command", input);
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenAnswer(ignored -> Mono.fromCallable(() -> record("confirm-1", "reply-1", toolCall)));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("permission_required");
      assertThat((List<?>) event.payload().get("toolCalls")).singleElement().satisfies(tool ->
          assertThat(((Map<?, ?>) tool).get("toolInput")).isEqualTo(input));
    });
    verify(toolConfirmationService)
        .create(7L, "s_123", "reply-1", List.of(toolCall), ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void registersAllToolsFromAConfirmationEvent(CapturedOutput output) {
    ToolUseBlock first = new ToolUseBlock("call-1", "first", Map.of("one", 1));
    ToolUseBlock second = new ToolUseBlock("call-2", "second", Map.of("two", 2));
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", List.of(first, second))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(record("confirm-1", "reply-1", first, second)));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("permission_required");
      assertThat((List<?>) event.payload().get("toolCalls")).hasSize(2);
    });
    verify(toolConfirmationService, times(1))
        .create(7L, "s_123", "reply-1", List.of(first, second), ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void waitsForConfirmationRegistrationBeforePublishingLaterEvents() {
    ToolUseBlock toolCall = new ToolUseBlock("call-1", "shell_command", Map.of());
    Sinks.One<ToolConfirmationRecord> registration = Sinks.one();
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(
            new RequireUserConfirmEvent("reply-1", List.of(toolCall)),
            new AgentEndEvent("reply-1")));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(registration.asMono());

    var events = new java.util.ArrayList<StreamEventDto>();
    var subscription = gateway().stream(request()).subscribe(events::add);

    assertThat(events).isEmpty();
    registration.tryEmitValue(record("confirm-1", "reply-1", toolCall));
    assertThat(events).extracting(StreamEventDto::type)
        .containsExactly("permission_required", "done");
    subscription.dispose();
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
  void emitsErrorWithoutRegisteringWhenConfirmationToolsAreNull() {
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-1", null)));

    var events = gateway().stream(request()).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry(
          "message", "AgentScope confirmation event did not include a tool call");
    });
    verify(toolConfirmationService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void emitsErrorWithoutRegisteringForBlankOrDuplicateToolCallIds() {
    ToolUseBlock blank = new ToolUseBlock(" ", "read_file", Map.of());
    ToolUseBlock first = new ToolUseBlock("call-1", "read_file", Map.of());
    ToolUseBlock duplicate = new ToolUseBlock("call-1", "shell_command", Map.of());
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(
            Flux.just(new RequireUserConfirmEvent("reply-1", List.of(blank))),
            Flux.just(new RequireUserConfirmEvent("reply-1", List.of(first, duplicate))));

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThat(gateway().stream(request()).collectList().block()).singleElement().satisfies(event -> {
        assertThat(event.type()).isEqualTo("error");
        assertThat(event.payload()).containsEntry(
            "message", "AgentScope confirmation event included invalid or duplicate tool call ids");
      });
    }
    verify(toolConfirmationService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void convertsConfirmationRegistrationFailureToProtocolError() {
    ToolUseBlock toolCall = new ToolUseBlock("call-1", "shell_command", Map.of());
    Sinks.One<ToolConfirmationRecord> registration = Sinks.one();
    when(executor.stream(any(ChatAgentRequest.class), any()))
        .thenReturn(Flux.just(
            new RequireUserConfirmEvent("reply-1", List.of(toolCall)),
            new AgentEndEvent("reply-1")));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(registration.asMono());

    var events = new java.util.ArrayList<StreamEventDto>();
    var subscription = gateway().stream(request()).subscribe(events::add);

    assertThat(events).isEmpty();
    registration.tryEmitError(new IllegalStateException("redis unavailable"));
    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("error");
      assertThat(event.payload()).containsEntry("message", "redis unavailable");
    });
    subscription.dispose();
  }

  @Test
  void confirmationPassesTrustedSnapshotAndRequestScopeToExecutor() {
    ToolCallSnapshot snapshot =
        new ToolCallSnapshot("call-1", "shell_command", Map.of("command", "Get-ChildItem"));
    ChatToolConfirmationRequest request =
        new ChatToolConfirmationRequest(
            7L, "s_123", PermissionMode.ACCEPT_EDITS,
            List.of(new ToolCallDecision(snapshot, true)));
    when(executor.confirm(any(ChatToolConfirmationRequest.class), any()))
        .thenReturn(Flux.just(new TextBlockDeltaEvent("reply-1", "block-1", "resumed")));

    var events = gateway().confirm(request).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("text_delta");
      assertThat(event.payload()).containsEntry("delta", "resumed");
    });
    ArgumentCaptor<ChatToolConfirmationRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    ArgumentCaptor<Object> runtimeContextCaptor = ArgumentCaptor.forClass(Object.class);
    verify(executor).confirm(requestCaptor.capture(), runtimeContextCaptor.capture());
    assertThat(requestCaptor.getValue()).isEqualTo(request);
    RuntimeContext runtimeContext = (RuntimeContext) runtimeContextCaptor.getValue();
    assertThat(runtimeContext.getUserId()).isEqualTo("7");
    assertThat(runtimeContext.getSessionId()).isEqualTo("s_123");
    assertThat(runtimeContext.get(PERMISSION_MODE_CONTEXT_KEY, String.class))
        .isEqualTo(PermissionMode.ACCEPT_EDITS.name());
  }

  @Test
  void confirmationRegistersFollowUpUserConfirmationAndPublishesMetadata() {
    ToolUseBlock toolCall = new ToolUseBlock("call-2", "shell_command", Map.of("command", "pwd"));
    ToolConfirmationRecord record = record("confirm-2", "reply-2", toolCall);
    when(executor.confirm(any(ChatToolConfirmationRequest.class), any()))
        .thenReturn(Flux.just(new RequireUserConfirmEvent("reply-2", List.of(toolCall))));
    when(toolConfirmationService.create(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(record));

    var events = gateway().confirm(confirmationRequest(true)).collectList().block();

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.type()).isEqualTo("permission_required");
      assertThat(event.payload()).containsEntry("confirmationId", "confirm-2");
      assertThat((List<?>) event.payload().get("toolCalls")).hasSize(1);
    });
    verify(toolConfirmationService)
        .create(7L, "s_123", "reply-2", List.of(toolCall), ConfirmationKind.USER_CONFIRM);
  }

  @Test
  void confirmationPropagatesReactiveExecutorErrors() {
    ChatToolConfirmationRequest request = confirmationRequest(true);
    when(executor.confirm(any(ChatToolConfirmationRequest.class), any()))
        .thenReturn(Flux.error(new IllegalStateException("redis unavailable")));

    StepVerifier.create(gateway().confirm(request))
        .expectErrorMatches(
            throwable ->
                throwable instanceof IllegalStateException
                    && throwable.getMessage().equals("redis unavailable"))
        .verify();
  }

  @Test
  void confirmationPropagatesThrowableEvents() {
    ChatToolConfirmationRequest request = confirmationRequest(false);
    when(executor.confirm(any(ChatToolConfirmationRequest.class), any()))
        .thenReturn(Flux.just(new IllegalArgumentException("sdk confirmation error")));

    StepVerifier.create(gateway().confirm(request))
        .expectErrorMatches(
            throwable ->
                throwable instanceof IllegalArgumentException
                    && throwable.getMessage().equals("sdk confirmation error"))
        .verify();
  }

  private AgentScopeChatAgentGateway gateway() {
    return new AgentScopeChatAgentGateway(executor, new AgentEventMapper(), toolConfirmationService);
  }

  private ChatAgentRequest request() {
    return new ChatAgentRequest(7L, "s_123", "hello", PermissionMode.DEFAULT);
  }

  private ChatToolConfirmationRequest confirmationRequest(boolean confirmed) {
    return new ChatToolConfirmationRequest(
        7L,
        "s_123",
        PermissionMode.DEFAULT,
        List.of(new ToolCallDecision(
            new ToolCallSnapshot("call-1", "shell_command", Map.of("command", "Get-ChildItem")),
            confirmed)));
  }

  private ToolConfirmationRecord record(
      String confirmationId, String replyId, ToolUseBlock... toolCalls) {
    return new ToolConfirmationRecord(
        confirmationId,
        "7",
        "s_123",
        replyId,
        java.util.Arrays.stream(toolCalls)
            .map(ToolCallSnapshot::from)
            .toList(),
        ConfirmationKind.USER_CONFIRM,
        Instant.parse("2026-07-11T00:00:00Z"),
        ToolConfirmationStatus.PENDING,
        null,
        null,
        null);
  }
}
