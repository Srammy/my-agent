package com.example.myagent.chat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentEventMapper {

  public StreamEventDto map(Object agentEvent) {
    if (agentEvent instanceof AgentStartEvent) {
      return StreamEventDto.replyStart();
    }
    if (agentEvent instanceof Throwable throwable) {
      return StreamEventDto.error(errorMessage(throwable));
    }
    if (agentEvent instanceof TextBlockDeltaEvent textBlockDeltaEvent) {
      return StreamEventDto.textDelta(textBlockDeltaEvent.getDelta());
    }
    if (agentEvent instanceof ToolCallStartEvent toolCallStartEvent) {
      return StreamEventDto.toolCall(toolCallStartEvent.getToolCallName(), Map.of());
    }
    if (agentEvent instanceof ToolResultTextDeltaEvent toolResultTextDeltaEvent) {
      return StreamEventDto.toolResult(
          toolResultTextDeltaEvent.getToolCallName(), toolResultTextDeltaEvent.getDelta());
    }
    if (agentEvent instanceof ToolResultDataDeltaEvent toolResultDataDeltaEvent) {
      return StreamEventDto.toolResult(
          toolResultDataDeltaEvent.getToolCallName(), renderContent(toolResultDataDeltaEvent.getData()));
    }
    if (agentEvent instanceof RequireUserConfirmEvent requireUserConfirmEvent) {
      return StreamEventDto.permissionRequired(firstToolName(requireUserConfirmEvent.getToolCalls()));
    }
    if (agentEvent instanceof RequireExternalExecutionEvent requireExternalExecutionEvent) {
      return StreamEventDto.permissionRequired(
          firstToolName(requireExternalExecutionEvent.getToolCalls()));
    }
    if (agentEvent instanceof ExceedMaxItersEvent exceedMaxItersEvent) {
      return StreamEventDto.error(
          "Agent iteration limit reached (%d/%d)"
              .formatted(exceedMaxItersEvent.getCurrentIter(), exceedMaxItersEvent.getMaxIters()));
    }
    if (agentEvent instanceof RequestStopEvent) {
      return StreamEventDto.done();
    }
    if (agentEvent instanceof AgentEndEvent) {
      return StreamEventDto.done();
    }
    if (agentEvent instanceof AgentEvent baseAgentEvent) {
      return mapUnhandledAgentEvent(baseAgentEvent);
    }
    return null;
  }

  private StreamEventDto mapUnhandledAgentEvent(AgentEvent agentEvent) {
    // These SDK event types are lower-level stream markers with no current frontend protocol shape.
    return switch (agentEvent.getType()) {
      case AGENT_RESULT,
          MODEL_CALL_START,
          MODEL_CALL_END,
          TEXT_BLOCK_START,
          TEXT_BLOCK_END,
          THINKING_BLOCK_START,
          THINKING_BLOCK_DELTA,
          THINKING_BLOCK_END,
          DATA_BLOCK_START,
          DATA_BLOCK_DELTA,
          DATA_BLOCK_END,
          TOOL_CALL_DELTA,
          TOOL_CALL_END,
          TOOL_RESULT_START,
          TOOL_RESULT_END -> null;
      default -> StreamEventDto.error("Unhandled AgentScope event: " + agentEvent.getType().getValue());
    };
  }

  private String errorMessage(Throwable throwable) {
    return throwable.getMessage() == null || throwable.getMessage().isBlank()
        ? throwable.getClass().getSimpleName()
        : throwable.getMessage();
  }

  private String firstToolName(List<ToolUseBlock> toolCalls) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      return "confirmation";
    }
    return toolCalls.getFirst().getName();
  }

  private Object renderContent(Object content) {
    if (content instanceof TextBlock textBlock) {
      return textBlock.getText();
    }
    return content;
  }
}
