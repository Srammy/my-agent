package com.example.myagent.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Iterator;

@JsonDeserialize(using = ToolConfirmationRequestDeserializer.class)
public record ToolConfirmationRequest(@NotNull Boolean confirmed) {}

final class ToolConfirmationRequestDeserializer extends StdDeserializer<ToolConfirmationRequest> {

  ToolConfirmationRequestDeserializer() {
    super(ToolConfirmationRequest.class);
  }

  @Override
  public ToolConfirmationRequest deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    JsonNode node = parser.getCodec().readTree(parser);
    if (!node.isObject()) {
      return (ToolConfirmationRequest) context.handleUnexpectedToken(ToolConfirmationRequest.class, parser);
    }

    Iterator<String> fieldNames = node.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!"confirmed".equals(fieldName)) {
        context.reportInputMismatch(
            ToolConfirmationRequest.class,
            "Unrecognized field \"%s\" for ToolConfirmationRequest",
            fieldName);
      }
    }

    JsonNode confirmedNode = node.get("confirmed");
    if (confirmedNode == null || confirmedNode.isNull()) {
      return new ToolConfirmationRequest(null);
    }
    if (!confirmedNode.isBoolean()) {
      context.reportInputMismatch(ToolConfirmationRequest.class, "Field \"confirmed\" must be a boolean");
    }
    return new ToolConfirmationRequest(confirmedNode.booleanValue());
  }
}
