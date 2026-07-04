package com.example.myagent.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Iterator;

@JsonDeserialize(using = ChatRequestDeserializer.class)
public record ChatRequest(@NotBlank String message) {}

final class ChatRequestDeserializer extends StdDeserializer<ChatRequest> {

  ChatRequestDeserializer() {
    super(ChatRequest.class);
  }

  @Override
  public ChatRequest deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    JsonNode node = parser.getCodec().readTree(parser);
    if (!node.isObject()) {
      return (ChatRequest) context.handleUnexpectedToken(ChatRequest.class, parser);
    }

    Iterator<String> fieldNames = node.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!"message".equals(fieldName)) {
        context.reportInputMismatch(
            ChatRequest.class, "Unrecognized field \"%s\" for ChatRequest", fieldName);
      }
    }

    JsonNode messageNode = node.get("message");
    if (messageNode == null || messageNode.isNull()) {
      return new ChatRequest(null);
    }
    if (!messageNode.isTextual()) {
      context.reportInputMismatch(ChatRequest.class, "Field \"message\" must be a string");
    }

    return new ChatRequest(messageNode.asText());
  }
}
