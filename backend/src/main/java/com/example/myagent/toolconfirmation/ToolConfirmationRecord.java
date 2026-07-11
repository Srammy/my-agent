package com.example.myagent.toolconfirmation;

import java.time.Instant;

public record ToolConfirmationRecord(
    String confirmationId,
    Long userId,
    String sessionId,
    String replyId,
    ToolCallSnapshot toolCall,
    ConfirmationKind kind,
    Instant createdAt,
    ToolConfirmationStatus status,
    String processingToken,
    Long leaseExpiresAtEpochMs,
    Boolean confirmed) {}
