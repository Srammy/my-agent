package com.example.myagent.chat;

import com.example.myagent.toolconfirmation.ToolConfirmationRecord;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;

public record StreamEventDto(String type, @JsonIgnore Map<String, Object> payload) {

  public StreamEventDto(String type) {
    this(type, Map.of());
  }

  @JsonAnyGetter
  public Map<String, Object> jsonFields() {
    return payload == null ? Map.of() : payload;
  }

  public static StreamEventDto replyStart() {
    return new StreamEventDto("reply_start");
  }

  public static StreamEventDto textDelta(String delta) {
    return new StreamEventDto("text_delta", Map.of("delta", delta));
  }

  public static StreamEventDto toolCall(String tool, Object input) {
    return new StreamEventDto("tool_call", Map.of("tool", tool, "input", input));
  }

  public static StreamEventDto toolResult(String tool, Object output) {
    return new StreamEventDto("tool_result", Map.of("tool", tool, "output", output));
  }

  public static StreamEventDto permissionRequired(String permission) {
    return new StreamEventDto("permission_required", Map.of("permission", permission));
  }

  public static StreamEventDto permissionRequired(ToolConfirmationRecord record) {
    List<Map<String, Object>> toolCalls = record.toolCalls().stream()
        .map(tool -> Map.<String, Object>of(
            "toolCallId", tool.id(),
            "toolName", tool.name(),
            "toolInput", tool.input()))
        .toList();
    return new StreamEventDto(
        "permission_required",
        Map.of(
            "permission", record.toolCalls().getFirst().name(),
            "confirmationId", record.confirmationId(),
            "replyId", record.replyId(),
            "toolCalls", toolCalls,
            "kind", record.kind().name()));
  }

  public static StreamEventDto evolutionProposal(String summary) {
    return new StreamEventDto("evolution_proposal", Map.of("summary", summary));
  }

  public static StreamEventDto done() {
    return new StreamEventDto("done");
  }

  public static StreamEventDto error(String message) {
    return new StreamEventDto("error", Map.of("message", message));
  }
}
