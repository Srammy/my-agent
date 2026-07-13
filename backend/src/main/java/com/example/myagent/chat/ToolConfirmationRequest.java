package com.example.myagent.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@JsonDeserialize(using = ToolConfirmationRequestDeserializer.class)
public record ToolConfirmationRequest(
    @NotEmpty @Valid List<ToolConfirmationDecisionRequest> decisions) {}

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
      if (!"decisions".equals(fieldName)) {
        context.reportInputMismatch(
            ToolConfirmationRequest.class,
            "Unrecognized field \"%s\" for ToolConfirmationRequest",
            fieldName);
      }
    }

    JsonNode decisionsNode = node.get("decisions");
    if (decisionsNode == null || decisionsNode.isNull()) {
      return new ToolConfirmationRequest(null);
    }
    if (!decisionsNode.isArray()) {
      context.reportInputMismatch(ToolConfirmationRequest.class, "Field \"decisions\" must be an array");
    }
    List<ToolConfirmationDecisionRequest> decisions = new ArrayList<>();
    for (JsonNode decisionNode : decisionsNode) {
      if (!decisionNode.isObject()) {
        context.reportInputMismatch(ToolConfirmationRequest.class, "Each decision must be an object");
      }
      Iterator<String> decisionFields = decisionNode.fieldNames();
      while (decisionFields.hasNext()) {
        String fieldName = decisionFields.next();
        if (!"toolCallId".equals(fieldName) && !"confirmed".equals(fieldName)) {
          context.reportInputMismatch(ToolConfirmationRequest.class,
              "Unrecognized field \"%s\" for tool confirmation decision", fieldName);
        }
      }
      JsonNode idNode = decisionNode.get("toolCallId");
      JsonNode confirmedNode = decisionNode.get("confirmed");
      if (idNode != null && !idNode.isNull() && !idNode.isTextual()) {
        context.reportInputMismatch(ToolConfirmationRequest.class, "Field \"toolCallId\" must be a string");
      }
      if (confirmedNode != null && !confirmedNode.isNull() && !confirmedNode.isBoolean()) {
        context.reportInputMismatch(ToolConfirmationRequest.class, "Field \"confirmed\" must be a boolean");
      }
      decisions.add(new ToolConfirmationDecisionRequest(
          idNode == null || idNode.isNull() ? null : idNode.textValue(),
          confirmedNode == null || confirmedNode.isNull() ? null : confirmedNode.booleanValue()));
    }
    return new ToolConfirmationRequest(decisions);
  }
}
