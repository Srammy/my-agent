package com.example.myagent.toolconfirmation;

import io.agentscope.core.message.ToolUseBlock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallSnapshot(String id, String name, Map<String, Object> input) {
  public static ToolCallSnapshot from(ToolUseBlock toolCall) {
    return new ToolCallSnapshot(toolCall.getId(), toolCall.getName(),
        Collections.unmodifiableMap(new LinkedHashMap<>(toolCall.getInput())));
  }

  public ToolUseBlock toToolUseBlock() {
    return new ToolUseBlock(id, name, input);
  }
}
