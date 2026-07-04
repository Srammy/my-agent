package com.example.myagent.chat;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
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
    if (agentEvent instanceof AgentEndEvent) {
      return StreamEventDto.done();
    }
    return null;
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
