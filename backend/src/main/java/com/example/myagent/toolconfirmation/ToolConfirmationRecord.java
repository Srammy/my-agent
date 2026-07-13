package com.example.myagent.toolconfirmation;

import java.time.Instant;
import java.util.List;

public record ToolConfirmationRecord(
    String confirmationId,
    String userId,
    String sessionId,
    String replyId,
    List<ToolCallSnapshot> toolCalls,
    ConfirmationKind kind,
    Instant createdAt,
    ToolConfirmationStatus status,
    String processingToken,
    Long leaseExpiresAtEpochMs,
    List<ToolConfirmationDecision> decisions) {}
