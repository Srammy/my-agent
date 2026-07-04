package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventMapperTest {

  private final AgentEventMapper mapper = new AgentEventMapper();

  @Test
  void mapsAgentStartToReplyStart() {
    StreamEventDto event = mapper.map(new AgentStartEvent("s_123", "reply-1", "assistant"));

    assertThat(event.type()).isEqualTo("reply_start");
    assertThat(event.payload()).isEmpty();
  }

  @Test
  void mapsTextDeltaToTextDeltaEvent() {
    StreamEventDto event = mapper.map(new TextBlockDeltaEvent("reply-1", "block-1", "hello"));

    assertThat(event.type()).isEqualTo("text_delta");
    assertThat(event.payload()).containsEntry("delta", "hello");
  }

  @Test
  void mapsToolCallStartToToolCallEvent() {
    StreamEventDto event = mapper.map(new ToolCallStartEvent("reply-1", "call-1", "shell_command"));

    assertThat(event.type()).isEqualTo("tool_call");
    assertThat(event.payload()).containsEntry("tool", "shell_command");
  }

  @Test
  void mapsToolResultDeltaToToolResultEvent() {
    StreamEventDto event =
        mapper.map(new ToolResultTextDeltaEvent("reply-1", "call-1", "shell_command", "done"));

    assertThat(event.type()).isEqualTo("tool_result");
    assertThat(event.payload())
        .containsEntry("tool", "shell_command")
        .containsEntry("output", "done");
  }

  @Test
  void mapsConfirmRequestToPermissionRequiredEvent() {
    ToolUseBlock toolUseBlock = shellCommandToolCall();

    StreamEventDto event =
        mapper.map(new RequireUserConfirmEvent("reply-1", List.of(toolUseBlock)));

    assertThat(event.type()).isEqualTo("permission_required");
    assertThat(event.payload()).containsEntry("permission", "shell_command");
  }

  @Test
  void mapsRequireExternalExecutionToPermissionRequiredEvent() {
    StreamEventDto event =
        mapper.map(new RequireExternalExecutionEvent("reply-1", List.of(shellCommandToolCall())));

    assertThat(event.type()).isEqualTo("permission_required");
    assertThat(event.payload()).containsEntry("permission", "shell_command");
  }

  @Test
  void mapsExceedMaxItersToErrorEvent() {
    StreamEventDto event = mapper.map(new ExceedMaxItersEvent("reply-1", 8, 8));

    assertThat(event.type()).isEqualTo("error");
    assertThat(event.payload())
        .containsEntry("message", "Agent iteration limit reached (8/8)");
  }

  @Test
  void mapsRequestStopToDoneEvent() {
    StreamEventDto event =
        mapper.map(new RequestStopEvent("reply-1", GenerateReason.ACTING_STOP_REQUESTED));

    assertThat(event.type()).isEqualTo("done");
    assertThat(event.payload()).isEmpty();
  }

  @Test
  void mapsAgentEndToDone() {
    StreamEventDto event = mapper.map(new AgentEndEvent("reply-1"));

    assertThat(event.type()).isEqualTo("done");
    assertThat(event.payload()).isEmpty();
  }

  @Test
  void mapsThrowableEventsToError() {
    StreamEventDto event = mapper.map(new IllegalStateException("sdk exploded"));

    assertThat(event.type()).isEqualTo("error");
    assertThat(event.payload()).containsEntry("message", "sdk exploded");
  }

  private ToolUseBlock shellCommandToolCall() {
    return new ToolUseBlock("call-1", "shell_command", Map.of("command", "Get-ChildItem"));
  }
}
